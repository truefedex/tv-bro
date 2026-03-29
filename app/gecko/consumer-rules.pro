-keep class com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine { *; }

-keepclassmembers class org.mozilla.geckoview.** {
    *** mDisplay;
}
