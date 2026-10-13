plugins {
    id("tvbro.android.application")
    alias(libs.plugins.ksp)
}

//The app version is driven by git tags, so cutting a release is just pushing a "vX.Y.Z" tag: the
//tag becomes versionName and the in-app updater (which compares GitHub release tags against the
//installed versionName) stays in sync by construction. -PversionName / -PversionCode override the
//git lookups (the release workflow passes the tag through), and a checkout without git (or without
//any tag) falls back to the last released version so local builds still install over released ones.
fun gitOutput(vararg args: String): String? = runCatching {
    val process = ProcessBuilder(listOf("git") + args)
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor() == 0) output.ifEmpty { null } else null
}.getOrNull()

//"v2.1.7" -> "2.1.7"; "v2.1.7-3-gabc1234" -> "2.1.7-dev.3" (a dev build 3 commits past the tag).
fun versionNameFromGit(): String? {
    gitOutput("describe", "--tags", "--exact-match", "HEAD")?.let { return it.removePrefix("v") }
    val described = gitOutput("describe", "--tags", "--long") ?: return null
    val match = Regex("^v?(\\d+(?:\\.\\d+)*)-(\\d+)-g[0-9a-f]+").find(described) ?: return null
    val (base, commitsSinceTag) = match.destructured
    return "$base-dev.$commitsSinceTag"
}

//versionCode grows monotonically with versionName ("2.1.7" -> 20107) so newer builds install over
//older ones. Only the first three dot-separated components are used.
fun versionCodeFromName(name: String): Int {
    val components = name.removePrefix("v").split('.', '-', '+').map { part ->
        part.takeWhile(Char::isDigit).toIntOrNull() ?: 0
    }
    return components.getOrElse(0) { 0 } * 10_000 + components.getOrElse(1) { 0 } * 100 +
        components.getOrElse(2) { 0 }
}

val appVersionName = (findProperty("versionName") as String?) ?: versionNameFromGit() ?: "2.1.6"
val appVersionCode = ((findProperty("versionCode") as String?)?.toIntOrNull()
    ?: versionCodeFromName(appVersionName)).coerceAtLeast(1)

android {
    namespace = "com.phlox.tvwebbrowser"

    defaultConfig {
        applicationId = "com.phlox.tvwebbrowser"
        versionCode = appVersionCode
        versionName = appVersionName

        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf(
                    "room.incremental" to "true",
                    //used when AppDatabase @Database annotation exportSchema = true. Useful for migrations
                    "room.schemaLocation" to "$projectDir/schemas"
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("KEYSTORE_PATH") != null)
                signingConfigs.getByName("release") else null
        }
    }

    splits {
        abi {
            isEnable = project.hasProperty("enableAbiSplits")
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    flavorDimensions += listOf("appstore", "webengine")
    productFlavors {
        create("generic") {
            dimension = "appstore"
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "true")
            //upstream project: keep updating from the original tv-bro releases
            buildConfigField("String", "UPDATE_REPO", "\"truefedex/tv-bro\"")
        }
        create("google") {
            dimension = "appstore"
            //now auto-update violates Google Play policies
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "false")
            buildConfigField("String", "UPDATE_REPO", "\"truefedex/tv-bro\"")
        }
        create("p403n1x87") {
            dimension = "appstore"
            applicationIdSuffix = ".p403n1x87"
            //this fork publishes its own releases (see .github/workflows/release.yml)
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "true")
            buildConfigField("String", "UPDATE_REPO", "\"P403n1x87/tv-bro\"")
        }

        create("geckoIncluded") {
            dimension = "webengine"
            minSdk = 26 // geckoview requires minSdk 26
        }
        create("geckoExcluded") {
            dimension = "webengine"
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":app:common"))
    implementation(project(":app:player"))
    "geckoIncludedImplementation"(project(":app:gecko"))

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.androidx.room.runtime)
    annotationProcessor(libs.androidx.room.compiler)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    implementation(libs.segmented.button)
    implementation(libs.ad.block)
    implementation(libs.pinned.section.listview)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}