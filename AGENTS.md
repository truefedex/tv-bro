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

## Build / test
- `export JAVA_HOME="$HOME/java/jdk-21.0.12.1+1/Contents/Home"` then `./gradlew <task> --console=plain`.
- Unit tests: `:app:player:testDebugUnitTest`.
- Debug APKs (flavor `p403n1x87GeckoIncluded`): `./gradlew :app:assembleP403n1x87GeckoIncludedDebug -PenableAbiSplits`.
