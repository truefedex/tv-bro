plugins {
    id("tvbro.android.application")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.phlox.tvwebbrowser"

    defaultConfig {
        applicationId = "com.phlox.tvwebbrowser"
        versionCode = 74
        versionName = "2.2.0"

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

    packaging {
        jniLibs {
            // GeckoView has no x86 build, and a bundle requires all modules with native
            // libraries to support the same ABIs
            excludes += "lib/x86/libad-block.so"
        }
    }

    // flavors themselves are declared by the tvbro.android.application convention plugin
    productFlavors {
        getByName("generic") {
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "true")
        }
        getByName("google") {
            //now auto-update violates Google Play policies
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "false")
        }
        getByName("foss") {
            applicationIdSuffix = ".foss"
            buildConfigField("Boolean", "BUILT_IN_AUTO_UPDATE", "false")
        }

        getByName("geckoIncluded") {
            buildConfigField("Boolean", "GECKO_SUPPORTED", "true")
        }
        getByName("geckoExcluded") {
            buildConfigField("Boolean", "GECKO_SUPPORTED", "false")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    dynamicFeatures += setOf(":app:gecko")

    // On-demand feature delivery (Play Core) is used only by the Google Play build: the generic
    // build is not published on Play, and the foss build must not contain proprietary libraries.
    sourceSets {
        getByName("generic").kotlin.srcDir("src/withoutPlayCore/java")
        getByName("foss").kotlin.srcDir("src/withoutPlayCore/java")
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

    "googleImplementation"(libs.google.feature.delivery)
    "googleImplementation"(libs.google.feature.delivery.ktx)

    // GeckoView brings Guava into the dynamic feature, which also defines
    // ListenableFuture. The 1.0 jar pulled in by AndroidX would make R8 see
    // that type twice; this artifact is empty and replaces it.
    "geckoIncludedImplementation"(libs.guava.listenablefuture)
    // AndroidX code in the base module needs ListenableFuture even when the
    // feature is not downloaded yet, so Guava must be in the base module too.
    "geckoIncludedImplementation"(libs.guava)

    // GeckoView depends on Play services, and the gecko module manifest that is merged into
    // the base one references their @integer/google_play_services_version resource.
    "geckoIncludedImplementation"(libs.play.services.basement)

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
    testImplementation(libs.kotlinx.coroutines.test)
}