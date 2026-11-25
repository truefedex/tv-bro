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
import com.phlox.tvwebbrowser.net.AppDns
import com.phlox.tvwebbrowser.net.AppProxy
import com.phlox.tvwebbrowser.net.DnsSettings
import com.phlox.tvwebbrowser.net.ProxySettings
import com.phlox.tvwebbrowser.utils.observable.ObservableValue
import com.phlox.tvwebbrowser.webengine.SiteBlock
import com.phlox.tvwebbrowser.webengine.SiteView
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.WebEngineProvider
import com.phlox.tvwebbrowser.webengine.WebEngineProviderCallback
import com.phlox.tvwebbrowser.webengine.EngineSpecificSetting
import com.phlox.tvwebbrowser.webengine.WebEngineCallbacks
import com.phlox.tvwebbrowser.webengine.VirtualCursorSupport
import com.phlox.tvwebbrowser.webengine.PageZoom
import com.phlox.tvwebbrowser.webengine.PageZoomSupport
import com.phlox.tvwebbrowser.webengine.SiteViewSupport
import com.phlox.tvwebbrowser.webengine.SiteDataKind
import com.phlox.tvwebbrowser.webengine.CursorModes
import com.phlox.tvwebbrowser.utils.HardwareCodecs
import com.phlox.tvwebbrowser.webengine.FindInPageSupport
import com.phlox.tvwebbrowser.webengine.FindResult
import com.phlox.tvwebbrowser.webengine.MediaPlaybackSupport
import com.phlox.tvwebbrowser.webengine.MediaCommand
import com.phlox.tvwebbrowser.webengine.MediaFrames
import com.phlox.tvwebbrowser.webengine.MediaState
import com.phlox.tvwebbrowser.webengine.PageVideoMatch
import com.phlox.tvwebbrowser.webengine.HlsPlaylists
import com.phlox.tvwebbrowser.webengine.MediaSource
import com.phlox.tvwebbrowser.webengine.PageVideo
import com.phlox.tvwebbrowser.webengine.PageVideoList
import com.phlox.tvwebbrowser.webengine.PageVideosSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

