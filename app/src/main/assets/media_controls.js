// The media keys of the remote (see WebViewMediaControls). The script runs in every frame: it tells the app
// the state of the frame's media through the tvBroMedia object of the app, and the app sends each command to
// the frame that shows a video in fullscreen, else to the one that plays. Without that object (old WebViews)
// it runs in the main frame only and the app calls tvBroMediaCommand itself.
(function() {
    if (window.tvBroMediaCommand) return;
    var lastPlayed = null;
    var lastState = '';

    function isPlaying(m) {
        return !m.paused && !m.ended;
    }

    function fullscreenElement() {
        var e = document.fullscreenElement || document.webkitFullscreenElement;
        // an ancestor frame sees the iframe as its fullscreen element: the frame inside holds the media
        return e && e.tagName != 'IFRAME' && e.tagName != 'FRAME' ? e : null;
    }

    function mediaIn(root) {
        if (root instanceof HTMLMediaElement) return [root];
        return Array.prototype.slice.call(root.querySelectorAll('video, audio'));
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
        return pick(mediaIn(document)) || (lastPlayed && lastPlayed.isConnected ? lastPlayed : null);
    }

    function seek(m, position) {
        var end = isFinite(m.duration) ? m.duration : Infinity;
        m.currentTime = Math.max(0, Math.min(end, position));
    }

    // a command, or a command and its number of seconds: 'seekTo:42.5', 'seekBy:-10'
    window.tvBroMediaCommand = function(command) {
        var m = target();
        if (!m) return;
        var parts = String(command).split(':');
        var seconds = parseFloat(parts[1]);
        switch (parts[0]) {
            case 'toggle':
                if (m.paused || m.ended) {
                    var p = m.play();
                    if (p && p.catch) p.catch(function() {});
                } else {
                    m.pause();
                }
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
    };

    function post(state) {
        var channel = window.tvBroMedia;
        if (!channel || state == lastState) return;
        lastState = state;
        channel.postMessage(state);
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
            // the position stands still while the media waits for data
            rate: isPlaying(m) && m.readyState > 2 ? m.playbackRate : 0
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

    if (!window.tvBroMedia) return;
    window.tvBroMedia.onmessage = function(e) {
        window.tvBroMediaCommand(e.data);
    };
    // media events do not bubble, but the window gets them while they are captured
    ['play', 'pause', 'ended', 'emptied', 'seeked', 'playing', 'waiting', 'ratechange', 'durationchange',
            'loadedmetadata', 'timeupdate'].forEach(function(type) {
        window.addEventListener(type, function(e) {
            if (!(e.target instanceof HTMLMediaElement)) return;
            if (type == 'play') lastPlayed = e.target;
            if (type == 'timeupdate') reportTime(); else report();
        }, true);
    });
    ['fullscreenchange', 'webkitfullscreenchange'].forEach(function(type) {
        window.addEventListener(type, report, true);
    });
    // a frame that goes away (or into the back-forward cache) has no media for the app
    window.addEventListener('pagehide', function() {
        post(JSON.stringify({media: false}));
        lastState = '';
    }, true);
    window.addEventListener('pageshow', function(e) {
        if (e.persisted) report();
    }, true);
})();
