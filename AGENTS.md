# tv-bro — agent notes

## Branch / context
- Work in progress on branch `feat/stream-player`: native video playback handoff for WebView + GeckoView (shared `:app:player` module).

## Known issues / TODO
- **Toolbar auto-hidden after every action — FIXED.**
  Added `Config.keepToolbarOpen` (`KEEP_TOOLBAR_OPEN_KEY`, default `true`), setting string
  `keep_toolbar_open`, switch `scKeepToolbarOpen` in `view_settings_main.xml` wired in
  `MainSettingsView.initKeepToolbarOpenUI()`.
  When ON, `MainActivity.showMenuOverlay()` keeps `flWebViewContainer` visible (toolbar floats
  over the live page instead of swapping in the `ivMiniatures` thumbnail), and the new
  `autoHideMenuOverlay()` (a no-op when the setting is ON) replaces `hideMenuOverlay()` at all
  the auto-hide call sites (tab switch, close tab, URL input done, history, favorites, voice
  search, new tab, history-activity result). When OFF, the legacy modal-thumbnail behavior is
  unchanged. Toolbar is still force-hidden during the native video fullscreen handoff via
  `onPrepareForFullscreen()` (and `showMenuOverlay()` early-returns while `isFullscreen`).
- **Tab switching broken on tap (touch) — FIXED.**
  `view_horizontal_webtab_item.xml` had `android:focusableInTouchMode="true"`, so on a touch/tap the
  first tap was consumed to acquire focus (`View.onTouchEvent` skips `performClick()` when
  `focusTaken`), and the tab never switched until a second tap. Removed `focusableInTouchMode`
  (kept `android:focusable="true"` so DPAD still works) and changed
  `MainActivity.tabsListener.onTitleSelected(index)` to switch by the passed `index`
  (`tabByTitleIndex(index)` → `changeTab`) instead of relying on the stale focused tab in
  `vb.vTabs.current`. Verified on emulator: single tap AND DPAD focus+OK both switch tabs in
  keep-toolbar-open (overlay) and modal modes.
- **Home-page bookmark clicks did nothing (automatic-redirect blocker too greedy) — FIXED.**
  `MyNavigationDelegate.onLoadRequest` computed `isAutomaticRedirect` with
  `!request.isDirectNavigation`, but GeckoView's `isDirectNavigation` is true only for
  app-initiated `GeckoSession.load()`, so *every* link/`window.open` tap was flagged as an
  automatic redirect and the blocker denied cross-site navigations (home-page bookmarks live on a
  different site than `127.0.0.1`). Switched to `!request.hasUserGesture` (the actual user-gesture
  signal; the WebView path already used `request.isRedirect`). Verified on emulator: tapping a
  bookmark now navigates, while a script auto-redirect (`location.href` with no gesture) is still
  blocked. Blocker only active when `blockAutomaticRedirects && adBlockEnabled`.
- **Focus trapped in the page: no way back to the toolbar — FIXED.**
  With the virtual cursor on, DPAD stays inside the WebView/GeckoView, so the remote had no escape
  hatch back to the top bar. `MainActivity.handleBackNavigation()` now has a
  `vb.flWebViewContainer.hasFocus()` branch that calls `showMenuOverlay()` (which focuses the top
  bar) instead of the old `toggleMenu()` fallthrough. `ActionBar.catchFocus()` previously always
  targeted `ibBack`, but Back/Forward are disabled on a tab with no history and a disabled view
  cannot take focus — so it now walks the button row and focuses the first enabled, visible one
  (`ibBack → ibForward → ibRefresh → ibTabs → ibFavorites → ibMedia → ibMenu`). Nets: on a fresh tab
  BACK still reliably moves focus out of the page.
- **Tab-toggle and overflow icons were black-on-dark — FIXED.**
  `ic_menu_grey_900_36dp` (`ibTabs`) and `ic_more_vert_grey_900_36dp` (`ibMenu`) hardcoded their
  `fillColor="#212121"`, unlike the other toolbar glyphs which use the theme-aware
  `@color/day_night_icon_color` (`#212121` in `values/`, `#FDFDFD` in `values-night/`). Both now use
  the resource so they turn white in dark mode and match the rest of the bar.
- **Tab panel: close "X" unreachable with the D-pad — FIXED.**
  Opening the panel calls `vb.vTabs.requestFocus()`, but the panel is made VISIBLE and focused in the
  same frame, so the `RecyclerView` had no laid-out rows and `TabsView.onFocusChanged`'s hand-off
  loop matched nothing. Focus stayed on the full-size `vTabs` container (DOWN finds nothing below it,
  RIGHT escaped to the top bar — `ibFavorites`). Moved the hand-off into a `post { focusCurrentTabRow() }`
  from `onFocusChanged` so it runs after layout. With the row focused, `TabsAdapter`'s
  `setOnKeyListener` (RIGHT → `ibCloseTabItem`, LEFT → row) makes the X reachable/releasable.
  Verified on emulator: panel opens with the current row focused; RIGHT highlights the X, LEFT returns.
