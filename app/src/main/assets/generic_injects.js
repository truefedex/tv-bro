//download blobs support
if (!window.tvBroClicksListener) {
    window.tvBroClicksListener = function(e) {
        if (e.target.tagName.toUpperCase() == "A" && e.target.attributes.href.value.toLowerCase().startsWith("blob:")) {
            var fileName = e.target.download;
            var url = e.target.attributes.href.value;
            var xhr=new XMLHttpRequest();
            xhr.open('GET', e.target.attributes.href.value, true);
            xhr.responseType = 'blob';
            xhr.onload = function(e) {
                if (this.status == 200) {
                    var blob = this.response;
                    var reader = new FileReader();
                    reader.readAsDataURL(blob);
                    reader.onloadend = function() {
                        base64data = reader.result;
                        TVBro.takeBlobDownloadData(base64data, fileName, url, blob.type);
                    }
                }
            };
            xhr.send();
            e.stopPropagation();
            e.preventDefault();
        }
    };
    document.addEventListener("click", window.tvBroClicksListener);
}

// video playback control support
Object.defineProperty(HTMLMediaElement.prototype, 'playing', {
    get: function(){
        return !!(this.currentTime > 0 && !this.paused && !this.ended && this.readyState > 2);
    }
})

window.tvBroTogglePlayback = function() {
  var media = document.querySelector('video') || document.querySelector('audio');
  if (media) {
      if (media.playing) {
        media.pause();
      } else {
        media.play();
      }
  }
}

window.tvBroStopPlayback = function() {
  var media = document.querySelector('video') || document.querySelector('audio');
  if (media) {
      media.pause();
      media.currentTime = 0;
  }
}

window.tvBroRewind = function() {
    var media = document.querySelector('video') || document.querySelector('audio');
    if (media) {
        media.currentTime -= 10;
    }
}

window.tvBroFastForward = function() {
    var media = document.querySelector('video') || document.querySelector('audio');
    if (media) {
        media.currentTime += 10;
    }
}

window.tvBroCastCurrentVideo = function() {
    var v = document.querySelector('video');
    var url = '';
    if (v) {
        url = v.currentSrc || v.src || '';
        if (!url || url.indexOf('blob:') === 0) {
            var s = v.querySelector('source');
            if (s && s.src) url = s.src;
        }
        try { v.pause(); } catch (e) {}
    }
    if (url && url.indexOf('blob:') === 0) { url = ''; }
    // Pass the <video> source to the app. It is combined there with the streams
    // sniffed at the network layer (needed for cross-origin iframe players,
    // whose DOM is not reachable from here). blob: URLs are useless, send empty.
    TVBro.castMedia(url || '');
}

// Reports the video that went fullscreen so the app can hand it over to the
// native player. Returns "1|<positionMs>|<url>" when the fullscreen element
// is/contains a media element (or is an iframe, whose cross-origin DOM we
// cannot read), and "0|" otherwise. The url is empty for MSE/blob: sources;
// the app then falls back to the streams it sniffed at the network layer. The
// page video is paused so it does not keep playing behind the native player.
window.tvBroFullscreenVideo = function() {
    var el = document.fullscreenElement
        || document.webkitFullscreenElement
        || document.msFullscreenElement
        || document.webkitCurrentFullScreenElement;
    if (!el) return '0|';
    var media = null;
    if (el.tagName === 'VIDEO' || el.tagName === 'AUDIO') {
        media = el;
    } else if (el.querySelector) {
        media = el.querySelector('video, audio');
    }
    if (!media) {
        return el.tagName === 'IFRAME' ? '1|0|' : '0|';
    }
    var url = media.currentSrc || media.src || '';
    if (!url) {
        var source = media.querySelector('source');
        if (source && source.src) url = source.src;
    }
    if (url.indexOf('blob:') === 0) url = '';
    var positionMs = 0;
    try {
        positionMs = Math.max(0, Math.round((media.currentTime || 0) * 1000));
        media.pause();
    } catch (e) {}
    return '1|' + positionMs + '|' + url;
};

// context menu support
window.addEventListener("touchstart", function(e) {
    window.TVBRO_activeElement = e.target;
    window.TVBRO_touchStartX = e.touches[0].clientX;
    window.TVBRO_touchStartY = e.touches[0].clientY;
});