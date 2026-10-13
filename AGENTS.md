# AGENTS.md

Guidance for AI coding agents working in this repository.

## Project

TV Bro is an Android web browser for TV remotes (D-pad navigation, virtual cursor, tabs, bookmarks, voice search, downloads, history). It can render pages with either the system WebView or a bundled GeckoView engine. Package/applicationId: `com.phlox.tvwebbrowser`.

## Build & test

Gradle (Kotlin DSL) with JDK 21. On Windows use `gradlew.bat` / `.\gradlew`.

Variants combine two flavor dimensions with the build type: `appstore` = `generic` | `google` | `foss`, and `webengine` = `geckoIncluded` | `geckoExcluded`. Examples:

```bash
./gradlew assembleGenericGeckoIncludedDebug
```
```bash
./gradlew assembleGenericGeckoExcludedRelease
```
```bash
./gradlew testGenericGeckoIncludedDebugUnitTest
```
```bash
./gradlew :app:testGenericGeckoIncludedDebugUnitTest --tests "com.phlox.tvwebbrowser.utils.FaviconExtractorTest"
```
```bash
./gradlew :app:common:testDebugUnitTest
```

- A plain `assemble` does not include Gecko in the base APK: the gecko module becomes a separate split APK (Android Studio installs both). For a single APK with Gecko inside, build a universal APK from the bundle, optionally for one ABI:

```bash
./gradlew packageGenericGeckoIncludedReleaseUniversalApk -PabiFilter=arm64-v8a
```

