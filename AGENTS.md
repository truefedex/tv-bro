# tv-bro — agent notes

## Branch / context
- Work in progress on branch `feat/stream-player`: native video playback handoff for WebView + GeckoView (shared `:app:player` module).

## Known issues / TODO
- **Toolbar auto-hidden after every action — FIXED.**
  Added `Config.keepToolbarOpen` (`KEEP_TOOLBAR_OPEN_KEY`, default `true`), setting string
  `keep_toolbar_open`, exposed as a switch on the Appearance settings page
  (`AppearanceSettingsView`).
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
  (`ibBack → ibForward → ibRefresh → ibTabs → ibFavorites → ibMedia → ibSettings → ibMenu`). Nets: on a fresh tab
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
- **Native player BACK never took effect (predictive back bypassed the activity) — FIXED.**
  `NativeVideoActivity` handled BACK in `dispatchKeyEvent`, but at `targetSdk 35+` predictive back is on
  by default: the system finished the activity on the first press through
  `Activity$RequestFinishCallback` → `finishAfterTransition()`, never reaching `dispatchKeyEvent`
  (and `onBackPressed()` was not called either). The activity is now opted out via
  `android:enableOnBackInvokedCallback="false"` in the player manifest, and BACK takes two presses:
  the first only reveals the controls, the second leaves the player and hands the position back to the
  page. Controller visibility is tracked through `PlayerView.setControllerVisibilityListener`
  (`PlayerView` exposes no plain "is the controller shown"; `isControllerFullyVisible()` is false while
  the controls animate), a matching UP is swallowed via `backConsumedDown` so a single press cannot
  leak through, and holding BACK is ignored. Verified on emulator: one BACK keeps the player open
  (controls shown then auto-hidden after 2.5s), two quick BACKs return to `MainActivity`.
  `SystemPlayerActivity` deliberately keeps predictive back: it does not handle BACK and wants the
  first press to finish.
- **D-pad could not get past a disabled Back/Forward button — FIXED.**
  `ActionBar.setBackEnabled`/`setForwardEnabled` only set `isEnabled`, but a disabled view still
  reports `isFocusable() == true`: the framework's `View.isFocusable()` ignores the enabled flag
  (only `canTakeFocus()` checks it), and `FocusFinder.findNextUserSpecifiedFocus` accepts the
  explicit `nextFocusRight` target *before* `requestFocus` fails — with no fallback. So with Back
  enabled and Forward greyed out, `ibBack.nextFocusRight="ibForward"` trapped the remote and RIGHT
  never reached the URL box. Both setters now also sync `isFocusable` with `isEnabled`, so the
  explicit chain walks past the disabled button (`ibBack → ibRefresh → etUrl`). The same trap applied
  to the hidden tab/stream buttons, whose inner `ImageButton` keeps its own `VISIBLE`/focusable flags
  while the wrapper `FrameLayout` is `GONE`; `setTabsCounter`/`setMediaCounter` now sync the inner
  button's `isFocusable` too, and `catchFocus()` checks `isShown && isFocusable` (it previously tested
  the child's own `visibility == VISIBLE`, so a button hidden by a `GONE` parent could be "picked"
  only for the focus request to fail). Verified on emulator: Back focused + Forward disabled → RIGHT →
  `ibRefresh` → `etUrl`; hidden stream button skipped both ways (`ibFavorites ↔ ibSettings`).
- **Downloaded update APK was never cleaned up — FIXED.**
  `UpdateChecker.downloadUpdate()` writes to a fixed-name `update.apk` in a cache dir and can't delete
  it right after launching the installer (PackageInstaller reads the file asynchronously), but it was
  only purged on app *version change* (`checkVersionCodeAndRunMigrations`), and a cancelled/failed
  download left the partial file behind too. Now a failed/cancelled attempt deletes the temp file
  immediately (after `job.join()`, when `downloaded` is false), and `MainActivityViewModel.loadState()`
  runs `UpdateChecker.clearTempFilesIfAny()` unconditionally at startup -- so at most one stale APK
  can linger (fixed name) and it is removed on the next launch.

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
  gained a **Toggle theme** entry (`R.string.toggle_theme`; after Settings moved to the toolbar it is
  index 9 in `onShowMenu()`, ahead of Close tab/Close app at 10/11) backed by
  `toggleTheme()`, which cycles `Config.Theme` in enum order **SYSTEM → WHITE → BLACK → SYSTEM**
  (`entries[(ordinal + 1) % size]`). Verified on emulator: SYSTEM(night) →
  Toggle theme → `theme=1` (white, toolbar brightness 224/255, pill `#E0E0E0`), toggle again →
  `theme=2` (dark, brightness 57/255, pill `#3A3A3A`), and a full cycle reads `2→0→1→2`; no crash,
  activity stays focused.
