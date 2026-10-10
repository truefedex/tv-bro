package tvbro

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.DynamicFeatureExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

internal enum class AndroidModuleType { APPLICATION, LIBRARY, FEATURE }

internal object TvBroAndroidModulePlugin {

    fun configure(project: Project, type: AndroidModuleType) {
        val catalogs = project.extensions.getByType<VersionCatalogsExtension>()
        val libs = catalogs.named("libs")
        // Helper function to get versions as integers
        fun findVersion(alias: String): Int =
            libs.findVersion(alias).get().requiredVersion.toInt()

        with(project) {
            when (type) {
                AndroidModuleType.APPLICATION -> pluginManager.apply("com.android.application")
                AndroidModuleType.LIBRARY -> pluginManager.apply("com.android.library")
                AndroidModuleType.FEATURE -> pluginManager.apply("com.android.dynamic-feature")
            }

            extensions.configure<CommonExtension> {
                compileSdk = findVersion("android-compileSdk")
                compileSdkMinor = findVersion("android-compileSdkMinor")
            }

            when (type) {
                AndroidModuleType.APPLICATION -> extensions.configure<ApplicationExtension> {
                    defaultConfig {
                        minSdk = findVersion("android-minSdk")
                        targetSdk = findVersion("android-targetSdk")
                    }
                }
                AndroidModuleType.LIBRARY -> extensions.configure<LibraryExtension> {
                    defaultConfig {
                        minSdk = findVersion("android-minSdk")
                        consumerProguardFiles("consumer-rules.pro")
                    }
                }
                AndroidModuleType.FEATURE -> extensions.configure<DynamicFeatureExtension> {
                    defaultConfig {
                        minSdk = findVersion("android-minSdk")
                    }
                }
            }
        }
    }
}

class TvBroAndroidApplicationPlugin : Plugin<Project> {
    override fun apply(project: Project) = TvBroAndroidModulePlugin.configure(project, AndroidModuleType.APPLICATION)
}

class TvBroAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project) = TvBroAndroidModulePlugin.configure(project, AndroidModuleType.LIBRARY)
}

class TvBroAndroidFeaturePlugin : Plugin<Project> {
    override fun apply(project: Project) = TvBroAndroidModulePlugin.configure(project, AndroidModuleType.FEATURE)
}