- Pass `-PenableAbiSplits` to `assemble` to produce per-ABI APKs (armeabi-v7a, arm64-v8a, x86_64).
- Release signing reads the env vars `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Without `KEYSTORE_PATH`, release builds are unsigned.
- App unit tests use Robolectric (`isIncludeAndroidResources = true`). There are no instrumented tests.
- `buildSrc` compiles with `allWarningsAsErrors = true`.

## Module layout

- `:app`: the base application. It holds the UI (activities, dialogs, settings), the Room `AppDatabase`, the download service, the WebView engine (`webengine/webview`), adblock and auto-update.
- `:app:common`: an Android library shared by `:app` and `:app:gecko`. It holds the engine abstraction (`webengine/WebEngine.kt` and `WebEngineFactory`), the `Config` prefs, the Room entity models, `AppContext`, `ActiveModel`/`ActiveModelsRepository`, and the cursor widgets (`CursorLayout`). Code that both engines need belongs here, because the gecko module must not depend on app-only classes. It currently has to depend on `:app`, but only because dynamic feature modules require that.
- `:app:gecko`: the GeckoView engine (`GeckoWebEngine`, delegates, and a bundled WebExtension under `assets/extensions/generic`). It is a **dynamic feature module** (`com.android.dynamic-feature`: install-time on devices with `android.software.leanback`, on-demand elsewhere, fused into universal APKs). It has the same flavors as the base app; all its code, resources and the GeckoView dependency live in the `geckoIncluded` source set, so its `geckoExcluded` variants are empty.
- `buildSrc`: convention plugins `tvbro.android.application`, `tvbro.android.library` and `tvbro.android.feature` (all in `TvBroAndroidModulePlugin.kt`). They apply AGP and set compileSdk, minSdk and targetSdk from `gradle/libs.versions.toml`. The application and feature plugins also declare the product flavors, because a dynamic feature must have exactly the base app's variants; `app/build.gradle.kts` only adds per-flavor settings. All dependency versions live in that version catalog.

## Key architecture

**Web engine plug-in mechanism.** Engines register themselves rather than being referenced directly. Each engine class has a `companion object { init { WebEngineFactory.registerProvider(...) } }`. `TVBro.initWebEngineStuff()` triggers that registration through reflection (`Class.forName("...WebViewWebEngine")` and `Class.forName("...GeckoWebEngine")`). A `ClassNotFoundException` for Gecko is expected in variants that leave it out. `WebEngineFactory.initialize()`, called from `MainActivity`, picks the provider whose name matches `Config.webEngine` and falls back to the first registered one. Settings only shows the engine picker when more than one provider is registered. **Keep base-app code free of compile-time references to gecko classes.** All interaction goes through the `WebEngine` interface and `WebEngineFactory`.

**Gecko delivery.** In universal APKs (GitHub, `generic`/`foss`) the gecko module is fused into the APK and found by `Class.forName` at startup. In the `google` flavor, Play installs it together with the app on Android TV (`android.software.leanback`), because Play does not support on-demand delivery on TV devices (https://issuetracker.google.com/issues/572988451). On other devices it is downloaded on demand: when Gecko is selected but missing, `TVBro` registers `GeckoFeatureInstaller` (`app/src/google`) as a `WebEngineInstaller`. `WebEngineFactory.initialize()` then runs it, which shows `WebEngineInstallView` over `MainActivity`, downloads the module through Play, and restarts the app. A restart is required: the native libraries of a split installed while the process runs cannot be loaded. The install-time module is marked removable: where Play can manage modules, Settings shows a "Remove the GeckoView engine" button that calls `deferredUninstall`. `FeatureDelivery.canUninstallGecko()` probes `getSessionStates`, which Play rejects with `API_NOT_AVAILABLE` on Android TV. Play Core (`feature-delivery`) is a `google`-only dependency. `generic` and `foss` use the no-op `FeatureDelivery` from `app/src/withoutPlayCore`.

**Gecko as a split APK.** Gecko needs some workarounds when installed as a split:
- GeckoView reads `omni.ja` from `Context.getPackageResourcePath()`, so `FeatureModuleSupport.kt` gives `GeckoRuntime` a context that points to `split_gecko.apk`.
- Content providers in the feature manifest would crash the app before the module is downloaded. So GeckoView's `GeckoClipboardContentProvider` is removed from the feature manifest, and the base declares `GeckoClipboardProviderProxy` (in `app/src/geckoIncluded`) instead.
- `bundletool --local-testing` can test the download UI, but not running Gecko afterwards. SplitCompat-emulated splits cannot load Gecko's native libraries.

**R8 and dependency alignment for the feature module.** R8 keeps the base module's copy of any library shared with the feature. Because of that:
- `app/build.gradle.kts` pins `play-services-basement`/`play-services-tasks` to the versions GeckoView resolves. In `geckoIncluded` variants it also adds `play-services-basement` itself, because the merged feature manifest references its resources.
- It adds Guava to `geckoIncluded` variants, so `ListenableFuture` is in the base even when the feature is not downloaded, along with an empty `guava-listenablefuture` artifact.

Comments in `libs.versions.toml` explain why. When you bump GeckoView or feature-delivery, recheck these versions. The feature's keep rules are in `app/gecko/proguard-rules.pro`.

**State models.** Screens keep state in `ActiveModel` subclasses (for example `MainActivityViewModel`, `TabsModel`, `SettingsModel` and `AdblockModel`). They get them through `ActiveModelsRepository.get(Model::class, activity)`, not through AndroidX ViewModel. A model is released when every activity using it finishes. Observable state uses the custom `utils/observable` types (`ObservableValue` and `ObservableList`).

**Global singletons.** `AppContext.get()` and `AppContext.provideConfig()` (initialized in `TVBro.onCreate`) give `:app:common` code access to the Application and the `Config` SharedPreferences wrapper. `AppDatabase.db` is the Room database. Its schema version lives in `singleton/AppDatabase.kt`, and migrations are written by hand there.

**Flavors.** `BUILT_IN_AUTO_UPDATE` (a BuildConfig field) is true only for `generic`. The auto-updater reads `latest_version.json` from the repo (release/beta channels with per-ABI APK URLs). `foss` adds the `.foss` applicationId suffix. `geckoIncluded` raises minSdk to 26 and sets `BuildConfig.GECKO_SUPPORTED`, which lets Settings offer Gecko before it is downloaded (the picker lists `WebEngineFactory.getAvailableEngines()`).

**Home page.** The home page is served from local assets (`app/src/main/assets/pages`) for WebView. GeckoView still loads the web version, and moving it to the local assets is pending.

## Release

Releases use the manual `.github/workflows/release.yml` workflow (`workflow_dispatch` with a version). It builds per-ABI universal `genericGeckoIncluded` APKs (with the gecko module fused in, via `-PabiFilter`) and the `genericGeckoExcluded` release APK, renames them `tvbro-<version>-...apk`, and publishes a GitHub release whose body is `RELEASE_NOTES.md`. A version bump touches `versionCode`/`versionName` in `app/build.gradle.kts`, `latest_version.json`, `RELEASE_NOTES.md`, and the F-Droid/Play metadata changelog under `metadata/en-US/changelogs/<versionCode>.txt`. The app reads `latest_version.json` from `master` on GitHub, so changes to it go live once merged.
