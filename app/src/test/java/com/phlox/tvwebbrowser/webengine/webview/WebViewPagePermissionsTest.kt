package com.phlox.tvwebbrowser.webengine.webview

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.PermissionRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebViewPagePermissionsTest {
    private class FakeRequest(origin: String, private vararg val res: String) : PermissionRequest() {
        private val uri = Uri.parse(origin)
        var answer: String? = null
        var grantedResources: List<String>? = null
        override fun getOrigin(): Uri = uri
        override fun getResources(): Array<String> = arrayOf(*res)
        override fun grant(resources: Array<String>) {
            check(answer == null) { "answered twice" }
            answer = "grant"; grantedResources = resources.toList()
        }
        override fun deny() {
            check(answer == null) { "answered twice" }
            answer = "deny"
        }
    }

    private class FakeHost : WebViewPagePermissions.Host {
        val androidGranted = mutableSetOf(Manifest.permission.RECORD_AUDIO)
        val dialogs = ArrayList<(Boolean) -> Unit>()
        var dismissed = 0
        var askedAndroid: List<String>? = null
        override fun hasAndroidPermission(permission: String) = permission in androidGranted
        override fun requestAndroidPermissions(permissions: Array<String>) { askedAndroid = permissions.toList() }
        override fun showDialog(resources: Array<String>, onAnswer: (Boolean) -> Unit): (() -> Unit) {
            dialogs.add(onAnswer)
            return { dismissed++ }
        }
    }

    private val audio = PermissionRequest.RESOURCE_AUDIO_CAPTURE
    private val video = PermissionRequest.RESOURCE_VIDEO_CAPTURE
    private lateinit var host: FakeHost
    private lateinit var permissions: WebViewPagePermissions

    @Before
    fun setUp() {
        WebViewPagePermissions.clearGrantsForTests()
        host = FakeHost()
        permissions = WebViewPagePermissions(host)
    }

    @After
    fun tearDown() = WebViewPagePermissions.clearGrantsForTests()

    @Test
    fun allowedResourceIsNotAskedAgainForTheOrigin() {
        val first = FakeRequest("https://x.com/", audio)
        permissions.onRequest(first)
        host.dialogs.single()(true)
        assertEquals("grant", first.answer)

        // another request of the page, and one of a new view of the same process (a reload)
        val second = FakeRequest("https://x.com/", audio)
        val third = FakeRequest("https://x.com/", audio)
        permissions.onRequest(second)
        WebViewPagePermissions(host).onRequest(third)
        assertEquals("grant", second.answer)
        assertEquals("grant", third.answer)
        assertEquals(1, host.dialogs.size)
    }

    @Test
    fun otherOriginsAndOtherResourcesAreAsked() {
        permissions.onRequest(FakeRequest("https://x.com/", audio))
        host.dialogs.single()(true)

        val otherOrigin = FakeRequest("https://grok.com/", audio)
        permissions.onRequest(otherOrigin)
        assertNull(otherOrigin.answer)
        host.dialogs[1](false)
        assertEquals("deny", otherOrigin.answer)

        host.androidGranted.add(Manifest.permission.CAMERA)
        val more = FakeRequest("https://x.com/", audio, video)
        permissions.onRequest(more)
        assertNull(more.answer)
        assertEquals(3, host.dialogs.size)
    }

    @Test
    fun deniedIsAskedAgain() {
        permissions.onRequest(FakeRequest("https://x.com/", audio))
        host.dialogs.single()(false)
        permissions.onRequest(FakeRequest("https://x.com/", audio))
        assertEquals(2, host.dialogs.size)
    }

    @Test
    fun requestsWhileADialogIsShownWaitAndAreAnsweredByIt() {
        val first = FakeRequest("https://x.com/", audio)
        val second = FakeRequest("https://x.com/", audio)
        permissions.onRequest(first)
        permissions.onRequest(second)
        assertEquals(1, host.dialogs.size)

        host.dialogs.single()(true)
        assertEquals("grant", first.answer)
        assertEquals("grant", second.answer)
        assertEquals(1, host.dialogs.size)
    }

    @Test
    fun waitingRequestOfAnotherOriginGetsItsOwnDialog() {
        val first = FakeRequest("https://x.com/", audio)
        val second = FakeRequest("https://grok.com/", audio)
        permissions.onRequest(first)
        permissions.onRequest(second)
        host.dialogs[0](false)
        assertEquals("deny", first.answer)
        assertEquals(2, host.dialogs.size)
        host.dialogs[1](true)
        assertEquals("grant", second.answer)
    }

    @Test
    fun cancelledRequestClosesItsDialogAndShowsTheNext() {
        val first = FakeRequest("https://x.com/", audio)
        val second = FakeRequest("https://grok.com/", audio)
        val third = FakeRequest("https://example.com/", audio)
        permissions.onRequest(first)
        permissions.onRequest(second)
        permissions.onRequest(third)

        permissions.onRequestCanceled(second)
        permissions.onRequestCanceled(first)
        assertEquals(1, host.dismissed)
        assertEquals(2, host.dialogs.size)
        host.dialogs[1](true)
        assertEquals("grant", third.answer)
        assertNull(first.answer)
        assertNull(second.answer)
    }

    @Test
    fun missingAndroidPermissionIsRequestedAndItsResultAnswers() {
        host.androidGranted.clear()
        val request = FakeRequest("https://x.com/", audio)
        permissions.onRequest(request)
        host.dialogs.single()(true)
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), host.askedAndroid)
        assertNull(request.answer)

        host.androidGranted.add(Manifest.permission.RECORD_AUDIO)
        permissions.onAndroidPermissionsResult(arrayOf(Manifest.permission.RECORD_AUDIO),
            intArrayOf(PackageManager.PERMISSION_GRANTED))
        assertEquals("grant", request.answer)
        assertEquals(listOf(audio), request.grantedResources)

        permissions.onRequest(FakeRequest("https://x.com/", audio))
        assertEquals(1, host.dialogs.size)
    }

    @Test
    fun refusedAndroidPermissionDeniesAndIsNotRemembered() {
        host.androidGranted.clear()
        val request = FakeRequest("https://x.com/", audio)
        permissions.onRequest(request)
        host.dialogs.single()(true)
        permissions.onAndroidPermissionsResult(arrayOf(Manifest.permission.RECORD_AUDIO),
            intArrayOf(PackageManager.PERMISSION_DENIED))
        assertEquals("deny", request.answer)

        permissions.onRequest(FakeRequest("https://x.com/", audio))
        assertEquals(2, host.dialogs.size)
    }

    @Test
    fun rememberedResourceIsAskedAgainWhenTheAndroidPermissionWasRevoked() {
        permissions.onRequest(FakeRequest("https://x.com/", audio))
        host.dialogs.single()(true)
        host.androidGranted.clear()
        val again = FakeRequest("https://x.com/", audio)
        permissions.onRequest(again)
        assertNull(again.answer)
        assertEquals(2, host.dialogs.size)
    }
}
