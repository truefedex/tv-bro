import java.util.zip.CRC32

plugins {
    id("tvbro.android.library")
}

/** The built-in home page of the app; GeckoView shows it as a page of the extension */
val homePageDir: File = rootProject.file("app/src/main/assets/pages/home")
val webExtensionDir: File = file("src/main/assets/extensions/generic")

/**
 * A hash of the files of the extension (with the home page): GeckoWebEngine installs the extension again
 * when it changes
 */
fun contentHash(vararg dirs: File): Int {
    val crc = CRC32()
    for (dir in dirs) {
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).invariantSeparatorsPath }.forEach {
            crc.update(it.relativeTo(dir).invariantSeparatorsPath.toByteArray())
            crc.update(it.readBytes())
        }
    }
    return crc.value.toInt()
}

/** Copies the home page into the extension (assets/extensions/generic/home) */
abstract class CopyHomePageToExtension : DefaultTask() {
    @get:InputDirectory
    abstract val homePage: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val root = outputDir.get().asFile
        root.deleteRecursively()
        homePage.get().asFile.copyRecursively(root.resolve("extensions/generic/home"))
    }
}

android {
    namespace = "com.phlox.tvwebbrowser.webengine.gecko"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 26
        buildConfigField("int", "WEB_EXTENSION_HASH", contentHash(webExtensionDir, homePageDir).toString())
    }
}

androidComponents {
    onVariants { variant ->
        val copyTask = tasks.register<CopyHomePageToExtension>(
            "copyHomePageToExtension${variant.name.replaceFirstChar { it.uppercase() }}") {
            homePage.set(homePageDir)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyTask, CopyHomePageToExtension::outputDir)
    }
}

dependencies {
    implementation(project(":app:common"))
    implementation(libs.androidx.appcompat)
    implementation(libs.geckoview)
    testImplementation(libs.junit)
}
