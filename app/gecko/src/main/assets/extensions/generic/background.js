// The background script of the extension: ad blocking of requests (webRequest.onBeforeRequest).
// It decides itself with adblock_engine.js (loaded before it) once it has the request filters of the app and
// its site settings (AppWebExtensionBackgroundPortDelegate sends them; the filters are kept in IndexedDB, so
// the app sends them again only when they change). Until then it holds each request until the app tells
// whether it is an ad, or the timeout passes.
console.log("TV Bro background extension loaded");

const RESOLVE_TIMEOUT_MS = 1500;
// The requests waiting for the app, by id: { resolve, started, timer }
const pending = new Map();
const tvBroPort = browser.runtime.connectNative("tvbro_bg");
// Main frames are sent to the app only when its filters may block pages ("config" message)
let checkPages = false;

// The engine of the filters, and whether the app said they are its current ones
let engine = null;
let engineConfirmed = false;
// TVBroAdblock.SiteSettings from the app; null until it sends them
let siteSettings = null;
// A transfer of filters in progress: { fingerprint, count, parts, started }
let incoming = null;
const pslText = fetch(browser.runtime.getURL("psl.txt")).then(response => response.text());

// The answer times of the app, logged every STATS_EVERY answers
const STATS_EVERY = 200;
let answerTimes = [];
let timeouts = 0;
// Decisions of the script, and the requests to time them again in a loop (performance.now() is coarse)
const LOCAL_STATS_EVERY = 200;
let localDecisions = 0;
let localErrors = 0;
let portRequests = 0;
let recent = [];

function recordAnswer(ms) {
    answerTimes.push(ms);
    if (answerTimes.length < STATS_EVERY) return;
    const sorted = answerTimes.sort((a, b) => a - b);
    const at = (p) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))].toFixed(1);
    console.log(`TV Bro ad blocking: ${sorted.length} answers in ms p50 ${at(0.5)} p90 ${at(0.9)} ` +
        `p99 ${at(0.99)} max ${sorted[sorted.length - 1].toFixed(1)}, timeouts so far ${timeouts}`);
    answerTimes = [];
}

function recordLocal(request) {
    localDecisions++;
    if (recent.length < LOCAL_STATS_EVERY) recent.push(request);
    if (localDecisions % LOCAL_STATS_EVERY !== 0) return;
    //the best of a few passes: pages load on the same thread meanwhile
    let best = Infinity;
    for (let pass = 0; pass < 5; pass++) {
        const started = performance.now();
        for (const r of recent) TVBroAdblock.shouldBlock(engine, siteSettings, r[0], r[1], r[2], r[3]);
        best = Math.min(best, performance.now() - started);
    }
    const micros = best * 1000 / recent.length;
    console.log(`TV Bro ad blocking: ${localDecisions} decided by the script (${micros.toFixed(1)} µs each), ` +
        `${portRequests} asked the app, ${localErrors + engine.errors} errors`);
    recent = [];
}

// ---- the filters, kept in IndexedDB ----
function openStore() {
    return new Promise((resolve, reject) => {
        const request = indexedDB.open("tvbro-adblock", 1);
        request.onupgradeneeded = () => request.result.createObjectStore("engine");
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
    });
}

async function storeEngine(fingerprint, buffer) {
    try {
        const db = await openStore();
        const tx = db.transaction("engine", "readwrite");
        if (buffer) tx.objectStore("engine").put({ fingerprint: fingerprint, buffer: buffer }, "current");
        else tx.objectStore("engine").delete("current");
        await new Promise((resolve, reject) => {
            tx.oncomplete = resolve;
            tx.onerror = () => reject(tx.error);
        });
        db.close();
    } catch (e) {
        console.log("TV Bro ad blocking: can not keep the filters: " + e);
    }
}

async function loadStoredEngine() {
    try {
        const started = performance.now();
        const db = await openStore();
        const stored = await new Promise((resolve, reject) => {
            const request = db.transaction("engine").objectStore("engine").get("current");
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error);
        });
        db.close();
        if (!stored) return;
        const loaded = new TVBroAdblock.Engine(stored.buffer, new TVBroAdblock.PublicSuffixList(await pslText));
        if (engine === null) engine = loaded;
        console.log(`TV Bro ad blocking: kept filters loaded in ${(performance.now() - started).toFixed(0)} ms`);
    } catch (e) {
        console.log("TV Bro ad blocking: no kept filters: " + e);
    }
}

function tellApp() {
    tvBroPort.postMessage({ action: "scriptEngine", fingerprint: engine ? engine.fingerprint.toString() : "" });
}

function base64Bytes(data) {
    const binary = atob(data);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
}

