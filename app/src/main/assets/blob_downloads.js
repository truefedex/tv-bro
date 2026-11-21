// WebView: gives the app the contents of blob: downloads (WebViewBlobDownloads). WebView tells the app only the
// address of a download, and a blob: address exists only in the page, so the frame reads the blob for the app,
// a chunk at a time, through the tvBroBlobs object the app adds to every frame. A document start script.
(function() {
    var channel = window.tvBroBlobs;
    if (!channel || channel.tvBroStarted) return;
    channel.tvBroStarted = true;
    // pages often revoke the address right after the click, long before the app asks for it: the blobs of the
    // addresses are kept until a while after they are revoked
    var KEEP_REVOKED_MS = 30000;
    var blobs = new Map(); // address -> {blob, name}
    var transfers = new Map(); // id of the app -> blob
    var create = URL.createObjectURL, revoke = URL.revokeObjectURL;
    try {
        URL.createObjectURL = function(object) {
            var url = create.apply(this, arguments);
            if (object instanceof Blob) blobs.set(url, {blob: object, name: object.name || null});
            return url;
        };
        URL.revokeObjectURL = function(url) {
            var entry = blobs.get(url);
            if (entry) setTimeout(function() { if (blobs.get(url) === entry) blobs.delete(url); }, KEEP_REVOKED_MS);
            return revoke.apply(this, arguments);
        };
    } catch (e) {}

    // the name the page gives a download (the download attribute) reaches the app only from here
    function noteName(a) {
        try {
            if (!a || !a.href || a.href.lastIndexOf("blob:", 0) !== 0 || !a.hasAttribute("download")) return;
            var entry = blobs.get(a.href);
            var name = a.getAttribute("download");
            if (entry && name) entry.name = name;
            else if (name) blobs.set(a.href, {blob: null, name: name});
        } catch (e) {}
    }
    window.addEventListener("click", function(e) {
        var node = e.target;
        while (node && node.tagName && node.tagName.toUpperCase() !== "A") node = node.parentNode;
        if (node && node.tagName) noteName(node);
    }, true);
    try {
        var click = HTMLAnchorElement.prototype.click;
        HTMLAnchorElement.prototype.click = function() {
            noteName(this);
            return click.apply(this, arguments);
        };
    } catch (e) {}

    function reply(message) { channel.postMessage(JSON.stringify(message)); }

    function start(id, url) {
        var entry = blobs.get(url);
        var name = entry ? entry.name : null;
        var got = entry && entry.blob ? Promise.resolve(entry.blob) : fetch(url).then(function(r) {
            if (!r.ok) throw new Error("HTTP " + r.status);
            return r.blob();
        });
        got.then(function(blob) {
            transfers.set(id, blob);
            reply({id: id, size: blob.size, type: blob.type || null, name: name});
        }, function(e) {
            reply({id: id, error: String(e && e.message || e)});
        });
    }

    function chunk(id, offset, length, binary) {
        var blob = transfers.get(id);
        if (!blob) return reply({id: id, error: "no transfer"});
        var part = blob.slice(offset, offset + length);
        if (binary) {
            part.arrayBuffer().then(function(buffer) {
                // the id first: a binary message has nothing else to tell which transfer it is of
                var out = new Uint8Array(4 + buffer.byteLength);
                new DataView(out.buffer).setInt32(0, id);
                out.set(new Uint8Array(buffer), 4);
                channel.postMessage(out.buffer);
            }, function(e) { reply({id: id, error: String(e && e.message || e)}); });
        } else {
            var reader = new FileReader();
            reader.onload = function() {
                var data = reader.result;
                reply({id: id, data: data.substring(data.indexOf(",") + 1)});
            };
            reader.onerror = function() { reply({id: id, error: String(reader.error)}); };
            reader.readAsDataURL(part);
        }
    }

    channel.onmessage = function(e) {
        var m;
        try { m = JSON.parse(e.data); } catch (ex) { return; }
        if (m.start !== undefined) start(m.start, m.url);
        else if (m.chunk !== undefined) chunk(m.chunk, m.offset, m.length, m.binary);
        else if (m.end !== undefined) transfers.delete(m.end);
    };
    // the app asks the frames that said hello, those of the origin of the address
    channel.postMessage("hello");
})();
