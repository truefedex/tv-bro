// The media keys of the remote and the video mode (MediaFrames.kt), for both engines: WebView runs it in every
// frame (webview_media.js starts it), GeckoView in the content script of its extension (the build copies it
// there). tvBroWatchMedia(send) reports the state of the media of the frame that the commands go to as a JSON
// string to send ({media: false} when it has none) and returns the function that runs a command; the app sends
// each command to the frame that shows a video in fullscreen, else to the one that plays. Without send (old
// WebViews: the main frame only) it reports nothing and only runs commands. With frames (WebView, which runs no
// scripts in frames of about:blank and about:srcdoc, where many players write themselves) the media of the
// same-origin frames inside that have no scripts of the app count as media of this frame.
function tvBroWatchMedia(send, frames) {
    var lastPlayed = null;
    var lastState = '';
    // the media that met encrypted data: the page plays it with DRM, often before it sets its mediaKeys
    var encrypted = typeof WeakSet == 'function' ? new WeakSet() : null;

    function isPlaying(m) {
        return !m.paused && !m.ended;
    }

    // the window of a frame element that has no scripts of the app (they set tvBroMediaCommand), null otherwise
    function scriptlessWindow(frame) {
        try {
            var w = frame.contentWindow;
            return w && w.document && !w.tvBroMediaCommand ? w : null;
        } catch (e) {
            return null; // another origin: its own scripts run there
        }
    }

    // those windows inside this one, also nested in each other
    function childWindows() {
        var result = [];
        if (!frames) return result;
        (function walk(doc) {
            var list = doc.querySelectorAll('iframe, frame');
            for (var i = 0; i < list.length; i++) {
                var w = scriptlessWindow(list[i]);
                if (!w) continue;
                result.push(w);
                walk(w.document);
            }
        })(document);
        return result;
    }

    function fullscreenIn(doc) {
        var e = doc.fullscreenElement || doc.webkitFullscreenElement;
        if (!e) return null;
        if (e.tagName != 'IFRAME' && e.tagName != 'FRAME') return e;
        // an ancestor frame sees the iframe as its fullscreen element: the frame inside holds the media, and
        // tells it itself unless it has no scripts
        var w = frames && scriptlessWindow(e);
        return w ? fullscreenIn(w.document) : null;
    }

    function fullscreenElement() {
        return fullscreenIn(document);
    }

    // (by the tag: media of another frame are not instances of the classes of this one)
    function mediaIn(root) {
        if (root.tagName == 'VIDEO' || root.tagName == 'AUDIO') return [root];
        return Array.prototype.slice.call(root.querySelectorAll('video, audio'));
    }

    function allMedia() {
        return childWindows().reduce(function(list, w) {
            return list.concat(mediaIn(w.document));
        }, mediaIn(document));
    }

    function area(m) {
        var r = m.getBoundingClientRect();
        return r.width * r.height;
    }

    // the playing media, else the one played last, else the largest
    function pick(list) {
        if (!list.length) return null;
        var playing = list.filter(isPlaying);
        if (playing.length) {
            list = playing;
        } else if (lastPlayed && list.indexOf(lastPlayed) >= 0) {
            return lastPlayed;
        }
        return list.reduce(function(a, b) { return area(b) > area(a) ? b : a; });
    }

    function target() {
        var fs = fullscreenElement();
        var m = fs && pick(mediaIn(fs));
        if (m) return m;
        if (lastPlayed && lastPlayed.isConnected && isPlaying(lastPlayed)) return lastPlayed;
        return pick(allMedia()) || (lastPlayed && lastPlayed.isConnected ? lastPlayed : null);
    }

    // a page may refuse to play: its promise is rejected
    function play(m) {
        var p = m.play();
        if (p && p.catch) p.catch(function() {});
    }

    function seek(m, position) {
        var end = isFinite(m.duration) ? m.duration : Infinity;
        m.currentTime = Math.max(0, Math.min(end, position));
    }

    // a command, or a command and its number of seconds: 'seekTo:42.5', 'seekBy:-10'
    function command(command) {
        var m = target();
        if (!m) return;
        var parts = String(command).split(':');
        var seconds = parseFloat(parts[1]);
        switch (parts[0]) {
            case 'toggle':
                if (m.paused || m.ended) play(m); else m.pause();
                break;
            case 'play':
                play(m);
                break;
            case 'pause':
                m.pause();
                break;
            case 'stop':
                m.pause();
                m.currentTime = 0;
                break;
            case 'rewind':
                seek(m, m.currentTime - 10);
                break;
            case 'forward':
                seek(m, m.currentTime + 10);
                break;
            case 'seekTo':
                if (isFinite(seconds)) seek(m, seconds);
                break;
            case 'seekBy':
                if (isFinite(seconds)) seek(m, m.currentTime + seconds);
                break;
        }
    }

    function post(state) {
        if (state == lastState) return;
        lastState = state;
        send(state);
    }

    // the media the commands go to: whether it plays, where, how long it is (null: endless or unknown)
    function report() {
        var m = target();
        if (!m) {
            post(JSON.stringify({media: false}));
            return;
        }
        var fs = fullscreenElement();
        post(JSON.stringify({
            media: true,
            playing: isPlaying(m),
            fullscreen: !!(fs && (fs == m || fs.contains(m))),
            position: m.currentTime,
            duration: isFinite(m.duration) ? m.duration : null,
            // a live stream has no end (an unknown duration is NaN)
            live: m.duration === Infinity,
            // the position stands still while the media waits for data
            rate: isPlaying(m) && m.readyState > 2 ? m.playbackRate : 0,
            // no sound: previews and ads mostly play muted
            muted: m.muted || m.volume === 0,
            // what it plays: its address, or a stream of the page (Media Source Extensions)
            src: /^https?:/i.test(m.currentSrc) ? m.currentSrc : null,
            mse: /^blob:/i.test(m.currentSrc),
            // another address is another video (an ad, then the film)
            source: m.currentSrc || null,
            // protected (Encrypted Media Extensions): only the page can play it
            drm: !!m.mediaKeys || !!(encrypted && encrypted.has(m))
        }));
    }

    // the app moves the position on by itself while the media plays: this corrects it now and then
    var lastTimeReport = 0;
    function reportTime() {
        var now = Date.now();
        if (now - lastTimeReport < 1000) return;
        lastTimeReport = now;
        report();
    }

    if (!send) return command;
    // media events do not bubble, but the window gets them while they are captured
    var MEDIA_EVENTS = ['play', 'pause', 'ended', 'emptied', 'seeked', 'playing', 'waiting', 'ratechange',
        'durationchange', 'loadedmetadata', 'volumechange', 'timeupdate', 'encrypted'];
    function onMediaEvent(e) {
        var t = e.target;
        if (!t || (t.tagName != 'VIDEO' && t.tagName != 'AUDIO')) return;
        if (e.type == 'play') lastPlayed = t;
        if (e.type == 'encrypted' && encrypted) encrypted.add(t);
        if (e.type == 'timeupdate') reportTime(); else report();
    }
    function listen(w) {
        MEDIA_EVENTS.forEach(function(type) { w.addEventListener(type, onMediaEvent, true); });
        ['fullscreenchange', 'webkitfullscreenchange'].forEach(function(type) {
            w.addEventListener(type, report, true);
        });
    }
    listen(window);
    if (frames) {
        // the frames without scripts come and go, and a page that writes one anew (document.open) drops its
        // listeners: they are added again now and then (the same listener is added only once)
        setInterval(function() {
            var windows = childWindows();
            windows.forEach(listen);
            if (windows.length) reportTime();
        }, 1000);
    }
    // a frame that goes away (or into the back-forward cache) has no media for the app
    window.addEventListener('pagehide', function() {
        post(JSON.stringify({media: false}));
        lastState = '';
    }, true);
    window.addEventListener('pageshow', function(e) {
        if (e.persisted) report();
    }, true);
    return command;
}
