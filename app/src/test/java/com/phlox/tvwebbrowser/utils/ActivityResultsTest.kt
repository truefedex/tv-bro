package com.phlox.tvwebbrowser.utils

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ActivityResultsTest {
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    @Test
    fun activityResultIsDeliveredOnce() {
        val results = ArrayList<ActivityResult>()
        val intent = Intent("com.example.PICK")
        activity.launchForResult(ActivityResultContracts.StartActivityForResult(), intent) { results += it }

        val started = shadowOf(activity).nextStartedActivityForResult
        assertEquals("com.example.PICK", started.intent.action)
        val data = Intent().putExtra("picked", "file")
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_OK, data)
        assertEquals(1, results.size)
        assertEquals(Activity.RESULT_OK, results[0].resultCode)
        assertEquals("file", results[0].data?.getStringExtra("picked"))

        //the launcher is unregistered after the result
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
        assertEquals(1, results.size)
    }

    @Test
    fun cancelledActivityResultIsDelivered() {
        var result: ActivityResult? = null
        activity.launchForResult(ActivityResultContracts.StartActivityForResult(), Intent("com.example.PICK")) { result = it }
        val started = shadowOf(activity).nextStartedActivityForResult
        shadowOf(activity).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
        assertEquals(Activity.RESULT_CANCELED, result?.resultCode)
    }

    @Test
    fun grantedPermissionsAreReportedWithoutAsking() {
        shadowOf(activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        var result: Map<String, Boolean>? = null
        activity.launchForResult(ActivityResultContracts.RequestMultiplePermissions(),
            arrayOf(Manifest.permission.RECORD_AUDIO)) { result = it }
        //a result known without asking is posted to the main thread
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(mapOf(Manifest.permission.RECORD_AUDIO to true), result)
    }

    @Test
    fun independentRequestsGetTheirOwnResults() {
        val results = ArrayList<String>()
        activity.launchForResult(ActivityResultContracts.StartActivityForResult(), Intent("a")) { results += "a ${it.resultCode}" }
        activity.launchForResult(ActivityResultContracts.StartActivityForResult(), Intent("b")) { results += "b ${it.resultCode}" }
        val started = listOf(shadowOf(activity).nextStartedActivityForResult, shadowOf(activity).nextStartedActivityForResult)
        val a = started.first { it.intent.action == "a" }
        val b = started.first { it.intent.action == "b" }
        shadowOf(activity).receiveResult(b.intent, 2, null)
        shadowOf(activity).receiveResult(a.intent, 1, null)
        assertEquals(listOf("b 2", "a 1"), results)
        assertTrue(shadowOf(activity).nextStartedActivityForResult == null)
    }
}