- **Overlay zapper was never registered — FIXED.**
  `WebViewEx.init()` calls `updateOverlayZapper(callback.isAdBlockingEnabled())`, but the WebView is
  constructed before `WebViewWebEngine.onAttachToWindow()` assigns `callback`, so the internal
  `webViewCallback` returned `false` and the document-start script was never added (page reported
  `NOINJ`). Re-apply in `onAttachToWindow` via `webView?.onUpdateAdblockSetting(callback.isAdBlockingEnabled())`.
  Also fixed `MainActivity.WebEngineCallback.isAdBlockingEnabled()` to read its own `tab.adblock ?: config.adBlockEnabled`
  (it read `tabsModel.currentTab.value?.adblock`, which is wrong while a tab isn't selected yet), and
  re-sync every open tab when the global toggle is dismissed (`SettingsDialog.onDismissed` →
  `MainActivity.showSettings()`), since the zapper is registration-based while the network blocker
  re-checks live. Verified on emulator: test page with a transparent click-trap, hidden popunder and
  shadow-root video ad box now reports `INJ:CLEAN`.

## Feature notes
- **Chrome-like URL box in the top bar.**
  The address `EditText` (`etUrl` in `view_actionbar.xml`) no longer uses the default underline
  background and no longer inflates the bar with `15dp/5dp` vertical margins. It is now a fixed
  `@dimen/url_box_height` (40dp) rounded pill (`@drawable/url_box_background`, 20dp corners, filled
  `@color/url_box_background`, accent `@color/url_box_stroke` when focused) with
  `gravity=center_vertical` and 12dp horizontal padding. `ActionBar.init()` sets the root
  `LinearLayout` `gravity = CENTER_VERTICAL` so the pill and the 48dp buttons share one baseline;
  the per-button `layout_marginTop="4dp"` was removed. Net: the bar is exactly
  `@dimen/toolbar_button_size` (48dp) tall instead of being stretched by the URL box.
  New colors in `values/` (subtle grey `#E0E0E0` pill, white `#FFFFFF` when focused, `#006EC5`
  stroke) and `values-night/` (`#3A3A3A` / `#454545` / `#5FACFF`).
- **Light/dark theme toggle, applied live.**
  `Config.Theme` (`SYSTEM/WHITE/BLACK`) already existed and was wired to
  `AppCompatDelegate.setDefaultNightMode`, but `MainActivity` declares `uiMode` in
  `android:configChanges`, so AppCompat never recreated the activity and the already-inflated views
  kept the old colors (hence the old "need restart" toast). The `config.theme.subscribe { ... }`
  block in `MainActivity.onCreate` now calls `recreate()` (guarded by `!isFinishing && !isDestroyed`)
  after setting the night mode, so the top bar and dialogs repaint immediately. The overflow menu
  gained a **Toggle theme** entry (`R.string.toggle_theme`, inserted at index 10 in
  `onShowMenu()` between Settings and Close tab, shifting Close tab/Close app to 11/12) backed by
  `toggleTheme()`, which cycles `Config.Theme` in enum order **SYSTEM → WHITE → BLACK → SYSTEM**
  (`entries[(ordinal + 1) % size]`). The restart toast in
  `MainSettingsView.initThemeSettingsUI()` was removed. Verified on emulator: SYSTEM(night) →
  Toggle theme → `theme=1` (white, toolbar brightness 224/255, pill `#E0E0E0`), toggle again →
  `theme=2` (dark, brightness 57/255, pill `#3A3A3A`), and a full cycle reads `2→0→1→2`; no crash,
  activity stays focused.
- **Tab-panel button shows an open-tab counter.**
  `ibTabs` in `view_actionbar.xml` is now wrapped in a `FrameLayout` with a `tvTabsCounter`
  `TextView` badge (`@drawable/gray_badge_bg`, `top|end`, `minWidth=20dp`), mirroring the existing
  `tvMediaCounter` pattern. `ActionBar.setTabsCounter(count)` hides it when `count <= 1` (a lone
  "1" is redundant) and shows the count otherwise. `MainActivity` updates it from the existing
  `tabsModel.tabsStates.subscribe { }` block (`it.size`), plus an explicit
  `setTabsCounter(tabsModel.tabsStates.size)` in `loadState()` because `tabsStates` is retained
  across the activity re-creation the theme toggle performs (so the observer is not re-fired) and
  the badge would otherwise vanish. Verified on emulator: 3 tabs → badge `3` at `[64,0][104,41]`,
  and it survives a theme-toggle recreate.
- **Bookmarks workflow reworked into a right-side panel (star button).**
  The star (`ibFavorites`) used to open `FavoritesDialog` (a modal popup). Replaced with a
  right-anchored panel `rlBookmarksPanel` in `activity_main.xml` hosting
  `view.bookmarks.BookmarksPanelView` (mirrors the left `llTabPanel`/`TabsView` pattern). First
  entry is "Add this page" (`add_this_page`), shown only on a real page (non-null `http` url that
  isn't `Config.isHomePageUrl`); tapping it opens `FavoriteEditorDialog` prefilled with the current
  tab title/url. The rest of the list is all top-level bookmarks (`FavoritesDao.getAllBookmarks()`).
  Tap navigates and closes the panel; long-press opens an Edit/Delete context menu. Edits go through
  `MainActivityViewModel.onHomePageLinkEdited()` and deletes through the new
  `MainActivityViewModel.deleteBookmark()` so the home page's `homePageLinks` stay in sync. The
  overflow menu's "Add bookmark" now calls `addCurrentPageAsBookmark()` directly. Star toggles the
  panel; BACK and entering URL-edit mode close it; opening it hides the tab panel. Also fixed
  `dialog_new_favorite_item.xml` whose header TextView was hardcoded to `@string/new_bookmark` — it
  now shows Edit vs New correctly. Removed the dead `FavoritesDialog`, `FavoritesListAdapter`,
  `FavoriteItemView`, `dialog_favorites.xml`, `view_favorite_item.xml`,
  `favorite_item_view_bg_selector.xml`.
- **Stream list popup: per-row icon, short label, and background manifest metadata.**
  The "Streams on this page" list (`MediaListAdapter` over `view_media_candidate_item.xml`) renders
  each candidate as `[icon] last-path-segment / KIND · host` plus a right-aligned metadata column.
  The list still builds instantly from URL+kind; opening it calls the new
  `WebEngine.prefetchMediaMetadata()` so `MainActivity.showMediaListDialog()` never blocks. Engines
  warm a shared `MediaMetadataCache` in the background only for HLS/DASH candidates
  (`MediaMetadataPrefetcher`, 2-thread pool, deduped, HLS/DASH only, progressive skipped); rows
  update live via `MediaMetadataCache.Listener` (registered on open, removed on dismiss — no
  polling). `ManifestInspector` now also extracts HLS `RESOLUTION`/`BANDWIDTH`/`#EXT-X-MEDIA`
  `LANGUAGE` and DASH `width`/`height`/`lang`/`bandwidth`/`mediaPresentationDuration` into
  `MediaMetadata`; the manifest body is fetched through the existing `ScopedMediaDataSource`
  (`MediaManifestReader`, extracted from `NativeMediaResolver.readManifest`, which now also populates
  the cache). `MediaCandidate` itself is unchanged. Verified on emulator: local HLS master →
  `1920×1080`, `en/fr`; DASH MPD → `1920×1080`, `en/fr · 1:02:03`.
- **Cosmetic overlay zapper (WebView engine).**
  `app/src/main/assets/overlay_zapper.js` is injected as a document-start script in every frame while
  ad blocking is on, complementing the network blocker with runtime DOM cleanup of overlays that
  first-party scripts build after load: transparent/empty max-z-index click traps, hidden off-screen
  popunder anchors, and max-z-index ad boxes inside open shadow roots (the host is removed). It is
  WebView-only (`WebViewCompat.addDocumentStartJavaScript`, feature-gated on
  `WebViewFeature.DOCUMENT_START_SCRIPT`); `GeckoWebEngine.onUpdateAdblockSetting` remains an
  intentional no-op stub. Per-tab state overrides the global default via
  `WebEngineWindowProviderCallback.isAdBlockingEnabled()`.

## Build / test
- macOS: `export JAVA_HOME="$HOME/java/jdk-21.0.12.1+1/Contents/Home"` then `./gradlew <task> --console=plain`.
- Linux: JDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` (already exported from `~/.zshrc`), plus a JDK 17 at
  `/usr/lib/jvm/java-17-openjdk-amd64` — `buildSrc` pins `jvmToolchain(17)` and only auto-detects it (no
  foojay download for the `buildSrc` build), so both JDKs are required. Then `./gradlew <task> --console=plain`.
  Android SDK lives at `$HOME/.android/sdk` (`ANDROID_HOME`); emulator AVD is `tvbro_tv_api36`
  (Android TV 1080p, `system-images;android-36;android-tv;x86_64`).
- Run Gradle tasks **serially** — two concurrent builds of the same project share `build/` and corrupt
  the Kotlin incremental state (symptoms: bogus `Unresolved reference` errors, "multiple Kotlin daemon sessions").
- Unit tests: `:app:player:testDebugUnitTest`.
- Debug APKs (flavor `p403n1x87GeckoIncluded`): `./gradlew :app:assembleP403n1x87GeckoIncludedDebug -PenableAbiSplits`.
