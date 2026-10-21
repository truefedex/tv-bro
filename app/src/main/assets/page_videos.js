// The videos of a frame that other apps can play (see PageVideos.kt), for both engines: WebView runs it
// with media_controls.js in every frame, GeckoView in the content script of its extension (the build
// copies it there). tvBroWatchPageVideos(send) reports the videos of the frame as a JSON string to send:
// {videos: [{url, element, width, height, duration}], title, page, ua}; an empty list when the frame goes.
// The app decides which addresses are videos: here only the candidates are picked, the media elements
// (their addresses, and the files they load) and the manifests of streams the page loads (.m3u8, .mpd).
function tvBroWatchPageVideos(send) {
    var MAX = 50;
    var found = {};
    var order = [];
    var timer = null;
    var lastSent = '';

    function add(url, element, info) {
        if (!url || !/^https?:/i.test(url)) return;
        var known = found[url];
        var changed = false;
        if (!known) {
            if (order.length >= MAX) return;
            known = found[url] = {url: url, element: false};
            order.push(url);
            changed = true;
        }
        if (element && !known.element) {
            known.element = true;
            changed = true;
        }
        if (info) {
            ['width', 'height', 'duration'].forEach(function(key) {
                var value = info[key];
                if (value && isFinite(value) && known[key] !== value) {
                    known[key] = value;
                    changed = true;
                }
            });
        }
        if (changed) schedule();
    }

    function schedule() {
        if (!timer) timer = setTimeout(flush, 500);
    }

    function flush() {
        timer = null;
        var message = JSON.stringify({
            videos: order.map(function(url) { return found[url]; }),
            title: document.title, page: location.href, ua: navigator.userAgent
        });
        if (message == lastSent) return;
        lastSent = message;
        send(message);
    }

    function scanElements() {
        var videos = document.querySelectorAll('video');
        for (var i = 0; i < videos.length; i++) {
            var v = videos[i];
            var info = {width: v.videoWidth, height: v.videoHeight, duration: v.duration};
            add(v.currentSrc || v.src, true, info);
            var sources = v.querySelectorAll('source');
            for (var j = 0; j < sources.length; j++) add(sources[j].src, true, null);
        }
    }

    function onResource(entry) {
        var path = entry.name.split(/[?#]/)[0].toLowerCase();
        if (/\.(m3u8|mpd)$/.test(path)) {
            add(entry.name, false, null);
        } else if (entry.initiatorType == 'video') {
            add(entry.name, true, null);
        }
    }

    try {
        new PerformanceObserver(function(list) {
            list.getEntries().forEach(onResource);
        }).observe({type: 'resource', buffered: true});
    } catch (e) {
        // older engines: what was loaded so far, the media elements give the rest
        try { performance.getEntriesByType('resource').forEach(onResource); } catch (e2) {}
    }
    // media events do not bubble, but the window gets them while they are captured
    ['loadedmetadata', 'durationchange', 'play', 'emptied'].forEach(function(type) {
        window.addEventListener(type, function(e) {
            if (e.target && e.target.tagName == 'VIDEO') scanElements();
        }, true);
    });
    document.addEventListener('DOMContentLoaded', scanElements);
    window.addEventListener('load', scanElements);
    // a frame that goes away (or into the back-forward cache) has no videos for the app
    window.addEventListener('pagehide', function() {
        lastSent = '';
        send(JSON.stringify({videos: []}));
    }, true);
    window.addEventListener('pageshow', function(e) {
        if (e.persisted) {
            lastSent = '';
            schedule();
        }
    }, true);
}
