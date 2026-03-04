package tvbro

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

internal enum class AndroidModuleType { APPLICATION, LIBRARY }

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
            }
            // Do not apply org.jetbrains.kotlin.android — Gradle 9 registers the "kotlin" extension by default

            when (type) {
                AndroidModuleType.APPLICATION -> extensions.configure<ApplicationExtension> {
                    compileSdk = findVersion("android-compileSdk")
                    defaultConfig {
                        minSdk = findVersion("android-minSdk")
                        targetSdk = findVersion("android-targetSdk")
                    }
                }
                AndroidModuleType.LIBRARY -> extensions.configure<LibraryExtension> {
                    compileSdk = findVersion("android-compileSdk")
                    defaultConfig {
                        minSdk = findVersion("android-minSdk")
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
