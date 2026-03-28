// Top-level build file where you can add configuration options common to all sub-projects/modules.

// Android and Kotlin plugins are applied by buildSrc convention plugins (tvbro.android.library / tvbro.android.application).
// Only declare here plugins that subprojects apply via the version catalog and are not on buildSrc classpath.
plugins {
    alias(libs.plugins.ksp) apply false
}
