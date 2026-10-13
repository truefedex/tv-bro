package com.phlox.tvwebbrowser.webengine

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.ktx.bytesDownloaded
import com.google.android.play.core.ktx.errorCode
import com.google.android.play.core.ktx.hasTerminalStatus
import com.google.android.play.core.ktx.moduleNames
import com.google.android.play.core.ktx.requestInstall
import com.google.android.play.core.ktx.requestSessionStates
import com.google.android.play.core.ktx.sessionId
import com.google.android.play.core.ktx.status
import com.google.android.play.core.ktx.totalBytesToDownload
import com.google.android.play.core.splitinstall.SplitInstallException
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallSessionState
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallErrorCode
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel

/**
 * Downloads the gecko dynamic feature module from Google Play, showing [WebEngineInstallView]
 * over the activity while it works, and then restarts the app.
 */
class GeckoFeatureInstaller : WebEngineInstaller {
    companion object {
        private const val TAG = "GeckoFeatureInstaller"
    }

    override val name = Config.ENGINE_GECKO_VIEW

    override suspend fun install(context: Context): Boolean {
        val activity = context as ComponentActivity
        val manager = SplitInstallManagerFactory.create(activity)
        if (manager.installedModules.contains(FeatureDelivery.GECKO_MODULE_NAME)) {
            //then its classes would be loaded on app start
            Log.e(TAG, "Gecko module is installed, but its classes are not available")
            return false
        }
        val installView = WebEngineInstallView(activity, name)
        while (true) {
            val error = try {
                downloadAndInstall(manager, activity, installView)
            } catch (e: CancellationException) {
                installView.dismiss()
                throw e
            }
            if (error == null) break
            if (!installView.askRetry(error)) {
                installView.dismiss()
                return false
            }
        }
        //SplitCompat could load the module into this process, but Gecko native libraries would
        //fail to load: the linker namespace of the process does not see the new split. After
        //a restart the module is a regular part of the installed app.
        installView.showRestarting()
        TVBro.instance.needToExitProcessAfterMainActivityFinish = true
        TVBro.instance.needRestartMainActivityAfterExitingProcess = true
        activity.finish()
        awaitCancellation()
    }

    /**
     * @return null on success, otherwise error message
     */
    private suspend fun downloadAndInstall(manager: SplitInstallManager, activity: ComponentActivity,
                                           installView: WebEngineInstallView): String? {
        installView.showPending()

        val states = Channel<SplitInstallSessionState>(Channel.UNLIMITED)
        //registered before the request, so no state update can be missed
        val listener = SplitInstallStateUpdatedListener { states.trySend(it) }
        var sessionId = 0
        val confirmationLauncher = activity.activityResultRegistry.register(
            "gecko_feature_install_confirmation",
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                //Play then reports the session as canceled
                manager.cancelInstall(sessionId)
            }
        }
        manager.registerListener(listener)
        try {
            //the download may be still running if the app was closed during it
            val activeSession = manager.requestSessionStates()
                .find { it.moduleNames.contains(FeatureDelivery.GECKO_MODULE_NAME) && !it.hasTerminalStatus }
            if (activeSession != null) {
                sessionId = activeSession.sessionId
                states.trySend(activeSession)
            } else {
                sessionId = manager.requestInstall(listOf(FeatureDelivery.GECKO_MODULE_NAME))
                if (sessionId == 0) return null //already installed
            }

            for (state in states) {
                if (state.sessionId != sessionId) continue
                Log.d(TAG, "Install session state: ${state.status}")
                when (state.status) {
                    SplitInstallSessionStatus.PENDING -> installView.showPending()
                    SplitInstallSessionStatus.DOWNLOADING ->
                        installView.showProgress(state.bytesDownloaded, state.totalBytesToDownload)
                    SplitInstallSessionStatus.REQUIRES_USER_CONFIRMATION ->
                        manager.startConfirmationDialogForResult(state, confirmationLauncher)
                    SplitInstallSessionStatus.DOWNLOADED,
                    SplitInstallSessionStatus.INSTALLING -> installView.showInstalling()
                    SplitInstallSessionStatus.INSTALLED -> return null
                    SplitInstallSessionStatus.FAILED -> return errorMessage(activity, state.errorCode)
                    SplitInstallSessionStatus.CANCELED ->
                        return activity.getString(R.string.web_engine_install_canceled)
                }
            }
            return errorMessage(activity, SplitInstallErrorCode.INTERNAL_ERROR)
        } catch (e: SplitInstallException) {
            Log.e(TAG, "Failed to request module install", e)
            return errorMessage(activity, e.errorCode)
        } finally {
            manager.unregisterListener(listener)
            confirmationLauncher.unregister()
        }
    }

    private fun errorMessage(context: Context, errorCode: Int): String {
        val reason = when (errorCode) {
            SplitInstallErrorCode.NETWORK_ERROR -> context.getString(R.string.web_engine_install_error_network)
            SplitInstallErrorCode.INSUFFICIENT_STORAGE -> context.getString(R.string.web_engine_install_error_storage)
            SplitInstallErrorCode.API_NOT_AVAILABLE,
            SplitInstallErrorCode.PLAY_STORE_NOT_FOUND,
            SplitInstallErrorCode.APP_NOT_OWNED -> context.getString(R.string.web_engine_install_error_no_play)
            else -> context.getString(R.string.web_engine_install_error_code, errorCode)
        }
        return context.getString(R.string.web_engine_install_failed) + "\n" + reason
    }
}