class GeckoWebEngine(val tab: BrowserTab): WebEngine, VirtualCursorSupport, SiteViewSupport, PageZoomSupport,
    MediaPlaybackSupport, PageVideosSupport, FindInPageSupport, CursorDrawerDelegate.Callback {
    companion object {
        const val ENGINE_NAME = "GeckoView"
        /** The session context (a container) of the pages of the kids profile; never change it: it names their data */
        private const val KIDS_CONTEXT_ID = "kids"
        /** Changes with the files of the extension: it is installed again then */
        private const val APP_WEB_EXTENSION_VERSION = BuildConfig.WEB_EXTENSION_HASH
        /** The home page is a page of the extension: its files are copied there at build time */
        private const val HOME_PAGE_PATH = "home/index.html"
        private val LOCAL_HOME_PAGE_REGEX = Regex("^moz-extension://[^/]+/home/index\\.html([?#].*)?$")
        val TAG: String = GeckoWebEngine::class.java.simpleName
        lateinit var runtime: GeckoRuntime
        private val isRuntimeInitialized: Boolean get() = this::runtime.isInitialized
        var appWebExtension = ObservableValue<WebExtension?>(null)
        var weakRefToSingleGeckoView: WeakReference<GeckoViewWithVirtualCursor?> = WeakReference(null)
        val uiHandler = Handler(Looper.getMainLooper())
        /** The width of the layout of a desktop page in CSS pixels, that of the desktop viewport of GeckoView */
        private const val DESKTOP_WIDTH = 980
        /**
         * The User-Agent of the tablet view: the one of GeckoView (Firefox for Android) with "Tablet" in place of
         * "Mobile", as Firefox on Android tablets sends it
         */
        private val TABLET_USER_AGENT: String by lazy {
            GeckoSession.getDefaultUserAgent().replace("; Mobile;", "; Tablet;")
        }
        private const val ZOOM_REPLY_TIMEOUT_MS = 500L
        /** The time GeckoView takes to settle the zoom after a pinch */
        private const val PINCH_SETTLE_MS = 300L

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
        fun toAppUrl(url: String): String =
            if (isLocalHomePage(url)) Config.HOME_PAGE_URL else GeckoSiteBlocks.blockedUrlOf(url) ?: url

        fun onThemeSettingUpdated(theme: Config.Theme) {
            runtime.settings.preferredColorScheme = theme.toGeckoPreferredColorScheme()
        }

        init {
            WebEngineFactory.registerProvider(WebEngineProvider(ENGINE_NAME, object : WebEngineProviderCallback {
                override suspend fun initialize(context: Context, webViewContainer: ViewGroup) {
                    GeckoWebEngine.initialize(context, webViewContainer)
                    //a browser activity of another profile: the site filter may block pages now, or no more
                    AppWebExtensionBackgroundPortDelegate.current?.sendConfig()
                    //before the sessions of the tabs load a page
                    AppProxy.settings?.let { GeckoProxyPrefs.apply(it) }
                    AppDns.settings?.let { GeckoDns.apply(runtime.settings, it) }
                }

                override suspend fun applyProxy(proxy: ProxySettings?) {
                    GeckoProxyPrefs.apply(proxy)
                }

                override suspend fun applyDns(dns: DnsSettings?) {
                    GeckoDns.apply(runtime.settings, dns)
                }

                override fun createWebEngine(tab: BrowserTab): WebEngine {
                    return GeckoWebEngine(tab)
                }

                override fun supportsSetting(setting: EngineSpecificSetting): Boolean =
                    setting == EngineSpecificSetting.TRACKING_PROTECTION || setting == EngineSpecificSetting.PROXY ||
                        setting == EngineSpecificSetting.DNS

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

                override fun supportsKidsProfile(): Boolean = true

                //private sessions; their data go when the last of them is closed
                override fun supportsIncognito(): Boolean = true

                //the settings, where it is asked, show only after the runtime started
                override suspend fun deleteKidsData(context: Context) {
                    if (isRuntimeInitialized) runtime.storageController.clearDataForSessionContext(KIDS_CONTEXT_ID)
                }

                override suspend fun clearSiteData(context: Context, host: String, kinds: Set<SiteDataKind>) {
                    //not the permissions (SITE_DATA has them) and not the history
                    var flags = 0L
                    if (SiteDataKind.COOKIES in kinds) {
                        flags = flags or StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.AUTH_SESSIONS
                    }
                    if (SiteDataKind.STORAGE in kinds) flags = flags or StorageController.ClearFlags.DOM_STORAGES
                    if (SiteDataKind.CACHE in kinds) flags = flags or StorageController.ClearFlags.ALL_CACHES
                    if (flags == 0L) return
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
    /**
     * The page that opened this session as a window at no web address (about:blank), until its first web page
     * loads: that address is checked as a popup ([com.phlox.tvwebbrowser.webengine.EngineAdBlocker.shouldBlockPopup])
     */
    var popupOpener: String? = null
    /**
     * The content process of the session died while its tab was in the background: attaching opens it again. Only
     * then: the session of a window the page opened is not open yet when its tab is attached, and GeckoView opens it
     * itself once [MyNavigationDelegate.onNewSession] gives it back (with the window of the page, so `window.opener`
     * works; an opened one fails an assertion)
     */
    private var sessionLost = false
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
    private val videoScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** The playlists of streams the addresses leave unclear (GeckoView gives no cookies) */
    private val playlists: HlsPlaylists = HlsPlaylists(videoScope, { HlsPlaylists.download(it, null) }) {
        _pageVideos.value = videos.videos()
        updateMediaVideo()
    }
    /** The videos of the page, from the content scripts of its frames (one port each) */
    private val videos: PageVideoList<WebExtension.Port> = PageVideoList(playlists = playlists::get)
    private val _pageVideos = MutableStateFlow<List<PageVideo>>(emptyList())
    override val pageVideos: StateFlow<List<PageVideo>> = _pageVideos.asStateFlow()
    private val _mediaVideo = MutableStateFlow(PageVideoMatch.NONE)
    override val mediaVideo: StateFlow<PageVideoMatch> = _mediaVideo.asStateFlow()
    private val _findResult = MutableStateFlow<FindResult?>(null)
    override val findResult: StateFlow<FindResult?> = _findResult.asStateFlow()
    /** The text of the search in the session, null while there is none */
    private var findQuery: String? = null
    /** Counts the searches: the result of an older one (it comes later) is dropped */
    private var findGeneration = 0
    /** What the media of each frame plays, for [mediaVideo] */
    private val mediaSources = HashMap<WebExtension.Port, MediaSource>()

    /** The content script of a frame reported its videos (none: the frame is gone) */
    fun onFrameVideos(port: WebExtension.Port, frameVideos: List<PageVideo>) {
        _pageVideos.value = videos.onFrameVideos(port, frameVideos)
        playlists.read(videos.unclearStreams())
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
        _mediaVideo.value = port?.let {
            PageVideoMatch.of(mediaSources[it], videos.videosOf(it), mediaFrames.targetMedia()?.duration, playlists::get)
        } ?: PageVideoMatch.NONE
    }

    private fun updateMediaState() {
        _mediaState.value = mediaFrames.targetMedia() ?: sessionState
    }

    /** The port of the content script of the top frame (the page), which gets the zoom of the page */
    private var topFramePort: WebExtension.Port? = null
    /** The questions of the zoom of the page waiting for the top frame (zoomed in, the zoom), by their ids */
    private val zoomCallbacks = HashMap<Int, (Boolean, Double?) -> Unit>()
    private var zoomRequests = 0

    /** The content script of the top frame of a new document connected */
    fun onTopFrame(port: WebExtension.Port) {
        topFramePort = port
    }

    /** The content script of a frame disconnected */
    fun onFrameGone(port: WebExtension.Port) {
        if (topFramePort == port) topFramePort = null
    }

    /** The top frame told the zoom of the page relative to its width, and whether the user zoomed in */
    fun onZoomReply(request: Int, zoomedIn: Boolean, visualZoom: Double?) {
        zoomCallbacks.remove(request)?.invoke(zoomedIn, visualZoom)
    }

    /** Asks the top frame; a page that does not answer soon (still loading) counts as not zoomed in */
    private fun askZoom(callback: (zoomedIn: Boolean, visualZoom: Double?) -> Unit) {
        val port = topFramePort ?: return callback(false, null)
        val request = ++zoomRequests
        zoomCallbacks[request] = callback
        port.postMessage(JSONObject().put("zoomRequest", request))
        uiHandler.postDelayed({ onZoomReply(request, false, null) }, ZOOM_REPLY_TIMEOUT_MS)
    }

    override fun applyPageZoom(zoom: Int) {
        pageZoomValue = zoom
        applyViewportMode()
        topFramePort?.postMessage(JSONObject().put("pageZoom", pageZoomSettings(zoom)))
    }

    override fun isZoomedIn(callback: (Boolean) -> Unit) = askZoom { zoomedIn, _ -> callback(zoomedIn) }

    /** The pinch of the cursor; the page is asked once GeckoView has settled after the fingers are lifted */
    override fun zoomVisually(zoomIn: Boolean, onDone: (Double?) -> Unit) {
        val cursor = webView?.cursorDrawerDelegate ?: return onDone(null)
        val onEnd: () -> Unit = { uiHandler.postDelayed({ askZoom { _, visualZoom -> onDone(visualZoom) } }, PINCH_SETTLE_MS) }
        if (zoomIn) cursor.tryZoomIn(onEnd) else cursor.tryZoomOut(onEnd)
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
        playlists.clear()
        _pageVideos.value = emptyList()
        mediaFrames.clear()
        mediaSources.clear()
        updateMediaState()
        updateMediaVideo()
    }
    val selectionActionDelegate = MySelectionActionDelegate { webView }
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
    /**
     * The one of the user or of the tablet view; GeckoView gives its own User-Agent only asynchronously
     */
    override val userAgentString: String?
        get() = session.settings.userAgentOverride

    override var userAgentOverride: String? = null
        set(value) {
            field = value
            applyUserAgentOverride()
        }

    /**
     * The User-Agent and viewport modes of GeckoView: desktop for the desktop view; mobile for the others, where
     * the tablet view has a User-Agent of its own ([TABLET_USER_AGENT]) and gets the width of the desktop view from
     * page_zoom.js. A change takes effect with the next load.
     */
    override var view: SiteView = SiteView.MOBILE
        set(value) {
            if (value == field) return
            field = value
            session.settings.userAgentMode = if (value == SiteView.DESKTOP) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            applyUserAgentOverride()
            applyViewportMode()
            //the view is shared by all tabs: only the attached one sets its cursor
            if (callback != null) applyPointerMode()
        }

    /** The User-Agent of the user, else that of the tablet view in it */
    private fun applyUserAgentOverride() {
        val userAgent = userAgentOverride ?: if (view == SiteView.TABLET) TABLET_USER_AGENT else null
        if (userAgent != session.settings.userAgentOverride) session.settings.userAgentOverride = userAgent
    }

    /** The page zoom of the page shown or loading */
    private var pageZoomValue = PageZoom.DEFAULT

    /**
     * The desktop viewport of GeckoView ignores the viewport of the page, so a page zoomed out ([PageZoom]) gets the
     * mobile viewport, where page_zoom.js writes the width of the desktop view with the zoom
     */
    private fun applyViewportMode() {
        val mode = if (view == SiteView.DESKTOP && pageZoomValue == PageZoom.DEFAULT) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else
            GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        if (session.settings.viewportMode != mode) session.settings.viewportMode = mode
    }

    /** A page of the [url] is to load: it gets the viewport of its page zoom */
    fun onPageZoomOf(url: String) {
        pageZoomValue = WebEngineFactory.pageZooms?.zoomOf(url) ?: PageZoom.DEFAULT
        applyViewportMode()
    }

    /**
     * What page_zoom.js of the top frame gets: the zoom of the page and, in a wide view (desktop, tablet) without
     * the desktop viewport (also for a page that went there in the history with the viewport of another), the width
     * of the desktop view
     */
    fun pageZoomSettings(zoom: Int): JSONObject = JSONObject().put("zoom", zoom).put("desktopWidth",
        if (view.wide && session.settings.viewportMode == GeckoSessionSettings.VIEWPORT_MODE_MOBILE) DESKTOP_WIDTH else 0)

    /** The cursor is a mouse for pages in a wide view (desktop, tablet), a finger in the mobile one */
    private fun applyPointerMode() {
        webView?.cursorDrawerDelegate?.pointerMode = if (view.wide) PointerMode.MOUSE else PointerMode.TOUCH
    }

    init {
        Log.d(TAG, "init")
        session = GeckoSession(GeckoSessionSettings.Builder()
            //Gecko deletes the data of private sessions when the last of them is closed
            .usePrivateMode(tab.incognito)
            //the browser core gives the view of the page's host before it loads
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .useTrackingProtection(AppContext.provideConfig().trackingProtection)
            //a container of its own: the cookies, the storage and the cache of the kids profile
            .apply { if (AppContext.kidsProfile) contextId(KIDS_CONTEXT_ID) }
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
            onPageZoomOf(tab.url)
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
        //what the user loads is no popup of the page that opened the window
        popupOpener = null
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
        onPageZoomOf(url)
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

    /** Shows the block page of the site filter in place of the page of [url] ([replace]: of a redirect) */
    fun showSiteBlockPage(url: String, block: SiteBlock, replace: Boolean) {
        val loader = GeckoSession.Loader().uri(GeckoSiteBlocks.pageUrl(AppContext.get(), url, block))
        if (replace) loader.flags(GeckoSession.LOAD_FLAGS_REPLACE_HISTORY)
        session.load(loader)
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
            sessionLost = false
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

    override fun resumePlayback() {
        if (sendToFrame(MediaCommand.PLAY.js)) return
        mediaSessionDelegate.mediaSession?.play()
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

    override fun find(text: String) {
        if (text.isEmpty()) {
            clearFind()
            return
        }
        findQuery = text
        runFind(text, GeckoSession.FINDER_FIND_FORWARD)
    }

    override fun findNext(forward: Boolean) {
        if (findQuery == null) return
        //no text: the next match of the last one
        runFind(null, if (forward) GeckoSession.FINDER_FIND_FORWARD else GeckoSession.FINDER_FIND_BACKWARDS)
    }

    private fun runFind(text: String?, flags: Int) {
        val query = findQuery ?: return
        val generation = ++findGeneration
        val finder = session.finder
        finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL or GeckoSession.FINDER_DISPLAY_DRAW_LINK_OUTLINE
        finder.find(text, flags).accept({ result ->
            if (result == null || generation != findGeneration || findQuery != query) return@accept
            //a total of -1: more matches than Gecko counts
            val total = if (result.total < 0) FindResult.MANY else result.total
            _findResult.value = FindResult(query, if (result.found) result.current.coerceAtLeast(1) else 0,
                if (result.found) total else 0)
        }, { e ->
            Log.w(TAG, "find failed", e)
        })
    }

    override fun clearFind() {
        findQuery = null
        findGeneration++
        session.finder.clear()
        _findResult.value = null
    }

    override suspend fun renderThumbnail(bitmap: Bitmap?, onScreen: Boolean): Bitmap? {
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
        //a session whose content process died in the background opens again with its page (not the one of a
        //new window, which GeckoView opens)
        val reopened = sessionLost && !session.isOpen
        if (reopened) {
            sessionLost = false
            session.open(runtime)
        }
        webView.coverUntilFirstPaint(Color.WHITE)
        webView.setSession(session)
        if (reopened) {
            restorePage()
        } else if (session.isOpen && previousSession != null && previousSession != session) {
            //not reloaded: the page keeps what it did in the background (the result of a sign-in its popup
            //gave it just before it closed, which a reload lost)
            Log.d(TAG, "Activating session")
            session.setActive(true)
        }
    }

    /**
     * The content process of [lostSession] crashed or was killed (for memory), which closes the session. A session
     * shown in the view opens again with its page and is given to the view anew: the view keeps the display of the
     * closed one, so the reopened session draws nothing and the view stays covered in white. A background session
     * stays closed until its tab is attached again ([onAttachToWindow]).
     */
    fun onContentProcessLost(lostSession: GeckoSession) {
        val view = webView
        if (lostSession != session || view == null || view.session != session) {
            Log.w(TAG, "Background session lost")
            if (lostSession == session) sessionLost = true
            return
        }
        Log.w(TAG, "Current session lost, reopening")
        view.releaseSession()
        if (!session.isOpen) session.open(runtime)
        view.coverUntilFirstPaint(Color.WHITE)
        view.setSession(session)
        restorePage()
    }

    /** The page of a session opened again: its saved state, else its url */
    private fun restorePage() {
        val state = progressDelegate.sessionState
        if (state != null) {
            onPageZoomOf(tab.url)
            session.restoreState(state)
        } else {
            url?.let { loadUrl(it) }
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
            videoScope.cancel()
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