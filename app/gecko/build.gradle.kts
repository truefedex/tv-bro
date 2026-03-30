plugins {
    id("tvbro.android.library")
}

android {
    namespace = "com.phlox.tvwebbrowser.webengine.gecko"
    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    implementation(project(":app:common"))
    implementation(libs.androidx.appcompat)
    implementation(libs.geckoview)
    testImplementation(libs.junit)
}
