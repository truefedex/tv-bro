import java.util.zip.CRC32

plugins {
    id("tvbro.android.feature")
}

/** The built-in home page of the app; GeckoView shows it as a page of the extension */
val homePageDir: File = rootProject.file("app/src/main/assets/pages/home")
val webExtensionDir: File = file("src/geckoIncluded/assets/extensions/generic")

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
    // minSdk must match the base app in every variant, so it comes from the convention plugin
    // (the geckoIncluded flavor raises it to 26)

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("int", "WEB_EXTENSION_HASH", contentHash(webExtensionDir, homePageDir).toString())
    }

    buildTypes {
        getByName("release") {
            // R8 runs in the base module, but each feature passes its own keep rules
            proguardFiles("proguard-rules.pro")
        }
    }
}

androidComponents {
    onVariants(selector().withFlavor("webengine" to "geckoIncluded")) { variant ->
        val copyTask = tasks.register<CopyHomePageToExtension>(
            "copyHomePageToExtension${variant.name.replaceFirstChar { it.uppercase() }}") {
            homePage.set(homePageDir)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyTask, CopyHomePageToExtension::outputDir)
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
