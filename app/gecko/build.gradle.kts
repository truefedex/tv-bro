plugins {
    id("tvbro.android.feature")
}

android {
    namespace = "com.phlox.tvwebbrowser.webengine.gecko"
    // minSdk must match the base app in every variant, so it comes from the convention plugin
    // (the geckoIncluded flavor raises it to 26)

    buildTypes {
        getByName("release") {
            // R8 runs in the base module, but each feature passes its own keep rules
            proguardFiles("proguard-rules.pro")
        }
    }
}

dependencies {
    // this dependency added only because of the dynamic feature module requirement, otherwise
    // it should not be needed
    implementation(project(":app"))

    implementation(project(":app:common"))
    implementation(libs.androidx.appcompat)
    // The module must exist in every variant of the base app, but in geckoExcluded variants it
    // is empty: all its sources live in the geckoIncluded source set.
    "geckoIncludedImplementation"(libs.geckoview)
    testImplementation(libs.junit)
}
