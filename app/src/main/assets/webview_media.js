// WebView: starts the scripts of the frame (media_controls.js, page_videos.js, run before this one) with the
// tvBroMedia object the app adds to every frame (WebViewMediaControls). Without that object (old WebViews) it
// runs in the main frame only, and the app calls tvBroMediaCommand itself.
(function() {
    if (window.tvBroMediaCommand) return;
    var channel = window.tvBroMedia;
    var send = channel ? function(message) { channel.postMessage(message); } : null;
    // WebView runs no scripts in frames of about:blank and about:srcdoc: these take the media of such frames inside
    window.tvBroMediaCommand = tvBroWatchMedia(send, !!channel);
    if (!channel) return;
    channel.onmessage = function(e) {
        window.tvBroMediaCommand(e.data);
    };
    tvBroWatchPageVideos(send, true);
})();