async function onEngineChunk(message) {
    if (message.index === 0) incoming = { fingerprint: message.fingerprint, count: message.count, parts: [], started: performance.now() };
    if (!incoming || incoming.fingerprint !== message.fingerprint || incoming.parts.length !== message.index) return;
    incoming.parts.push(base64Bytes(message.data));
    tvBroPort.postMessage({ action: "engineChunkAck", fingerprint: message.fingerprint, index: message.index });
    if (incoming.parts.length < incoming.count) return;
    const done = incoming;
    incoming = null;
    const size = done.parts.reduce((sum, part) => sum + part.length, 0);
    const bytes = new Uint8Array(size);
    let offset = 0;
    for (const part of done.parts) {
        bytes.set(part, offset);
        offset += part.length;
    }
    try {
        const built = performance.now();
        engine = new TVBroAdblock.Engine(bytes.buffer, new TVBroAdblock.PublicSuffixList(await pslText));
        engineConfirmed = true;
        console.log(`TV Bro ad blocking: filters received (${(size / 1024).toFixed(0)} KB) in ` +
            `${(built - done.started).toFixed(0)} ms, engine made in ${(performance.now() - built).toFixed(0)} ms`);
        tellApp();
        storeEngine(done.fingerprint, bytes.buffer);
    } catch (e) {
        console.log("TV Bro ad blocking: can not use the filters: " + e);
        tvBroPort.postMessage({ action: "scriptEngine", fingerprint: engine ? engine.fingerprint.toString() : "",
            failed: true, failedFingerprint: done.fingerprint });
    }
}

tvBroPort.onMessage.addListener(response => {
    switch (response.action) {
        case "config":
            checkPages = !!response.checkPages;
            return;
        case "siteSettings":
            siteSettings = new TVBroAdblock.SiteSettings(response.enabled, response.hosts, response.homePageUrl);
            return;
        case "engineCurrent":
            engineConfirmed = engine !== null && engine.fingerprint.toString() === response.fingerprint;
            return;
        case "engineNone":
            engine = null;
            engineConfirmed = false;
            storeEngine(null, null);
            return;
        case "engineChunk":
            onEngineChunk(response);
            return;
        case "onResolveRequest":
            break;
        default:
            return;
    }
    const id = String(response.data.requestId);
    const request = pending.get(id);
    //answered after the timeout
    if (!request) return;
    pending.delete(id);
    clearTimeout(request.timer);
    recordAnswer(performance.now() - request.started);
    request.resolve({ cancel: response.data.block });
    if (response.data.block) tellBlocked(request.tabId, request.url);
});

loadStoredEngine().then(tellApp);

/** The content script of the top frame of the tab counts it (none on some pages: nothing to count) */
function tellBlocked(tabId, url) {
    if (tabId >= 0) browser.tabs.sendMessage(tabId, { blockedUrl: url }, { frameId: 0 }).catch(() => {});
}

function topUrlOf(details) {
    if (details.type === "main_frame") return details.url;
    const ancestors = details.frameAncestors;
    if (ancestors && ancestors.length > 0) return ancestors[ancestors.length - 1].url;
    return details.documentUrl || details.originUrl;
}

browser.webRequest.onBeforeRequest.addListener(
    function (details) {
        //a request the user started (the address bar) has no origin
        const documentUrl = details.documentUrl || details.originUrl || null;
        const topUrl = topUrlOf(details);
        if (engine !== null && engineConfirmed && siteSettings !== null) {
            try {
                const block = TVBroAdblock.shouldBlock(engine, siteSettings, details.url, details.type, documentUrl, topUrl);
                recordLocal([details.url, details.type, documentUrl, topUrl]);
                if (block) tellBlocked(details.tabId, details.url);
                return { cancel: block };
            } catch (e) {
                //the app decides this one
                localErrors++;
            }
        }
        if (details.type === "main_frame" && !checkPages) return;
        portRequests++;
        const id = String(details.requestId);
        return new Promise(resolve => {
            const timer = setTimeout(() => {
                if (!pending.delete(id)) return;
                timeouts++;
                console.log("TV Bro ad blocking: no answer in time for " + details.url);
                resolve({ cancel: false });
            }, RESOLVE_TIMEOUT_MS);
            pending.set(id, { resolve: resolve, started: performance.now(), timer: timer, tabId: details.tabId,
                url: details.url });
            tvBroPort.postMessage({
                action: "onBeforeRequest",
                details: {
                    requestId: details.requestId,
                    url: details.url,
                    type: details.type,
                    originUrl: details.originUrl,
                    documentUrl: details.documentUrl,
                    // the page of the tab, whose site settings apply (the last ancestor of a frame)
                    topUrl: topUrl
                }
            });
        });
    },
    { urls: ["<all_urls>"] },
    ["blocking"]
);
