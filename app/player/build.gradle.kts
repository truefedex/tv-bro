plugins {
    id("tvbro.android.library")
}

android {
    namespace = "com.phlox.tvwebbrowser.player"
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)
    testImplementation(libs.junit)
}
