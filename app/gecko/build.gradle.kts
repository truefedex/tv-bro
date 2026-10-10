plugins {
    id("tvbro.android.feature")
}

android {
    namespace = "com.phlox.tvwebbrowser.webengine.gecko"
    defaultConfig {
        minSdk = 26
        missingDimensionStrategy("appstore", "generic")
        missingDimensionStrategy("webengine", "geckoIncluded")
    }
}

dependencies {
    // this dependency added only because of the dynamic feature module requirement, otherwise
    // it should not be needed
    implementation(project(":app"))

    implementation(project(":app:common"))
    implementation(libs.androidx.appcompat)
    implementation(libs.geckoview)
    testImplementation(libs.junit)
}
