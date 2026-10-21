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

// context menu support
window.addEventListener("touchstart", function(e) {
    window.TVBRO_activeElement = e.target;
    window.TVBRO_touchStartX = e.touches[0].clientX;
    window.TVBRO_touchStartY = e.touches[0].clientY;
});
// the virtual cursor is a mouse for pages in the desktop view: its long press touches nothing
window.addEventListener("mousemove", function(e) {
    window.TVBRO_activeElement = e.target;
}, {capture: true, passive: true});