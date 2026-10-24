plugins {
    id("tvbro.android.library")
}

// The ad blocker: filter lists of the Adblock Plus syntax (EasyList), compiled into a compact engine.
// Plain Kotlin without android.*, so its tests run on the JVM.
android {
    namespace = "com.phlox.tvwebbrowser.adblock"

    testOptions {
        unitTests.all { test ->
            // -PadblockLists=<dir>: real filter lists for the tests that need them (not in the repository)
            (project.findProperty("adblockLists") as String?)?.let { test.systemProperty("adblock.lists", it) }
            test.testLogging.showStandardStreams = project.hasProperty("adblockLists")
        }
    }
}

dependencies {
    testImplementation(libs.junit)
}
