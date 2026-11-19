plugins {
    id("tvbro.android.library")
    alias(libs.plugins.ksp)
}

/**
 * The partner ID of Qwant (the `client` parameter of its searches). It is not in the repository, so forks do not
 * send it: `-PqwantClientId=<id>` (or `qwantClientId` in ~/.gradle/gradle.properties) or the env var QWANT_CLIENT_ID
 * (a secret of the release workflow). Builds without it list Qwant without the parameter and keep Google the default.
 */
val qwantClientId: String = providers.gradleProperty("qwantClientId")
    .orElse(providers.environmentVariable("QWANT_CLIENT_ID")).getOrElse("").trim()
require(qwantClientId.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }) {
    "qwantClientId may have only letters, digits, '-', '_' and '.'"
}

android {
    namespace = "com.phlox.tvwebbrowser.common"

    defaultConfig {
        buildConfigField("String", "QWANT_CLIENT_ID", "\"$qwantClientId\"")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    testImplementation(libs.junit)
}
