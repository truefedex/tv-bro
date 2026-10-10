console.log("TV Bro background extension loaded");

let requests = new Map();
let tvBroPort = browser.runtime.connectNative("tvbro_bg");

tvBroPort.onMessage.addListener(response => {
    //console.log("Received: " + JSON.stringify(response));
    if (response.action === 'activeMediaTab') {
        browser.tabs.query({active: true}).then(tabs => {
            const tab = tabs.length === 1 ? tabs[0] : null;
            tvBroPort.postMessage({action: 'activeMediaTab', requestId: response.requestId,
                tab: tab ? {id: tab.id, url: tab.url, incognito: !!tab.incognito} : null});
        }).catch(() => tvBroPort.postMessage({action:'activeMediaTab', requestId:response.requestId, tab:null}));
        return;
    }
    if (response.action === "onResolveRequest") {
        let id = response.data.requestId;
        let block = response.data.block;
        //console.log("Requests contents: " + JSON.stringify(Array.from(requests.keys())));
        if (requests.has(id.toString())) {
            let request = requests.get(id.toString());
            //console.log("Request resolved id: " + id);
            requests.delete(id.toString());
            //console.log("Requests size: " + requests.size);
            request.resolverRef({ cancel: block });
        }
    }
});
//tvBroPort.postMessage("Hello from WebExtension!");

browser.webRequest.onBeforeRequest.addListener(
    function (details) {
        // App-owned fetches have no tab and must not wait on a page's ad-blocking callback.
        if (details.tabId < 0) return {};
        let id = details.requestId;
        let resolverRef = null;
        let promise = new Promise((resolve, reject) => {
            resolverRef = resolve;
            setTimeout(() => {
                if (requests.has(id)) {
                    let request = requests.get(id);
                    let time = new Date() - request.time;
                    //console.log("Request block timeout id: " + id);
                    requests.delete(id);
                    resolve({ cancel: false });
                } else {
                    //console.log("Request processed by blocking rules id: " + id);
                }
            }, 1500);
        });
        requests.set(id, {
            details: details,
            time: new Date(),
            promise: promise,
            resolverRef: resolverRef
        });

        //console.log('onBeforeRequest url: ' + details.url);
        tvBroPort.postMessage({ action: "onBeforeRequest", details: details });
        return promise;
    },
    { urls: ["<all_urls>"] },
    ["blocking"]
);

// Read-only network observations. No content script, response filter, or page changes.
const mediaRequestHeaders = new Map();
browser.webRequest.onBeforeSendHeaders.addListener(details => {
    if (details.tabId < 0 || details.method !== 'GET') return;
    const headers = {};
    let budget = 16384;
    for (const header of (details.requestHeaders || []).slice(0, 32)) {
        if (header.name.length <= 128 && header.value && header.value.length <= Math.min(8192, budget)) {
            headers[header.name] = header.value;
            budget -= header.value.length;
        }
    }
    mediaRequestHeaders.set(details.requestId, headers);
    while (mediaRequestHeaders.size > 128) mediaRequestHeaders.delete(mediaRequestHeaders.keys().next().value);
}, {urls: ['http://*/*', 'https://*/*']}, ['requestHeaders']);

browser.webRequest.onHeadersReceived.addListener(async details => {
    if (details.tabId < 0 || details.method !== 'GET' || details.statusCode >= 400) return;
    const mime = (details.responseHeaders || []).find(h => h.name.toLowerCase() === 'content-type')?.value || '';
    const pathname = new URL(details.url).pathname;
    if (!/^(video\/|audio\/|text\/vtt|application\/(vnd\.apple\.mpegurl|x-mpegurl|dash\+xml|ttml\+xml))/i.test(mime) &&
        !/\.(mp4|webm|m3u8|mpd|m4v|ts)$/i.test(pathname)) return;
    const headers = mediaRequestHeaders.get(details.requestId) || {};
    try {
        const tab = await browser.tabs.get(details.tabId);
        tvBroPort.postMessage({action: 'mediaResponse', details: {
            url: details.url, page: tab.url,
            frame: details.documentUrl || details.originUrl || tab.url,
            privateMode: !!tab.incognito, mime, headers, tabId: details.tabId
        }});
    } catch (_) { /* Tab closed; do not retain its request context. */ }
}, {urls: ['http://*/*', 'https://*/*']}, ['responseHeaders']);

const discardHeaders = details => mediaRequestHeaders.delete(details.requestId);
browser.webRequest.onCompleted.addListener(discardHeaders, {urls: ['http://*/*', 'https://*/*']});
browser.webRequest.onErrorOccurred.addListener(discardHeaders, {urls: ['http://*/*', 'https://*/*']});
browser.tabs.onUpdated.addListener((tabId, change) => {
    if (change.url) tvBroPort.postMessage({action:'mediaNavigation', tabId, url:change.url});
});