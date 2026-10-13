# TV Bro

Simple web browser optimized to use with TV remote

Features:
- working with TV remote
- tabs and bookmarks support
- voice search support
- switch user agent support
- use Android builtin web rendering engine (WebKit/Blink based)
- built-in download manager
- browsing history
- shortcuts

Discussion pages:
- https://forum.xda-developers.com/android/apps-games/tv-bro-browser-android-based-tvs-t3545295

## Development

Launch a build straight at a URL with `adb` (handy for testing). The package id depends on the
`appstore` flavor — `p403n1x87` → `com.phlox.tvwebbrowser.p403n1x87`, `generic`/`google` →
`com.phlox.tvwebbrowser`:

```
adb shell am start -a android.intent.action.VIEW -d "http://example.com" -p com.phlox.tvwebbrowser.p403n1x87
```

Every pull request is built with the same pipeline as a release, so a broken build or a bad version
cannot reach a tag. Releases are cut by pushing a `vX.Y.Z` tag — the tag becomes the app version and
drives the GitHub release (see [AGENTS.md](AGENTS.md)).

See [AGENTS.md](AGENTS.md) for build and debug instructions.
