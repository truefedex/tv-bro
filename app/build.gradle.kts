plugins {
    id("tvbro.android.application")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.phlox.tvwebbrowser"

    defaultConfig {
        applicationId = "com.phlox.tvwebbrowser"
        versionCode = 69
        versionName = "2.1.6"

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
            isShrinkResources = true
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
        }
        create("google") {
            dimension = "appstore"
            //now auto-update violates Google Play policies
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "false")
        }
        create("foss") {
            dimension = "appstore"
            applicationIdSuffix = ".foss"
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "false")
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

    dynamicFeatures += setOf(":app:gecko")

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    //https://github.com/robolectric/robolectric/issues/7642
    tasks.withType<Test>().configureEach {
        jvmArgs(
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED"
        )
    }
}

dependencies {
    implementation(project(":app:common"))

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

    implementation(libs.google.feature.delivery)
    implementation(libs.google.feature.delivery.ktx)

    // GeckoView brings Guava into the dynamic feature, which also defines
    // ListenableFuture. The 1.0 jar pulled in by AndroidX would make R8 see
    // that type twice; this artifact is empty and replaces it.
    implementation(libs.guava.listenablefuture)

    constraints {
        // feature-delivery pulls older Play services than GeckoView. R8 ships
        // the base module's copies, so they must be new enough for the feature.
        implementation(libs.play.services.basement)
        implementation(libs.play.services.tasks)
    }

    implementation(libs.segmented.button)
    implementation(libs.ad.block)
    implementation(libs.pinned.section.listview)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}