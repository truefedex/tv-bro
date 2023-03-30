import java.util.*


plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
}

val properties = Properties()
val localPropertiesFile: File = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { properties.load(it) }
}

var includeFirebase = true

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

    flavorDimensions += listOf("appstore")
    productFlavors {
        create("generic") {
            dimension = "appstore"
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "true")
            //when distributing as an universal apk, the size of the distribution apk is more
            //important than the size after installation
            manifestPlaceholders["extractNativeLibs"] = "true"
            packagingOptions.jniLibs.useLegacyPackaging = true
        }
        create("google") {
            dimension = "appstore"
            //now auto-update violates Google Play policies
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "false")
            manifestPlaceholders["extractNativeLibs"] = "false"
        }
        create("foss") {
            dimension = "appstore"
            applicationIdSuffix = ".foss"
            versionNameSuffix = "-foss"
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "false")
            manifestPlaceholders["extractNativeLibs"] = "true"
            packagingOptions.jniLibs.useLegacyPackaging = true
            includeFirebase = false//do not include firebase in the foss build
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility to JavaVersion.VERSION_11
        targetCompatibility to JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.5.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.2.1")

    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.10")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.0")

    val roomVersion = "2.5.0"
    implementation("androidx.room:room-runtime:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")

    implementation("com.github.truefedex:segmented-button:v1.0.0")
    implementation("com.github.truefedex:ad-block:v0.0.1-ci")
    implementation("de.halfbit:pinned-section-listview:1.0.0")

    val geckoViewChannel = "beta"
    val geckoViewVersion = "112.0.20230326180212"
    implementation("org.mozilla.geckoview:geckoview-$geckoViewChannel:$geckoViewVersion")

    //"debugImplementation"("com.squareup.leakcanary:leakcanary-android:2.7")

    "googleImplementation"("com.google.firebase:firebase-core:21.1.1")
    "googleImplementation"("com.google.firebase:firebase-crashlytics-ktx:18.3.5")

    "genericImplementation"("com.google.firebase:firebase-core:21.1.1")
    "genericImplementation"("com.google.firebase:firebase-crashlytics-ktx:18.3.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.9")
}

if(includeFirebase) {
    plugins {
        id("com.google.gms.google-services")
        id("com.google.firebase.crashlytics")
    }
}