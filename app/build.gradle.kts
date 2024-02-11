import java.util.*


plugins {
    id("com.android.application")
    kotlin("android")
    id("com.google.devtools.ksp")
}

val properties = Properties()
val localPropertiesFile: File = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { properties.load(it) }
}

var includeFirebase = true

android {
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    namespace = "com.phlox.tvwebbrowser"

    defaultConfig {
        applicationId = "com.phlox.tvwebbrowser"
        minSdk = 24
        targetSdk = 34
        versionCode = 61
        versionName = "2.0.1"

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
            storeFile = properties.getProperty("storeFile", null)?.let { rootProject.file(it) }
            storePassword = properties.getProperty("storePassword", "")
            keyAlias = properties.getProperty("keyAlias", "")
            keyPassword = properties.getProperty("keyPassword", "")
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig=signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                val flavour = variant.flavorName
                //val builtType = variant.buildType.name
                val versionName = variant.versionName
                val arch = output.filters.first().identifier
                output.outputFileName =
                    "tvbro-${flavour}-${versionName}(${arch}).apk"
            }
    }

    flavorDimensions += listOf("appstore")
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
            includeFirebase = false//do not include firebase in the foss build
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.10")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    annotationProcessor("androidx.room:room-compiler:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")

    implementation("com.github.truefedex:segmented-button:v1.0.0")
    implementation("com.github.truefedex:ad-block:v0.0.1-ci")
    implementation("de.halfbit:pinned-section-listview:1.0.0")

    //val geckoViewChannel = "beta"
    //val geckoViewVersion = "112.0.20230330182947"
    //implementation("org.mozilla.geckoview:geckoview-$geckoViewChannel:$geckoViewVersion")
    val geckoViewVersion = "121.0.20240108143603"
    implementation("org.mozilla.geckoview:geckoview:$geckoViewVersion")

    //"debugImplementation"("com.squareup.leakcanary:leakcanary-android:2.7")

    "googleImplementation"("com.google.firebase:firebase-core:21.1.1")
    "googleImplementation"("com.google.firebase:firebase-crashlytics-ktx:18.6.2")

    "genericImplementation"("com.google.firebase:firebase-core:21.1.1")
    "genericImplementation"("com.google.firebase:firebase-crashlytics-ktx:18.6.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.9")
}

if(includeFirebase) {
    plugins {
        id("com.google.gms.google-services")
        id("com.google.firebase.crashlytics")
    }
}