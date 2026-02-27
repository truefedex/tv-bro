plugins {
    id("tvbro.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.phlox.tvwebbrowser.common"
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.room.runtime)
    annotationProcessor(libs.androidx.room.compiler)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    testImplementation(libs.junit)
}
