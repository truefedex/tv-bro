plugins {
    id("com.android.library")
}

android {
    namespace = "com.phlox.tvwebbrowser.webengine.gecko"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":app:common"))

    implementation("androidx.appcompat:appcompat:1.7.1")

    //val geckoViewChannel = "beta"
    //val geckoViewVersion = "112.0.20230330182947"
    //implementation("org.mozilla.geckoview:geckoview-$geckoViewChannel:$geckoViewVersion")
    val geckoViewVersion = "121.0.20240108143603"
    implementation("org.mozilla.geckoview:geckoview:$geckoViewVersion")

    testImplementation("junit:junit:4.13.2")
}