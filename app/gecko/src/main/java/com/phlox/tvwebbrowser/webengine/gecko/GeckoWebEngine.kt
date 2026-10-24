package com.phlox.tvwebbrowser.webengine.gecko

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import com.phlox.tvwebbrowser.AppContext
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.model.BrowserTab
import com.phlox.tvwebbrowser.net.AppProxy
import com.phlox.tvwebbrowser.net.ProxySettings
import com.phlox.tvwebbrowser.utils.observable.ObservableValue
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.WebEngineProvider
import com.phlox.tvwebbrowser.webengine.WebEngineProviderCallback
import com.phlox.tvwebbrowser.webengine.EngineSpecificSetting
import com.phlox.tvwebbrowser.webengine.WebEngineCallbacks
import com.phlox.tvwebbrowser.webengine.VirtualCursorSupport
import com.phlox.tvwebbrowser.webengine.SiteViewSupport
import com.phlox.tvwebbrowser.webengine.CursorModes
import com.phlox.tvwebbrowser.utils.HardwareCodecs
import com.phlox.tvwebbrowser.webengine.MediaPlaybackSupport
import com.phlox.tvwebbrowser.webengine.MediaCommand
import com.phlox.tvwebbrowser.webengine.MediaFrames
import com.phlox.tvwebbrowser.webengine.MediaState
import com.phlox.tvwebbrowser.webengine.PageVideoMatch
import com.phlox.tvwebbrowser.webengine.MediaSource
import com.phlox.tvwebbrowser.webengine.PageVideo
import com.phlox.tvwebbrowser.webengine.PageVideoList
import com.phlox.tvwebbrowser.webengine.PageVideosSupport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.phlox.tvwebbrowser.webengine.gecko.delegates.AppContentScriptPortDelegate
import com.phlox.tvwebbrowser.webengine.gecko.delegates.AppHomePagePortDelegate
import com.phlox.tvwebbrowser.webengine.gecko.delegates.AppWebExtensionBackgroundPortDelegate
import com.phlox.tvwebbrowser.webengine.gecko.delegates.MyContentDelegate
import com.phlox.tvwebbrowser.webengine.gecko.delegates.MyHistoryDelegate
import com.phlox.tvwebbrowser.webengine.gecko.delegates.MyMediaSessionDelegate
import com.phlox.tvwebbrowser.webengine.gecko.delegates.MyNavigationDelegate
import com.phlox.tvwebbrowser.webengine.gecko.delegates.MyPermissionDelegate
import com.phlox.tvwebbrowser.webengine.gecko.delegates.MyProgressDelegate
import com.phlox.tvwebbrowser.webengine.gecko.delegates.MyPromptDelegate
import com.phlox.tvwebbrowser.webengine.gecko.delegates.MySelectionActionDelegate
import com.phlox.tvwebbrowser.webengine.gecko.delegates.MyTextInputDelegate
import com.phlox.tvwebbrowser.widgets.cursor.CursorDrawerDelegate
import com.phlox.tvwebbrowser.widgets.cursor.PointerMode
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import kotlin.math.roundToInt
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.SessionState
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtension.MessageDelegate
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class GeckoWebEngine(val tab: BrowserTab): WebEngine, VirtualCursorSupport, SiteViewSupport,
    MediaPlaybackSupport, PageVideosSupport, CursorDrawerDelegate.Callback {
    companion object {
        const val ENGINE_NAME = "GeckoView"
        /** Changes with the files of the extension: it is installed again then */
        private const val APP_WEB_EXTENSION_VERSION = BuildConfig.WEB_EXTENSION_HASH
        /** The home page is a page of the extension: its files are copied there at build time */
        private const val HOME_PAGE_PATH = "home/index.html"
        private val LOCAL_HOME_PAGE_REGEX = Regex("^moz-extension://[^/]+/home/index\\.html([?#].*)?$")
        val TAG: String = GeckoWebEngine::class.java.simpleName
        lateinit var runtime: GeckoRuntime
        var appWebExtension = ObservableValue<WebExtension?>(null)
        var weakRefToSingleGeckoView: WeakReference<GeckoViewWithVirtualCursor?> = WeakReference(null)
        val uiHandler = Handler(Looper.getMainLooper())

        @UiThread
        fun initialize(context: Context, webViewContainer: ViewGroup) {
            if (!this::runtime.isInitialized) {
                val builder = GeckoRuntimeSettings.Builder()
                if (AppContext.provideConfig().webEngineDebug) {
                    builder.remoteDebuggingEnabled(true)
                    builder.consoleOutput(true)
                }
                builder.aboutConfigEnabled(true)
                    .preferredColorScheme(AppContext.provideConfig().theme.value.toGeckoPreferredColorScheme())
                    .forceUserScalableEnabled(true)
                builder.contentBlocking(
                        ContentBlocking.Settings.Builder()
                            .antiTracking(
                                ContentBlocking.AntiTracking.DEFAULT or ContentBlocking.AntiTracking.STP)
                    .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
                    .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
                    .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
                    .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
                    .build())
                runtime = GeckoRuntime.create(context, builder.build())
                disableSoftwareAv1()

                val webExtInstallResult = if (APP_WEB_EXTENSION_VERSION == AppContext.provideConfig().appWebExtensionVersion) {
                    Log.d(TAG, "appWebExtension already installed")
                    runtime.webExtensionController.ensureBuiltIn(
                        "resource://android/assets/extensions/generic/",
                        "tvbro@mock.com"
                    )
                } else {
                    Log.d(TAG, "installing appWebExtension")
                    runtime.webExtensionController.installBuiltIn("resource://android/assets/extensions/generic/")
                }

                webExtInstallResult.accept({ extension ->
                    Log.d(TAG, "extension accepted: ${extension?.metaData?.description}")
                    extension?.let(::connectBackgroundScript)
                    appWebExtension.value = extension
                    AppContext.provideConfig().appWebExtensionVersion = APP_WEB_EXTENSION_VERSION
                }
                ) { e -> Log.e(TAG, "Error registering WebExtension", e) }
            }

            val webView = GeckoViewWithVirtualCursor(context)
            webViewContainer.addView(webView)
            weakRefToSingleGeckoView = WeakReference(webView)
        }

        /**
         * The port of the background script, which asks about the requests of all tabs: one delegate for the
         * extension, not for an engine (the script, not persistent, may connect again at any time)
         */
        private fun connectBackgroundScript(extension: WebExtension) {
            extension.setMessageDelegate(object : MessageDelegate {
                override fun onMessage(nativeApp: String, message: Any,
                                       sender: WebExtension.MessageSender): GeckoResult<Any>? = null

                override fun onConnect(port: WebExtension.Port) {
                    Log.d(TAG, "background script connected")
                    port.setDelegate(AppWebExtensionBackgroundPortDelegate(port))
                }
            }, "tvbro_bg")
        }

        /**
         * Without a hardware AV1 decoder GeckoView decodes AV1 in software (dav1d), which TV chips can not do
         * smoothly, and sites still choose it (YouTube serves AV1 to Firefox): pages are told there is no AV1,
         * so they choose VP9 or H.264, which the hardware decodes. It is the default value of the preference,
         * not kept: each start checks the device again, and about:config can still turn AV1 on.
         */
        private fun disableSoftwareAv1() {
            if (HardwareCodecs.hasHardwareDecoder(HardwareCodecs.AV1)) return
            Log.i(TAG, "No hardware AV1 decoder: AV1 is off")
            GeckoPreferenceController.setGeckoPref("media.av1.enabled", false,
                GeckoPreferenceController.PREF_BRANCH_DEFAULT).exceptionally<Void> { e ->
                Log.e(TAG, "Can not turn AV1 off", e)
                null
            }
        }

        /** Where GeckoView finds the home page; null until the extension is registered */
        val localHomePageUrl: String?
            get() = appWebExtension.value?.metaData?.baseUrl?.let { it + HOME_PAGE_PATH }

        fun isLocalHomePage(url: String): Boolean = LOCAL_HOME_PAGE_REGEX.matches(url)

        /** The url as the app knows it: the local home page is [Config.HOME_PAGE_URL] */
        fun toAppUrl(url: String): String = if (isLocalHomePage(url)) Config.HOME_PAGE_URL else url

        fun onThemeSettingUpdated(theme: Config.Theme) {
            runtime.settings.preferredColorScheme = theme.toGeckoPreferredColorScheme()
        }

        init {
            WebEngineFactory.registerProvider(WebEngineProvider(ENGINE_NAME, object : WebEngineProviderCallback {
                override suspend fun initialize(context: Context, webViewContainer: ViewGroup) {
                    GeckoWebEngine.initialize(context, webViewContainer)
                    //before the sessions of the tabs load a page
                    AppProxy.settings?.let { GeckoProxyPrefs.apply(it) }
                }

                override suspend fun applyProxy(proxy: ProxySettings?) {
                    GeckoProxyPrefs.apply(proxy)
                }

                override fun createWebEngine(tab: BrowserTab): WebEngine {
                    return GeckoWebEngine(tab)
                }

                override fun supportsSetting(setting: EngineSpecificSetting): Boolean =
                    setting == EngineSpecificSetting.TRACKING_PROTECTION || setting == EngineSpecificSetting.PROXY

                override suspend fun clearCache(ctx: Context) {
                    suspendCoroutine { cont ->
                        runtime.storageController.clearData(StorageController.ClearFlags.ALL_CACHES).then({
                            cont.resume(null)
                            GeckoResult.fromValue(null)
                        }, {
                            it.printStackTrace()
                            cont.resumeWithException(it)
                            GeckoResult.fromValue(null)
                        })
                    }
                }

                override fun supportsSiteDataClearing(): Boolean = true

                override suspend fun clearSiteData(context: Context, host: String) {
                    //not the permissions (SITE_DATA has them) and not the history
                    val flags = StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.DOM_STORAGES or
                        StorageController.ClearFlags.AUTH_SESSIONS or StorageController.ClearFlags.ALL_CACHES
                    suspendCoroutine { cont ->
                        runtime.storageController.clearDataFromHost(host, flags).then({
                            cont.resume(Unit)
                            GeckoResult.fromValue(null)
                        }, {
                            cont.resumeWithException(it)
                            GeckoResult.fromValue(null)
                        })
                    }
                }

                override fun onThemeSettingUpdated(value: Config.Theme) {
                    GeckoWebEngine.onThemeSettingUpdated(value)
                }

                override fun getWebEngineVersionString(): String {
                    return org.mozilla.geckoview.BuildConfig.LIBRARY_PACKAGE_NAME + ":" +
                            org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION + "." +
                            org.mozilla.geckoview.BuildConfig.MOZ_APP_BUILDID + " - " +
                            org.mozilla.geckoview.BuildConfig.MOZ_UPDATE_CHANNEL
                }
            }))
        }
    }

    private var webView: GeckoViewWithVirtualCursor? = null
    private val _cursorModes = MutableStateFlow(CursorModes())
    override val cursorModes: StateFlow<CursorModes> = _cursorModes.asStateFlow()
    lateinit var session: GeckoSession
    var callback: WebEngineCallbacks? = null
    val navigationDelegate = MyNavigationDelegate(this)
    val progressDelegate = MyProgressDelegate(this)
    val promptDelegate = MyPromptDelegate(this)
    val contentDelegate = MyContentDelegate(this)
    val permissionDelegate = MyPermissionDelegate(this)
    val historyDelegate = MyHistoryDelegate(this)
    private val _mediaState = MutableStateFlow<MediaState?>(null)
    override val mediaState: StateFlow<MediaState?> = _mediaState.asStateFlow()
    /**
     * The media of the frames, from their content scripts (media_controls.js, one port each): the media keys
     * and the video mode go to the frame [MediaFrames] chooses, as in WebView. The media session of GeckoView
     * is used when no frame reports media; it has no muted media, and only the fullscreen of the element.
     */
    private val mediaFrames = MediaFrames<WebExtension.Port>()
    private var sessionState: MediaState? = null
    val mediaSessionDelegate = MyMediaSessionDelegate {
        sessionState = it
        updateMediaState()
    }
    /** The videos of the page, from the content scripts of its frames (one port each) */
    private val videos = PageVideoList<WebExtension.Port>()
    private val _pageVideos = MutableStateFlow<List<PageVideo>>(emptyList())
    override val pageVideos: StateFlow<List<PageVideo>> = _pageVideos.asStateFlow()
    private val _mediaVideo = MutableStateFlow<PageVideo?>(null)
    override val mediaVideo: StateFlow<PageVideo?> = _mediaVideo.asStateFlow()
    /** What the media of each frame plays, for [mediaVideo] */
    private val mediaSources = HashMap<WebExtension.Port, MediaSource>()

    /** The content script of a frame reported its videos (none: the frame is gone) */
    fun onFrameVideos(port: WebExtension.Port, frameVideos: List<PageVideo>) {
        _pageVideos.value = videos.onFrameVideos(port, frameVideos)
        updateMediaVideo()
    }

    /** The content script of a frame reported the media its commands go to (null: none, or the frame is gone) */
    fun onFrameMedia(port: WebExtension.Port, media: MediaState?, source: MediaSource?) {
        mediaFrames.onState(port, media)
        if (source == null) mediaSources.remove(port) else mediaSources[port] = source
        updateMediaState()
        updateMediaVideo()
    }

    /** The video of the page the media the keys control plays ([PageVideoMatch]) */
    private fun updateMediaVideo() {
        val port = mediaFrames.target()
        _mediaVideo.value = port?.let { PageVideoMatch.of(mediaSources[it], videos.videosOf(it)) }
    }

    private fun updateMediaState() {
        _mediaState.value = mediaFrames.targetMedia() ?: sessionState
    }

    /** Sends the command to the content script of the frame with the media; false if no frame has media */
    private fun sendToFrame(command: String): Boolean {
        val port = mediaFrames.target() ?: return false
        port.postMessage(JSONObject().put("command", command))
        return true
    }

    /** The page starts loading another document: the media and the videos of the old one are gone */
    fun onPageStartedForFrames() {
        videos.clear()
        _pageVideos.value = emptyList()
        mediaFrames.clear()
        mediaSources.clear()
        updateMediaState()
        updateMediaVideo()
    }
    val selectionActionDelegate = MySelectionActionDelegate()
    /** The last scroll position of the page in pixels, to report how far it scrolled; unknown when -1 */
    private var lastScrollY = -1
    /** GeckoView reports the offset of the visual viewport in CSS pixels */
    /** The default text input of GeckoView, plus the soft keyboard for fields the cursor's mouse clicks */
    private val textInputDelegate by lazy { MyTextInputDelegate(session.textInput.delegate) }
    private val scrollDelegate = object : GeckoSession.ScrollDelegate {
        override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
            val y = (scrollY * AppContext.get().resources.displayMetrics.density).roundToInt()
            val dy = if (lastScrollY == -1) 0 else y - lastScrollY
            lastScrollY = y
            callback?.onPageScrolled(y, dy)
        }
    }
    var appHomePagePortDelegate: AppHomePagePortDelegate? = null
    var appContentScriptPortDelegate: AppContentScriptPortDelegate? = null
    private lateinit var webExtObserver: (WebExtension?) -> Unit

    override val url: String?
        get() = navigationDelegate.locationURL
    override val stateFilePrefix: String
        get() = "gecko:"
    /** GeckoView gives its User-Agent only asynchronously */
    override val userAgentString: String? = null

    /** The desktop User-Agent and viewport of GeckoView; a change takes effect with the next load */
    override var desktopView: Boolean
        get() = session.settings.userAgentMode == GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        set(value) {
            if (value == desktopView) return
            session.settings.userAgentMode = if (value) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else
                GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            session.settings.viewportMode = if (value) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else
                GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            //the view is shared by all tabs: only the attached one sets its cursor
            if (callback != null) applyPointerMode()
        }

    /** The cursor is a mouse for pages in the desktop view, a finger for the others */
    private fun applyPointerMode() {
        webView?.cursorDrawerDelegate?.pointerMode = if (desktopView) PointerMode.MOUSE else PointerMode.TOUCH
    }

    init {
        Log.d(TAG, "init")
        session = GeckoSession(GeckoSessionSettings.Builder()
            .usePrivateMode(AppContext.provideConfig().incognitoMode)
            //the browser core gives the view of the page's host before it loads
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .useTrackingProtection(AppContext.provideConfig().trackingProtection)
            .build()
        )
        session.navigationDelegate = navigationDelegate
        session.progressDelegate = progressDelegate
        session.contentDelegate = contentDelegate
        session.promptDelegate = promptDelegate
        session.permissionDelegate = permissionDelegate
        session.historyDelegate = historyDelegate
        session.mediaSessionDelegate = mediaSessionDelegate
        session.selectionActionDelegate = selectionActionDelegate
        session.scrollDelegate = scrollDelegate
        session.textInput.setDelegate(textInputDelegate)

        webExtObserver = { ext: WebExtension? ->
            if (ext != null) {
                uiHandler.post {
                    connectToAppWebExtension(ext)
                }
                appWebExtension.unsubscribe(webExtObserver)
            }
        }
        appWebExtension.subscribe(webExtObserver)
    }

    private fun connectToAppWebExtension(extension: WebExtension) {
        Log.d(TAG, "connectToAppWebExtension")
        session.webExtensionController.setMessageDelegate(extension,
            object : MessageDelegate {
                override fun onMessage(nativeApp: String, message: Any,
                                       sender: WebExtension.MessageSender): GeckoResult<Any>? {
                    Log.d(TAG, "onMessage: $nativeApp, $message, $sender")
                    return null
                }

                override fun onConnect(port: WebExtension.Port) {
                    Log.d(TAG, "onConnect: $port")
                    appContentScriptPortDelegate = AppContentScriptPortDelegate(port, this@GeckoWebEngine).also {
                        port.setDelegate(it)
                    }
                }
            }, "tvbro_content"
        )
        session.webExtensionController.setMessageDelegate(extension,
            object : MessageDelegate {
                override fun onMessage(nativeApp: String, message: Any,
                                       sender: WebExtension.MessageSender): GeckoResult<Any>? {
                    Log.d(TAG, "onMessage: $nativeApp, $message, $sender")
                    return null
                }

                override fun onConnect(port: WebExtension.Port) {
                    Log.d(TAG, "onConnect: $port")
                    appHomePagePortDelegate = AppHomePagePortDelegate(port, this@GeckoWebEngine).also {
                        port.setDelegate(it)
                    }
                }
            }, "tvbro")
    }

    override fun getWebEngineName(): String = ENGINE_NAME

    override fun saveState(): Any? {
        Log.d(TAG, "saveState")
        progressDelegate.sessionState?.let {
            return it
        }
        return null
    }

    override fun restoreState(savedInstanceState: Any) {
        Log.d(TAG, "restoreState")
        if (savedInstanceState is SessionState) {
            progressDelegate.sessionState = savedInstanceState
            if (!session.isOpen) {
                session.open(runtime)
            }
            session.restoreState(savedInstanceState)
        } else {
            throw IllegalArgumentException("savedInstanceState is not SessionState")
        }
    }

    override fun stateFromBytes(bytes: ByteArray): Any? {
        val jsString = String(bytes, Charsets.UTF_8)
        return SessionState.fromString(jsString)
    }

    override fun loadUrl(url: String) {
        Log.d(TAG, "loadUrl($url)")
        applyTrackingProtection()
        if (!session.isOpen) {
            session.open(runtime)
        }
        if (url.startsWith(Config.HOME_PAGE_URL)) {
            val homePage = localHomePageUrl
            if (homePage == null) {
                loadWhenExtensionIsReady(url)
            } else {
                session.loadUri(homePage)
            }
            return
        }
        session.loadUri(url)
    }

    /** Waits for the extension (the first tab may want the home page before it is registered) */
    private var extensionWaiter: ((WebExtension?) -> Unit)? = null

    private fun loadWhenExtensionIsReady(url: String) {
        extensionWaiter?.let { appWebExtension.unsubscribe(it) }
        val waiter: (WebExtension?) -> Unit = { extension ->
            if (extension != null) {
                extensionWaiter?.let { appWebExtension.unsubscribe(it) }
                extensionWaiter = null
                loadUrl(url)
            }
        }
        extensionWaiter = waiter
        appWebExtension.subscribe(waiter, notifyOnSubscribe = false)
    }

    /**
     * Shows the local home page instead of the web version of it, which a link, an old bookmark or the saved
     * state of a tab may still load ([replace]: in place of the web version in the history)
     */
    fun loadLocalHomePage(replace: Boolean): Boolean {
        val homePage = localHomePageUrl ?: return false
        val loader = GeckoSession.Loader().uri(homePage)
        if (replace) {
            loader.flags(GeckoSession.LOAD_FLAGS_REPLACE_HISTORY)
        }
        session.load(loader)
        return true
    }

    override fun canGoForward(): Boolean {
        return navigationDelegate.canGoForward
    }

    override fun goForward() {
        session.goForward()
    }

    override fun canZoomIn(): Boolean {
        return true
    }

    override fun zoomIn() {
        //appWebExtensionPortDelegate?.port?.postMessage(JSONObject("{\"action\":\"zoomIn\"}"))
        webView?.cursorDrawerDelegate?.tryZoomIn()
    }

    override fun canZoomOut(): Boolean {
        return true
    }

    override fun zoomOut() {
        //appWebExtensionPortDelegate?.port?.postMessage(JSONObject("{\"action\":\"zoomOut\"}"))
        webView?.cursorDrawerDelegate?.tryZoomOut()
    }

    override fun zoomBy(zoomBy: Float) {
        
    }

    override fun evaluateJavascript(script: String) {
        session.loadUri("javascript:$script")
    }

    override fun setNetworkAvailable(connected: Boolean) {
        //nop
    }

    override fun getView(): View? {
        return webView
    }

    override fun getOrCreateView(activityContext: Context): View {
        Log.d(TAG, "getOrCreateView()")
        if (webView == null) {
            val geckoView = weakRefToSingleGeckoView.get()
            webView = if (geckoView == null) {
                Log.i(TAG, "Creating new GeckoView")
                val wv = GeckoViewWithVirtualCursor(activityContext)
                weakRefToSingleGeckoView = WeakReference(wv)
                wv
            } else {
                geckoView
            }.also { wv ->
                wv.cursorDrawerDelegate.callback = this
            }
        }
        return webView!!
    }

    override fun canGoBack(): Boolean {
        return navigationDelegate.canGoBack
    }

    override fun goBack() {
        session.goBack()
    }

    override fun stopLoading() {
        session.stop()
    }

    override fun reload() {
        applyTrackingProtection()
        session.reload()
    }

    override fun onResume() {
        if (!session.isOpen) {
            session.open(runtime)
            progressDelegate.sessionState?.let {
                session.restoreState(it)
            }
        }
        session.setFocused(true)
    }

    override fun onPause() {
        session.setFocused(false)
        mediaSessionDelegate.mediaSession?.let {
            if (!mediaSessionDelegate.paused) {
                it.pause()
            }
        }
    }

    /** The setting may have changed since the session was made; it applies from the next load */
    private fun applyTrackingProtection() {
        val enabled = AppContext.provideConfig().trackingProtection
        if (session.settings.useTrackingProtection != enabled) session.settings.useTrackingProtection = enabled
    }

    override fun hideFullscreenView() {
        session.exitFullScreen()
    }

    override fun togglePlayback() {
        if (sendToFrame(MediaCommand.TOGGLE.js)) return
        mediaSessionDelegate.mediaSession?.let {
            if (mediaSessionDelegate.paused) {
                it.play()
            } else {
                it.pause()
            }
        }
    }

    override fun pausePlayback() {
        if (sendToFrame(MediaCommand.PAUSE.js)) return
        mediaSessionDelegate.mediaSession?.pause()
    }

    override fun stopPlayback() {
        if (sendToFrame(MediaCommand.STOP.js)) return
        mediaSessionDelegate.mediaSession?.stop()
    }

    override fun rewind() {
        if (sendToFrame(MediaCommand.REWIND.js)) return
        mediaSessionDelegate.mediaSession?.seekBackward()
    }

    override fun fastForward() {
        if (sendToFrame(MediaCommand.FAST_FORWARD.js)) return
        mediaSessionDelegate.mediaSession?.seekForward()
    }

    override fun seekTo(position: Double) {
        if (sendToFrame(MediaCommand.seekTo(position))) return
        mediaSessionDelegate.mediaSession?.seekTo(position.coerceAtLeast(0.0), false)
    }

    /** In the media session, from its position; pages that give none get the seek actions of their own */
    override fun seekBy(delta: Double) {
        if (sendToFrame(MediaCommand.seekBy(delta))) return
        val session = mediaSessionDelegate.mediaSession ?: return
        val position = mediaSessionDelegate.currentPosition
        when {
            position != null && sessionState?.duration != null -> session.seekTo((position + delta).coerceAtLeast(0.0), false)
            delta < 0 -> session.seekBackward()
            else -> session.seekForward()
        }
    }

    override suspend fun renderThumbnail(bitmap: Bitmap?): Bitmap? {
        return webView?.renderThumbnail(bitmap)
    }

    override fun onAttachToWindow(
        callback: WebEngineCallbacks,
        parent: ViewGroup
    ) {
        Log.d(TAG, "onAttachToWindow()")
        this.callback = callback
        applyTrackingProtection()
        //the shared view showed another tab's page
        lastScrollY = -1
        val webView = this.webView ?: throw IllegalStateException("WebView is null")
        //the view is shared by all tabs: its cursor reports to the attached one
        webView.cursorDrawerDelegate.callback = this
        webView.onCursorMouseClick = { textInputDelegate.onCursorMouseClick(session) }
        applyPointerMode()
        _cursorModes.value = CursorModes(
            directNavigation = !webView.virtualCursorMode,
            grab = webView.cursorDrawerDelegate.canHandleBackNavigation()
        )
        val previousSession = webView.session
        if (previousSession != null && previousSession != session) {
            Log.d(TAG, "Closing previous session")
            previousSession.setActive(false)
            webView.releaseSession()
        }
        webView.coverUntilFirstPaint(Color.WHITE)
        webView.setSession(session)
        if (session.isOpen && previousSession != null && previousSession != session) {
            Log.d(TAG, "Activating session")
            session.setActive(true)
            session.reload()
        }
    }

    override fun onDetachFromWindow(completely: Boolean, destroyTab: Boolean) {
        Log.d(TAG, "onDetachFromWindow()")
        callback = null
        val webView = this.webView
        if (webView != null) {
            val session = webView.session
            if (session == this.session) {
                Log.d(TAG, "Closing session")
                session.setActive(false)
                webView.releaseSession()
            }
        }
        if (completely) {
            this.webView = null
        }
        if (destroyTab) {
            Log.d(TAG, "Closing session completely")
            mediaSessionDelegate.mediaSession?.stop()
            session.close()
        }
    }

    override fun trimMemory() {
    }

    override fun isSameSession(internalRepresentation: Any): Boolean {
        return internalRepresentation is GeckoSession && internalRepresentation == session
    }

    override fun onLongPress(x: Int, y: Int) {
        // There is a small delay so that if MyContentDelegate.onContextMenu (with more contextual data) is triggered first,
        // this "second" call will be ignored by the CursorMenuView.
        uiHandler.postDelayed(
            {
                callback?.onContextMenu(webView!!.cursorDrawerDelegate, navigationDelegate.locationURL,
                    null, null, null, null, null, x, y)
            }, 100
        )
    }

    override fun isVirtualCursorMode(): Boolean {
        return webView?.virtualCursorMode ?: true
    }

    override fun setVirtualCursorMode(enabled: Boolean) {
        val webView = webView ?: return
        if (enabled) {
            webView.cursorDrawerDelegate.animateAppearing()
        }
        webView.virtualCursorMode = enabled
        _cursorModes.update { it.copy(directNavigation = !enabled) }
    }

    override fun onGrabModeChanged(grabMode: Boolean) {
        _cursorModes.update { it.copy(grab = grabMode) }
    }

    override fun getCursorDrawerDelegate(): CursorDrawerDelegate? {
        return webView?.cursorDrawerDelegate
    }
}