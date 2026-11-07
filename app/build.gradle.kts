plugins {
    id("tvbro.android.application")
    alias(libs.plugins.ksp)
}

/** Picture-in-picture of the built-in player: `BuildConfig.PICTURE_IN_PICTURE` and its activities in the manifest */
fun com.android.build.api.dsl.ApplicationProductFlavor.pictureInPicture(enabled: Boolean) {
    buildConfigField("Boolean", "PICTURE_IN_PICTURE", enabled.toString())
    manifestPlaceholders["pictureInPicture"] = enabled.toString()
}

android {
    namespace = "com.phlox.tvwebbrowser"

    defaultConfig {
        applicationId = "com.phlox.tvwebbrowser"
        versionCode = 82
        versionName = "3.0.4"
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
            // -PdebugNotDebuggable: a debug build ART runs at full speed, for the benchmarks of AdblockBenchActivity
            isDebuggable = !project.hasProperty("debugNotDebuggable")
            applicationIdSuffix = ".dev"
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
            isDefault = true
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "true")
            pictureInPicture(true)
        }
        create("google") {
            dimension = "appstore"
            //now auto-update violates Google Play policies
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "false")
            //the quality guidelines of TV apps on Google Play allow no media playback in picture-in-picture
            pictureInPicture(false)
        }
        create("foss") {
            dimension = "appstore"
            applicationIdSuffix = ".foss"
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "false")
            pictureInPicture(true)
        }

        // each variant has one web engine, registered by TVBro.initWebEngineStuff()
        create("geckoIncluded") {
            dimension = "webengine"
            // a separate app: it can be installed next to the WebView one
            applicationIdSuffix = ".gecko"
            minSdk = 26 // geckoview requires minSdk 26
            buildConfigField("String", "WEB_ENGINE_CLASS",
                "\"com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine\"")
        }
        create("geckoExcluded") {
            dimension = "webengine"
            isDefault = true
            buildConfigField("String", "WEB_ENGINE_CLASS",
                "\"com.phlox.tvwebbrowser.webengine.webview.WebViewWebEngine\"")
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

    //https://github.com/robolectric/robolectric/issues/7642
    tasks.withType<Test>().configureEach {
        jvmArgs(
            "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED"
        )
    }
}

ksp {
    // exported schemas are used by migration tests
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":app:common"))
    implementation(project(":adblock"))
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
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    implementation(libs.qrcodegen)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}