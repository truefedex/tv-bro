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

// context menu support
window.addEventListener("touchstart", function(e) {
    window.TVBRO_activeElement = e.target;
    window.TVBRO_touchStartX = e.touches[0].clientX;
    window.TVBRO_touchStartY = e.touches[0].clientY;
});

// text selection support
function TVBRO_updateSelection(x, y, w, h) {
  let pageX = x * (window.innerWidth / window.visualViewport.scale / w) + window.visualViewport.offsetLeft;
  let pageY = y * (window.innerHeight / window.visualViewport.scale / h) + window.visualViewport.offsetTop;

  if (document.caretRangeFromPoint) {
    let caretRange = document.caretRangeFromPoint(pageX, pageY);
    let node = caretRange.startContainer;
    let offset = caretRange.startOffset;

    let selection = window.getSelection();
    if (selection.anchorNode === null || selection.anchorNode === undefined) {
        selection.setPosition(node, offset);
    } else {
        selection.extend(node, offset);
    }
  }
}

function TVBRO_clearSelection() {
    let selection = window.getSelection();
    selection.removeAllRanges();
}

function TVBRO_processSelection() {
    let selection = window.getSelection();
    let selectedText = selection.toString().trim();
    let editable = false;
    if (selection.anchorNode) {
        let node = selection.anchorNode;
        while (node) {
            if (node.isContentEditable) {
                editable = true;
                break;
            }
            node = node.parentNode;
        }
    }
    return JSON.stringify({selectedText: selectedText, editable: editable});
}