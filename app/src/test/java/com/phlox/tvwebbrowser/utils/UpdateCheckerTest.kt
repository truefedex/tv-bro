package com.phlox.tvwebbrowser.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The WebView and the GeckoView variants are different apps: each one gets only its own APKs from the version file.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {
    private val versionFile = """
        {
          "channels": [
            {
              "name": "release",
              "latestVersionName": "v3.0.0",
              "latestVersionCode": 80,
              "minAPI": 24,
              "url": "https://example.com/tvbro-3.0.0-generic-geckoExcluded.apk",
              "variants": {
                "geckoIncluded": {
                  "minAPI": 26,
                  "url": "https://example.com/tvbro-3.0.0-generic-geckoIncluded-armeabi-v7a.apk",
                  "urls": [
                    "https://example.com/tvbro-3.0.0-generic-geckoIncluded-arm64-v8a.apk",
                    "https://example.com/tvbro-3.0.0-generic-geckoIncluded-x86_64.apk"
                  ]
                }
              }
            },
            {
              "name": "beta",
              "latestVersionName": "v3.1.0",
              "latestVersionCode": 81,
              "minAPI": 24,
              "url": "https://example.com/tvbro-3.1.0-generic-geckoExcluded.apk"
            }
          ],
          "changelog": [
            { "versionCode": 80, "versionName": "v3.0.0", "changes": "* Changes" }
          ]
        }
    """.trimIndent()

    private fun parse(variant: String, channel: String, abi: String = "arm64-v8a", sdkInt: Int = 30) =
        UpdateChecker(70, variant).parseVersionFile(versionFile, arrayOf(channel), abi, sdkInt)

    @Test
    fun webViewVariantGetsTheApkOfTheChannel() {
        val result = parse("geckoExcluded", "release")
        assertEquals(80, result.latestVersionCode)
        assertEquals("https://example.com/tvbro-3.0.0-generic-geckoExcluded.apk", result.url)
    }

    @Test
    fun geckoVariantGetsItsOwnApkForTheAbi() {
        val result = parse("geckoIncluded", "release")
        assertEquals(80, result.latestVersionCode)
        assertEquals("https://example.com/tvbro-3.0.0-generic-geckoIncluded-arm64-v8a.apk", result.url)
    }

    @Test
    fun minApiOfTheVariantIsUsed() {
        assertEquals(0, parse("geckoIncluded", "release", sdkInt = 25).latestVersionCode)
        assertEquals(80, parse("geckoExcluded", "release", sdkInt = 25).latestVersionCode)
    }

    @Test
    fun geckoVariantGetsNoUpdateFromChannelWithoutItsApks() {
        val result = parse("geckoIncluded", "beta")
        assertEquals(0, result.latestVersionCode)
        assertEquals("", result.url)
        assertEquals(listOf("release", "beta"), result.availableChannels.toList())
    }
}
