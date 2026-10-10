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
    implementation(project(":app:player"))
    implementation(libs.androidx.appcompat)
    implementation(libs.geckoview)
    implementation(libs.androidx.media3.datasource)
    testImplementation(libs.junit)
}
