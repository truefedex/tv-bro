// WebView: starts the scripts of the frame (media_controls.js, page_videos.js, run before this one) with the
// tvBroMedia object the app adds to every frame (WebViewMediaControls). Without that object (old WebViews) it
// runs in the main frame only, and the app calls tvBroMediaCommand itself.
(function() {
    if (window.tvBroMediaCommand) return;
    var channel = window.tvBroMedia;
    var send = channel ? function(message) { channel.postMessage(message); } : null;
    window.tvBroMediaCommand = tvBroWatchMedia(send);
    if (!channel) return;
    channel.onmessage = function(e) {
        window.tvBroMediaCommand(e.data);
    };
    tvBroWatchPageVideos(send);
})();
