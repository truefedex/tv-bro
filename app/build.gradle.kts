import java.util.*


plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

val properties = Properties()
val localPropertiesFile: File = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { properties.load(it) }
}

android {
    compileSdk = 33
    buildToolsVersion = "33.0.1"
    namespace = "com.phlox.tvwebbrowser"

    defaultConfig {
        applicationId = "com.phlox.tvwebbrowser"
        minSdk = 23
        targetSdk = 33
        versionCode = 59
        versionName = "1.8.6"

        javaCompileOptions {
            annotationProcessorOptions {
                argument("room.incremental", "true")
            }
        }
    }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file(properties.getProperty("storeFile", ""))
            storePassword = properties.getProperty("storePassword", "")
            keyAlias = properties.getProperty("keyAlias", "")
            keyPassword = properties.getProperty("keyPassword", "")
        }
    }
    buildTypes {
        getByName("debug") {
            isDebuggable = true
            project.setProperty("crashlytics", false)
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig=signingConfigs.getByName("release")
            project.setProperty("crashlytics", true)
        }
    }

    flavorDimensions += listOf("appstore")
    productFlavors {
        create("generic") {
            dimension = "appstore"
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "true")
            //when distributing as an apk, the size of the distribution apk is more
            //important than the size after installation
            manifestPlaceholders["extractNativeLibs"] = "true"
        }
        create("google") {
            dimension = "appstore"
            //now auto-update violates Google Play policies
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "false")
            manifestPlaceholders["extractNativeLibs"] = "false"
        }
        create("amazon") {
            dimension = "appstore"
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "false")
            manifestPlaceholders["extractNativeLibs"] = "true"
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility to JavaVersion.VERSION_1_8
        targetCompatibility to JavaVersion.VERSION_1_8
    }

    lint.disable += setOf("UNUSED_PARAMETER")

    kapt {
        arguments {
            //used when AppDatabase @Database annotation exportSchema = true. Useful for migrations
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    implementation("androidx.appcompat:appcompat:1.6.0")
    implementation("androidx.webkit:webkit:1.5.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.2.1")

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.7.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.5.1")

    val roomVersion = "2.4.3"
    implementation("androidx.room:room-runtime:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")

    implementation("com.github.truefedex:segmented-button:v1.0.0")
    implementation("com.github.truefedex:ad-block:v0.0.1-ci")
    implementation("de.halfbit:pinned-section-listview:1.0.0")

    "debugImplementation"("com.squareup.leakcanary:leakcanary-android:2.7")

    if (project.property("crashlytics") == true) {
        implementation("com.google.firebase:firebase-core:21.1.1")
        implementation("com.google.firebase:firebase-crashlytics-ktx:18.3.2")
    }
}

tasks.getByName("check").dependsOn("lint")