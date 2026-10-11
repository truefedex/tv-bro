# tv-bro — agent notes

## Branch / context
- Work in progress on branch `feat/stream-player`: native video playback handoff for WebView + GeckoView (shared `:app:player` module).

## Known issues / TODO
- **Toolbar auto-hides after every action — we want to fix this.**
  TV Bro's toolbar is a *modal overlay*, not an overlay drawn over the live page:
  opening it hides the live webview and shows a static thumbnail (`MainActivity.showMenuOverlay()`),
  and `hideMenuOverlay()` restores the live page. It auto-hides after toolbar actions:
  - switch tab: `MainActivity.kt:290`
  - close tab: `MainActivity.kt:527` (`hideMenuOverlay(true)` + `hideBottomPanel()`)
  - URL input done: `MainActivity.kt:386`
  - history: `MainActivity.kt:325`, favorites: `MainActivity.kt:338`
  - voice search: `MainActivity.kt:1055`
  Goal: add a setting to keep the toolbar visible over the *live* page (still hidden during the
  native video fullscreen handoff). Not implemented yet.

## Build / test
- `export JAVA_HOME="$HOME/java/jdk-21.0.12.1+1/Contents/Home"` then `./gradlew <task> --console=plain`.
- Unit tests: `:app:player:testDebugUnitTest`.
- Debug APKs (flavor `p403n1x87GeckoIncluded`): `./gradlew :app:assembleP403n1x87GeckoIncludedDebug -PenableAbiSplits`.
