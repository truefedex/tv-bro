// The videos of a frame that other apps can play (see PageVideos.kt), for both engines: WebView runs it
// with media_controls.js in every frame, GeckoView in the content script of its extension (the build
// copies it there). tvBroWatchPageVideos(send) reports the videos of the frame as a JSON string to send:
// {videos: [{url, element, type, width, height, duration}], subtitles: [{url, lang, label, showing}], title, page,
// ua}; an empty list when the frame goes. The subtitles of the frame are the <track> files of its media elements
// (showing: the engine draws them now; players that draw them themselves leave them hidden), those the configuration
// of a PlayerJS player lists, and the subtitle files the page loads (the one loaded last counts as shown). The app decides which addresses are videos: here only the candidates are picked, the media elements
// (their addresses, and the files they load) and the manifests of streams the page loads: by the extension
// (.m3u8, .mpd), or by the type of the response where the engine tells it (contentType of Resource Timing:
// empty for other sites unless the request passed CORS, as players load manifests), then reported as type.
function tvBroWatchPageVideos(send) {
    var MAX = 50;
    var STREAM_TYPES = /^(application|audio)\/(vnd\.apple\.mpegurl|x-mpegurl|mpegurl|dash\+xml)$/i;
    var found = {};
    var order = [];
    var subtitles = [];
    // the text tracks of the media elements watched for new tracks and modes
    var watchedTracks = typeof WeakSet == 'function' ? new WeakSet() : null;
    var timer = null;
    var lastSent = '';

    function add(url, element, info, type) {
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
        if (type && !known.type) {
            known.type = type;
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
            subtitles: subtitles,
            title: document.title, page: location.href, ua: navigator.userAgent
        });
        if (message == lastSent) return;
        lastSent = message;
        send(message);
    }

    function scanElements() {
        var videos = document.querySelectorAll('video');
        var tracks = [];
        for (var i = 0; i < videos.length; i++) {
            var v = videos[i];
            var info = {width: v.videoWidth, height: v.videoHeight, duration: v.duration};
            add(v.currentSrc || v.src, true, info);
            var sources = v.querySelectorAll('source');
            for (var j = 0; j < sources.length; j++) add(sources[j].src, true, null);
            scanTracks(v, tracks);
        }
        trackSubtitles = tracks;
        scanPlayerConfigs();
        scanPlayerApis();
        updateSubtitles();
    }

    // the page itself: GeckoView runs this in a content script, which sees the objects of the page through
    // wrappedJSObject; WebView runs it in the page
    var page = window.wrappedJSObject || window;

    function absolute(url) {
        try { return new URL(String(url), location.href).href; } catch (e) { return null; }
    }

    // players that draw subtitles themselves and keep them in their own state: JW Player (the tracks of the playlist
    // item, its captions list tells the one shown) and video.js with emulated text tracks (its remote tracks)
    var apiSubtitles = [];
    function scanPlayerApis() {
        var found = [];
        function put(url, label, lang, showing) {
            url = url && absolute(url);
            if (!url || !/^https?:/i.test(url) || found.length >= MAX) return;
            found.push({url: url, label: label ? String(label) : null, lang: lang ? String(lang) : null, showing: !!showing});
        }
        try {
            if (typeof page.jwplayer == 'function') {
                var jw = page.jwplayer();
                var item = jw && jw.getPlaylistItem && jw.getPlaylistItem();
                var captions = (jw.getCaptionsList && jw.getCaptionsList()) || [];
                var current = jw.getCurrentCaptions ? jw.getCurrentCaptions() : 0;
                var shown = current > 0 && captions[current] ? String(captions[current].id) : null;
                var list = (item && item.tracks) || [];
                for (var i = 0; i < list.length; i++) {
                    var t = list[i];
                    var kind = String(t.kind || 'captions').toLowerCase();
                    if (kind != 'captions' && kind != 'subtitles') continue;
                    put(t.file, t.label, t.language, shown != null && absolute(t.file) == absolute(shown));
                }
            }
        } catch (e) {}
        try {
            if (page.videojs && typeof page.videojs.getPlayers == 'function') {
                var players = page.videojs.getPlayers();
                for (var id in players) {
                    var player = players[id];
                    var remote = player && player.remoteTextTracks && player.remoteTextTracks();
                    for (var j = 0; remote && j < remote.length; j++) {
                        var track = remote[j];
                        var kind2 = String(track.kind || '').toLowerCase();
                        if (track.src && (kind2 == 'captions' || kind2 == 'subtitles')) {
                            put(track.src, track.label, track.language, track.mode == 'showing');
                        }
                    }
                }
            }
        } catch (e) {}
        apiSubtitles = found;
    }

    // the subtitles of the frame: the <track> files, those of the configuration of its player, those it loaded;
    // the file loaded last is the one shown (players load them when they are turned on)
    var trackSubtitles = [];
    var configSubtitles = [];
    var loadedSubtitles = [];

    function updateSubtitles() {
        var list = [];
        var lastLoaded = loadedSubtitles[loadedSubtitles.length - 1];
        function put(s) {
            if (list.length >= MAX) return;
            var known = list.filter(function(k) { return k.url == s.url; })[0];
            if (known) {
                known.label = known.label || s.label;
                return;
            }
            list.push({url: s.url, lang: s.lang || null, label: s.label || null,
                showing: !!s.showing || s.url == lastLoaded});
        }
        trackSubtitles.forEach(put);
        apiSubtitles.forEach(put);
        configSubtitles.forEach(put);
        loadedSubtitles.forEach(function(url) { put({url: url}); });
        if (JSON.stringify(list) != JSON.stringify(subtitles)) {
            subtitles = list;
            schedule();
        }
    }

    // PlayerJS (common on video sites) is configured in an inline script:
    // new Playerjs({file: '…', subtitle: "[English]https://…/en.vtt,[Українські]https://…/ua.vtt"})
    var configsScanned = false;
    function scanPlayerConfigs() {
        if (configsScanned && configSubtitles.length) return;
        var scripts = document.querySelectorAll('script:not([src])');
        var found = [];
        for (var i = 0; i < scripts.length; i++) {
            var text = scripts[i].textContent;
            if (text.indexOf('Playerjs') < 0) continue;
            var config = /\bsubtitle\s*:\s*(["'])([^"']+)\1/.exec(text);
            if (!config) continue;
            var item = /(?:\[([^\]]*)\])?\s*(https?:\/\/[^\s,\[\]"']+)/g;
            var m;
            //a configuration written as JSON escapes its slashes
            var value = config[2].replace(/\\\//g, '/');
            while ((m = item.exec(value)) && found.length < MAX) found.push({url: m[2], label: m[1] || null});
        }
        if (document.readyState == 'complete') configsScanned = true;
        configSubtitles = found;
    }

    // a subtitle file the page loads, by its extension; not the cues of seek previews (thumbnails as WebVTT)
    function isSubtitleFile(url, path) {
        return /\.(vtt|srt|ass|ssa|ttml|dfxp)$/.test(path) && !/thumb|sprite|storyboard|preview/i.test(url);
    }

    // the subtitle files of the element; a track added later or turned on or off scans again
    function scanTracks(v, tracks) {
        var elements = v.querySelectorAll('track');
        for (var i = 0; i < elements.length && tracks.length < MAX; i++) {
            var t = elements[i];
            var kind = (t.kind || 'subtitles').toLowerCase();
            if (kind != 'subtitles' && kind != 'captions') continue;
            if (!/^https?:/i.test(t.src) || tracks.some(function(s) { return s.url == t.src; })) continue;
            tracks.push({url: t.src, lang: t.srclang || null, label: t.label || null,
                showing: !!(t.track && t.track.mode == 'showing')});
        }
        var list = v.textTracks;
        if (list && list.addEventListener && watchedTracks && !watchedTracks.has(list)) {
            watchedTracks.add(list);
            list.addEventListener('addtrack', scanElements);
            list.addEventListener('change', scanElements);
        }
    }

    function onResource(entry) {
        var path = entry.name.split(/[?#]/)[0].toLowerCase();
        var type = entry.contentType;
        if (type && STREAM_TYPES.test(type)) {
            add(entry.name, false, null, type.toLowerCase());
        } else if (/\.(m3u8|mpd)$/.test(path)) {
            add(entry.name, false, null);
        } else if (entry.initiatorType == 'video') {
            add(entry.name, true, null);
        } else if (isSubtitleFile(entry.name, path) && /^https?:/i.test(entry.name)) {
            loadedSubtitles = loadedSubtitles.filter(function(url) { return url != entry.name; });
            loadedSubtitles.push(entry.name);
            updateSubtitles();
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