- **Toolbar buttons appear only when useful, and Settings moved into the bar.**
  `ibTabs` and `ibMedia` (each wrapped in a `FrameLayout` with a count badge, `flTabsButton` /
  `flMediaButton`) are now hidden as a whole when there is nothing to act on:
  `ActionBar.setTabsCounter(count)` hides the tab button (and its `tvTabsCounter` badge) when
  `count <= 1` — the panel is only needed to switch between tabs, and new tabs can still be created
  from the menu — and `setMediaCounter(count)` hides the stream button when `count <= 0`. The tab
  count is seeded both from `MainActivity`'s `tabsModel.tabsStates.subscribe { }` and explicitly in
  `loadState()` (the latter because `tabsStates` is retained across the activity re-creation the
  theme toggle performs, so the observer is not re-fired and the button would otherwise stay hidden).
  A Settings gear (`ibSettings`, `ic_settings_grey_900_24dp`) was added to the toolbar between the
  stream and overflow buttons, bound to `callback.showSettings()`; the matching "Settings" entry was
  removed from the overflow menu (now 12 items: Toggle theme → 9, Close tab → 10, Close app → 11) and
  the focus chain updated (`ibMedia → ibSettings → ibMenu`). Verified on emulator: 1 tab → no tab
  button; 2 tabs → button with badge `2` at `[8,0][104,96]`; a page with an HLS stream → stream
  button with badge `2`; the gear opens the settings dialog.
