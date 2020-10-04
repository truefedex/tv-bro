import org.jetbrains.kotlin.config.KotlinCompilerVersion
import java.util.*


plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("android.extensions")
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
    compileSdkVersion(29)
    buildToolsVersion = "29.0.3"

    defaultConfig {
        applicationId = "com.phlox.tvwebbrowser"
        minSdkVersion(21)
        targetSdkVersion(29)
        versionCode = 42
        versionName = "1.5.8"

        javaCompileOptions {
            annotationProcessorOptions {
                arguments = mapOf("room.incremental" to "true")
            }
        }
    }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file(properties.getProperty("storeFile"))
            storePassword = properties.getProperty("storePassword")
            keyAlias = properties.getProperty("keyAlias")
            keyPassword = properties.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            isDebuggable = true
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig=signingConfigs.getByName("release")
        }
    }

    flavorDimensions("crashlytics", "appstore")
    productFlavors {
        create("crashlyticsfree") {
            setDimension("crashlytics")
        }
        create("crashlytics") {
            setDimension("crashlytics")
            //versionNameSuffix "-crashlytics"
        }

        create("google") {
            setDimension("appstore")
        }
        create("amazon") {
            setDimension("appstore")
        }
    }

    lintOptions {
        isAbortOnError=false
    }

    kapt {
        arguments {
            //used when AppDatabase @Database annotation exportSchema = true. Useful for migrations
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    implementation("androidx.appcompat:appcompat:1.2.0")

    implementation(kotlin("stdlib-jdk7", KotlinCompilerVersion.VERSION))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.3.7")

    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.3.0-alpha07")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.3.0-alpha07")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.3.0-alpha07")
    kapt("androidx.lifecycle:lifecycle-compiler:2.2.0")

    val room_version = "2.2.5"
    implementation("androidx.room:room-runtime:$room_version")
    kapt("androidx.room:room-compiler:$room_version")
    implementation("androidx.room:room-ktx:$room_version")

    //implementation("androidx.webkit:webkit:1.3.0")

    implementation("com.github.truefedex:segmented-button:v1.0.0")
    implementation("de.halfbit:pinned-section-listview:1.0.0")

    "crashlyticsImplementation"("com.google.firebase:firebase-core:17.5.0")
    "crashlyticsImplementation"("com.google.firebase:firebase-crashlytics-ktx:17.2.1")
}

tasks.getByName("check").dependsOn("lint")