- **Bookmarks workflow reworked into a right-side panel (star button).**
  The star (`ibFavorites`) used to open `FavoritesDialog` (a modal popup). Replaced with a
  right-anchored panel `rlBookmarksPanel` in `activity_main.xml` hosting
  `view.bookmarks.BookmarksPanelView` (mirrors the left `llTabPanel`/`TabsView` pattern). First
  entry is shown only on a real page (non-null `http` url that isn't `Config.isHomePageUrl`) and
  toggles the current page: it reads "Add this page" (`add_this_page`, outline star) when the page is
  not bookmarked and opens `FavoriteEditorDialog` prefilled with the current tab title/url, or
  "Remove from list" (`remove_from_list`, filled `ic_star_grey_900_36dp`) when it already is, deleting
  it via `MainActivityViewModel.deleteBookmark()`. `MainActivity.updateBookmarkState()` flips the
  toolbar star (`ActionBar.setBookmarked()`) between `ic_star_border_grey_900_36dp` and the filled
  star, and is refreshed on every `onWebViewUpdated()` (page start/finish, tab switch) and after any
  add/remove/edit. The rest of the list is all top-level bookmarks (`FavoritesDao.getAllBookmarks()`,
  plus a new `getByUrl()` for the current-page lookup).
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
- **WebView stream detection: manifests hidden behind extensionless gateways, and signed variants.**
  The WebView engine only sees request URLs (no response MIME, unlike Gecko), so a manifest fetched
  from an extensionless gateway with the real file name in the query
  (e.g. `.../secure_hls.php?path=ab12%2Findex.m3u8&e=...&sig=...`) was never listed. New
  `WebViewEx.queryNamesManifest()` (URL-decodes the query and looks for `.m3u8`/`.mpd`) is OR'd into
  `isMediaCandidate()`, and `inferCastKind()` classifies such URLs by the query. Since the gateway
  re-requests the same stream with rotated `e`/`sig`/`v` tokens, every rotation used to add a row;
  the new `MediaUrlIdentity.key()` (shared `:app:player`) collapses them. Its key is `host+path` plus
  only the query parameters whose decoded value names a resource (contains `/` or `.m3u8`/`.mpd`),
  sorted — if no parameter names a resource the whole query is kept verbatim, so genuinely distinct
  variants (`v.mp4?quality=low` vs `high`, `master.m3u8?token=a` vs `b`) are never merged.
  `WebViewEx` keeps a `detectedMediaIdentities` map and swaps a refreshed URL in place (list order
  preserved); `GeckoWebEngine.mediaCandidates()`/`prefetchMediaMetadata()` group by the same identity
  and keep the freshest observation. Unit-tested by `MediaUrlIdentityTest`. Verified on the emulator
  (WebView engine): the gateway now yields a single `secure_hls.php · HLS` row across several
  signature rotations.
- **Cosmetic overlay zapper (WebView engine).**
  `app/src/main/assets/overlay_zapper.js` is injected as a document-start script in every frame while
  ad blocking is on, complementing the network blocker with runtime DOM cleanup of overlays that
  first-party scripts build after load: transparent/empty max-z-index click traps, hidden off-screen
  popunder anchors, and max-z-index ad boxes inside open shadow roots (the host is removed). It is
  WebView-only (`WebViewCompat.addDocumentStartJavaScript`, feature-gated on
  `WebViewFeature.DOCUMENT_START_SCRIPT`); `GeckoWebEngine.onUpdateAdblockSetting` remains an
  intentional no-op stub. WebViews older than M86 have no document-start API, so when the feature is
  unsupported (or registration throws) `updateOverlayZapper()` sets a fallback flag and
  `injectOverlayZapperFallback()` (called from `onPageFinished`) injects the same asset into the main
  frame; the script self-guards, so re-injection is harmless. This restores zapping on old providers
  but, without the document-start API, cross-origin iframes cannot be reached (they are covered on
  WebView ≥ M86). Per-tab state overrides the global default via
  `WebEngineWindowProviderCallback.isAdBlockingEnabled()`.
- **Settings dialog rebuilt around a declarative, native-TV row framework.**
  `dialog_settings.xml` keeps the two-pane layout (left category rail + right content) but the rail
  now has **7 categories** and each button is `@dimen/settings_tab_height` (64dp) tall, so the rail
  fits a 1080p TV screen. The categories are General, Ads & privacy, Appearance, Playback, Controls,
  Advanced and About. Every page is a `SettingsPageView` (a `ScrollView` that stacks rows inside a
  `LinearLayout`) declared as a `List<SettingsItem>` and rendered by `SettingsRowFactory`. The
  `SettingsItem` sealed interface has `Header` (dimmed caps group title), `Toggle` (trailing switch,
  whole row is the click target), `Choice` (current value + chevron → single-choice dialog), `TextInput`
  (value → text dialog), `Action` (OK runs it, optional progress spinner), `Slider` (inline seekbar
  adjusted with LEFT/RIGHT), `KeyBinding` (bound key + chevron → `ShortcutDialog`) and `Info`
  (non-focusable read-only row, optional leading icon). Every row except `Header`/`Info` carries a
  leading icon drawn from `@color/day_night_icon_color` (see the new `ic_settings_*_24dp.xml` set).
  `SettingsItem`'s `visible` lambdas make conditional rows declarative; a page calls `post { rebuild() }`
  after a toggle that changes visibility (or after a dialog), and `SettingsPageView.rebuild()` keeps
  the currently-focused row focused by index. There is no deferred `save()`: settings commit
  immediately on toggle or on dialog OK (`SettingsDialog.onDismiss` now only calls `onDismissed`).
  Page classes: `GeneralSettingsView` (browsing + startup), `AdsSettingsView` (ad blocking + details
  + redirects), `AppearanceSettingsView` (theme/darkening/toolbar/screen-on),
  `PlaybackSettingsView`, `ControlsSettingsView`, `AdvancedSettingsView`, `VersionSettingsView`.
  The old `MainSettingsView`/`view_settings_main.xml` were removed.
- **Controls page merges the shortcut bindings; "Version & updates" is now a native "About" page.**
  `ShortcutsSettingsView` (a bare `ListView`) and `view_shortcut.xml` were deleted. Its rows are now
  `SettingsItem.KeyBinding` entries under a **Shortcuts** section on `ControlsSettingsView`, which is
  ordered **Input** (joystick-axes toggle) → **Virtual cursor** (two sliders) → **Shortcuts** (one row
  per `Shortcut.entries`, showing the bound key or "Not set"); OK opens the existing `ShortcutDialog`,
  and the row refreshes on dismiss. (The old code looked shortcuts up by list position; the new code
  binds the enum entry directly.) The About category is `VersionSettingsView`, now a
  `SettingsPageView`: an **About this app** group (TV Bro, version, build flavor, engine version as
  `Info` rows), a **Links** group (GitHub repository, Support the author, Open source license, Privacy
  policy, Why the icon changed — all `Action` rows that open the URL and close the dialog via the
  `Callback`) and, only when `BuildConfig.BUILT_IN_AUTO_UPDATE`, an **Updates** group (auto-check
  toggle, update-channel `Choice`, and an install-update `Action` shown when an update is available).
  The dead `view_settings_version.xml` layout was removed. Verified on emulator: 7-tab rail
  (GENERAL…ABOUT, each 64dp); Controls shows Input/Virtual cursor/Shortcuts with `KEYCODE_REFRESH`,
  `KEYCODE_MEDIA_*`, etc.; the bind dialog opens and the page survives dismiss; About shows
  `TV Bro`, `2.1.6`, `p403n1x87 · geckoIncluded`, the WebView engine version and all five links, plus
  the **Updates** group (auto-check switch on by default, update channel `release`); tapping
  GitHub closed the dialog and navigated to `github.com/P403n1x87/tv-bro`.
- **Self-updates now come from this fork's GitHub Releases, and the fork releases the `p403n1x87`
  flavor.**
  `UpdateChecker.checkGitHubReleases()` replaced the old fetch of
  `raw.githubusercontent.com/truefedex/tv-bro/master/latest_version.json`: it calls
  `https://api.github.com/repos/<repo>/releases` (with the mandatory `User-Agent` header), skips drafts
  and—unless the selected channel includes them—pre-releases, and picks the newest release that
  actually ships an APK for the running build. Asset selection mirrors `.github/workflows/release.yml`:
  match `-<appstore>-<webengine>[-<abi>].apk` using `BuildConfig.FLAVOR_appstore`,
  `BuildConfig.FLAVOR_webengine` and `Build.SUPPORTED_ABIS[0]` (the ABI is required for
  `geckoIncluded`, which ships native code; `geckoExcluded` releases a single universal APK). Version
  ordering can no longer use a numeric `versionCode` (releases only carry a `vX.Y.Z` tag), so
  `UpdateChecker.compareVersions()` compares dotted version components numerically (and ignores any
  `-suffix`), which `hasUpdate()` and the changelog dialog both use; the release body is the changelog.
  Channels are now `release`/`pre-release` (any non-`release` channel includes pre-releases). A
  successful check with nothing to offer caches an empty result, so opening About repeatedly does not
  re-hit the API. Wiring: `p403n1x87` now sets `BUILT_IN_AUTO_UPDATE=true` and
  `UPDATE_REPO="P403n1x87/tv-bro"`; `generic`/`google` keep `UPDATE_REPO="truefedex/tv-bro"` (and
  `google` stays `false`), so the original flavor still points upstream. Because release APKs are named
  `<appstore>-<webengine>`, a fork release is only installable on a build with the same
  `applicationId`+signing key, i.e. the `p403n1x87` flavor (hence the About **GitHub repository** link
  is derived from `UPDATE_REPO`). `.github/workflows/release.yml` now builds
  `assembleP403n1x87GeckoIncludedRelease -PenableAbiSplits` (3 ABIs) and
  `assembleP403n1x87GeckoExcludedRelease`, renaming to `tvbro-<v>-p403n1x87-gecko{Included,Excluded}…apk`
  and tagging `v<version>`. The old root `latest_version.json` is now unused by the app. Unit-tested by
  `UpdateCheckerVersionTest`. Verified on emulator (`p403n1x87GeckoIncluded` debug): About shows the
  UPDATES section with the auto-check switch on and channel `release`; the GitHub link went to
  `github.com/P403n1x87/tv-bro`; asset/version matching validated against upstream's real releases
  (the fork has none yet, which the checker treats as "no update").
- **Configurable shortcuts for the tab panel, stream list and bookmarks.**
  Three entries were appended to the `Shortcut` enum — `TOGGLE_TABS_PANEL` ("Tabs"), `SHOW_STREAMS`
  ("Streams on this page") and `SHOW_BOOKMARKS` ("Bookmarks") — with fresh `prefsKey`s (appending
  keeps the existing ordinals/prefs stable). `ShortcutMgr.process()` dispatches them to
  `MainActivity.onToggleTabPanel()`, `onShowStreams()` and `showFavorites()` respectively. The
  Controls page's Shortcuts section lists `Shortcut.entries` automatically, so the three rows appear
  with "Not set" and can be bound through `ShortcutDialog` like any other shortcut. Verified on
  emulator: all three appear at the bottom of the Shortcuts section.
- **Update alerts: badge on the settings gear and a dot on the About category.**
  While an update is available (`needAutoCheckUpdates` on **and** the last check found something
  newer, see the new `AutoUpdateModel.isUpdateAvailable()`), the toolbar settings button shows a small
  accent circle (`vSettingsUpdateBadge` — an `ImageView` over `ibSettings` inside the new
  `flSettingsButton` `FrameLayout`, `@drawable/ic_update_badge_circle`, `@color/update_badge_bg`
  = `#006EC5` day / `#5FACFF` night; purely decorative, never focusable) and `MainActivity` updates
  its content description. The **About** rail tab mirrors it with the same circle as a right compound
  drawable on `btnVersionTab` (`SettingsDialog.refreshAboutTab()`), refreshed live via the new
  `VersionSettingsView.Callback.onUpdateStateChanged()` (fired after checks, channel changes and the
  auto-check toggle). `MainActivity.updateUpdateBadge()` is called after the startup check, on
  `onResume`, and when Settings is dismissed (so disabling auto-check or switching channels clears the
  badge). Verified on emulator (temporary `versionName 2.0.0` + upstream repo so `v2.1.6` matched):
  the About tab shows a ~22px accent dot next to ABOUT once the check lands, the About page lists
  **New version available v2.1.6** + **Install update**, and the gear badge appears after closing
  Settings; with no update all indicators are absent.
- **Updates section moved above Links; upstream author stays credited.**
  In the About page (`VersionSettingsView.buildItems()`) the **Updates** group (auto-check toggle,
  channel, new-version row, install action, only when `BUILT_IN_AUTO_UPDATE`) is now rendered directly
  after "About this app" and before **Links**, so the update controls are the first actionable rows.
  The **Links** group gained an **Original project** row (`URL_UPSTREAM`, `settings_upstream_project`)
  pointing at `truefedex/tv-bro`, alongside the existing "Support the author" (truefedex donation),
  upstream license and privacy pages — so even though the **GitHub repository** row now points at this
  fork, the original author stays credited.
- **Release-asset download follows GitHub's redirect and sends a User-Agent.**
  `UpdateChecker.downloadUpdate()` now sets `instanceFollowRedirects = true` and a `User-Agent` on the
  `HttpURLConnection`. GitHub release assets answer with a 302 to a signed `release-assets.githubusercontent.com`
  URL (verified with curl: 302 → 200, `application/vnd.android.package-archive`); `HttpURLConnection`
  follows GET redirects within a protocol but the explicit flag + UA make the install-from-releases
  path robust. Direct install still relies on `REQUEST_INSTALL_PACKAGES` + the FileProvider
  (`${applicationId}.provider`), so a debug-signed build cannot be updated by a release-signed APK.
- **History uniqueness: each site appears once, with its latest visit time.**
  `HistoryDao.deleteByUrl(url)` removes any existing row for the URL right before
  `logVisitedHistory` inserts the new visit (inside the debounced save job, so a visit whose job gets
  cancelled by a newer one never deletes the previously saved row). History storage is bounded by
  distinct URLs instead of repeat visits, and revisiting a site moves its entry to the top with the
  new timestamp rather than stacking a duplicate. Side effect: `frequentlyUsedUrls()` counts are
  always 1 per URL, so the home page's "most visited" ordering degrades to recency.
  Verified on emulator: visited `example.com` → `example.org` → `example.com`; the DB holds exactly
  2 rows (one per URL) with `example.com` carrying the newest `time`.
  With that dedup, `frequentlyUsedUrls()` (`GROUP BY title, url, favicon … ORDER BY count(url)`) is
  now effectively `last(8)` — every group has one row — but still pays for a GROUP BY/scan over the
  whole `history` table on each home page load. Left as-is for now (collapsing `MOST_VISITED` onto the
  recency query would drop a user-visible setting; restoring a per-URL `visits` counter is the other
  option), but it is the one remaining redundant query if we want to trim further.
- **Algorithm pass: no hot-path O(n²) left; `favorites.URL` was the one unindexed lookup.**
  A sweep of the hot paths (network sniffing, ad-block, cursor/D-pad events, settings, tabs) found the
  structures already appropriate: `detectedMedia`/`MediaRequestCatalog`/`MediaMetadataCache` are bounded
  `LinkedHashMap`s with `putIfAbsent`, `MyHistoryDelegate` uses a `HashSet`, `DPADNavigationEventsAdapter`
  a `Set<Int>`, the Brave engine does the ad matching, and every other DAO lookup is covered by an index
  (`hosts.host_name` unique, `history.time/title/url`, `downloads.time/filename`). Two fixes:
  - `favorites` had **no** index on `URL`, yet `getByUrl()` (`WHERE url=:url LIMIT 1`) is called from
    `updateBookmarkState()` on **every** page update (`onWebViewUpdated`), so it was a full table scan
    per page load. Added `favorites_url_idx` (`FavoriteItem`) + `MIGRATION_20_21`; `EXPLAIN QUERY PLAN`
    now reports `SEARCH favorites USING INDEX favorites_url_idx (URL=?)`. Verified on the emulator by
    upgrading the installed v20 DB in place: after `force-stop` (clean WAL checkpoint) the DB is
    `user_version 21` with the index present and data intact (favorites 2, history 29, hosts 7).
  - `MediaListAdapter.onMetadataUpdated` did `items.any { it.url == url }` (O(n) per metadata callback,
    O(n²) while the prefetcher warms a page). It now checks a `HashSet` built once from the listed URLs.
  Known-acceptable small-n scans (not changed): `tabsStates.indexOf(tab)` in `TabsView`/`MainActivity`
  (tab counts are small), `ShortcutMgr.shortCutsForEvent` (≤ `Shortcut.entries.size` per key event),
  `SimpleObservable.addObserver`'s `contains` guard (few observers).
- **Update download/install verified end-to-end on a real release asset.**
  On the emulator (generic geckoExcluded debug, temporary `versionName 2.0.0` + upstream repo):
  tapping DOWNLOAD on the changelog dialog downloaded `tvbro-2.1.6-generic-geckoExcluded.apk`
  (302-redirect followed in-app) to `cache/update.apk` = **6,790,605 bytes**, then
  `ACTION_INSTALL_PACKAGE` launched PackageInstaller's **"Do you want to update this app?"**
  (Update/Cancel) via the FileProvider URI. The install itself cannot complete on a debug-signed
  build (the release APK carries a different signing key — expected, documented limitation), but
  download → ProgressDialog → URI handoff all work. After dismissing the installer, `update.apk`
  stayed in the cache until the next app launch, then was deleted by the startup purge.
- **Settings streamlining: "Links on home page" removed, update channel hidden, playback labels clarified.**
  `Config.HomePageLinksMode` / `homePageLinksMode` and the whole suggestion-mode plumbing were dead
  (after the history URL dedup `MOST_VISITED` behaved exactly like `LATEST_HISTORY`), so the
  `GeneralSettingsView` row, the `SettingsModel` property/param and `HistoryDao.frequentlyUsedUrls()`
  were removed and `MainActivityViewModel.loadHomePageLinks()` now always uses `historyDao().last(8)`.
  The **Update channel** `Choice` row and its `availableChannels()` helper were dropped from
  `VersionSettingsView` (no pre-releases planned); `Config.updateChannel` (default `release`) and
  `UpdateChecker.AVAILABLE_CHANNELS` are left in place. The Playback page gained summaries
  (`allow_autoplay_media_summary`, `settings_auto_fullscreen_summary`) and `settings_auto_fullscreen`
  now reads "Auto-open fullscreen videos in the native player". The unused `home_page_links`
  strings/array were left in every locale (translations untouched).
- **Fullscreen videos auto-open in the native player on the WebView engine too (cross-engine).**
  Gecko already reacted through `MyMediaSessionDelegate.onFullscreen → maybeOptimize()`; WebView only
  exposes `WebChromeClient.onShowCustomView(view, callback)`, which carries no media information, so
  the handoff asks the page instead: the new `window.tvBroFullscreenVideo()` in `generic_injects.js`
  returns `"1|<positionMs>|<url>"` (empty url for MSE/`blob:`, `1|0|` for an `<iframe>`, `0|`
  otherwise) and pauses the media. `WebViewEx.probeFullscreenVideoForNative()` feeds that into
  `orderedCandidates()` and plays the first candidate in the built-in player at `positionMs` via the
  new `playInInternalPlayer(candidate, startMs)` overload (the `castStartMs` field carries it through
  `playCandidate`; `resolveAndPlay` resets it to 0).
  **Gotcha:** WebView delivers `onShowCustomView` *before* the page's DOM reflects the new fullscreen
  element — `document.fullscreenElement` is still `null` at callback time and only becomes the
  `<video>` ~100–250 ms later, so a one-shot read always saw "no video" and silently fell back to the
  in-page fullscreen. The fix shows the in-page fullscreen immediately and then polls
  (`FULLSCREEN_PROBE_DELAY = 120 ms`, `MAX_FULLSCREEN_PROBES = 5`, guarded by
  `fullscreenViewCallback !== callback`), upgrading to the native player once the element appears and
  leaving the in-page fullscreen untouched for non-video fullscreens; `onHideCustomView()` now also
  nulls `fullscreenViewCallback`. Verified on the emulator (WebView engine): tapping fullscreen logged
  `fullscreen handoff: 1 candidate(s), resumeAt=4216ms`, `NativeVideoActivity` opened playing from that
  position, and a short clip returned to the page when it ended.

## Build / test
- macOS: `export JAVA_HOME="$HOME/java/jdk-21.0.12.1+1/Contents/Home"` then `./gradlew <task> --console=plain`.
- Linux: JDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` (already exported from `~/.zshrc`), plus a JDK 17 at
  `/usr/lib/jvm/java-17-openjdk-amd64` — `buildSrc` pins `jvmToolchain(17)` and only auto-detects it (no
  foojay download for the `buildSrc` build), so both JDKs are required. Then `./gradlew <task> --console=plain`.
  Android SDK lives at `$HOME/.android/sdk` (`ANDROID_HOME`); emulator AVD is `tvbro_tv_api36`
  (Android TV 1080p, `system-images;android-36;android-tv;x86_64`).
- Run Gradle tasks **serially** — two concurrent builds of the same project share `build/` and corrupt
  the Kotlin incremental state (symptoms: bogus `Unresolved reference` errors, "multiple Kotlin daemon sessions").
- Unit tests: `:app:player:testDebugUnitTest` and `:app:testP403n1x87GeckoIncludedDebugUnitTest`
  (e.g. `--tests "com.phlox.tvwebbrowser.utils.UpdateCheckerVersionTest"`).
- Debug APKs (flavor `p403n1x87GeckoIncluded`): `./gradlew :app:assembleP403n1x87GeckoIncludedDebug -PenableAbiSplits`.
- Launch the app straight at a URL (handy for manual testing). The package id depends on the `appstore`
  flavor: `p403n1x87` → `com.phlox.tvwebbrowser.p403n1x87`, `generic`/`google` → `com.phlox.tvwebbrowser`:
  ```
  adb shell am start -a android.intent.action.VIEW -d "http://example.com" -p com.phlox.tvwebbrowser.p403n1x87
  ```
  Add `-W` to block until it is up (`Status`/`TotalTime`); a cold start can take a few seconds, so wait
  before interacting. A page click can be simulated with `adb shell input tap <x> <y>` — needed to
  trigger `requestFullscreen()`, which requires a user gesture.
