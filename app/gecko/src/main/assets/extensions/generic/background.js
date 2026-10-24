// The background script of the extension. It holds each request until the app tells whether it is an ad
// (AppWebExtensionBackgroundPortDelegate) or the timeout passes.
console.log("TV Bro background extension loaded");

const RESOLVE_TIMEOUT_MS = 1500;
// The requests waiting for the app, by id: { resolve, started, timer }
const pending = new Map();
const tvBroPort = browser.runtime.connectNative("tvbro_bg");

// The answer times of the app, logged every STATS_EVERY answers (shown with the engine debugging on)
const STATS_EVERY = 200;
let answerTimes = [];
let timeouts = 0;

function recordAnswer(ms) {
    answerTimes.push(ms);
    if (answerTimes.length < STATS_EVERY) return;
    const sorted = answerTimes.sort((a, b) => a - b);
    const at = (p) => sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))].toFixed(1);
    console.log(`TV Bro ad blocking: ${sorted.length} answers in ms p50 ${at(0.5)} p90 ${at(0.9)} ` +
        `p99 ${at(0.99)} max ${sorted[sorted.length - 1].toFixed(1)}, timeouts so far ${timeouts}`);
    answerTimes = [];
}

tvBroPort.onMessage.addListener(response => {
    if (response.action !== "onResolveRequest") return;
    const id = String(response.data.requestId);
    const request = pending.get(id);
    //answered after the timeout
    if (!request) return;
    pending.delete(id);
    clearTimeout(request.timer);
    recordAnswer(performance.now() - request.started);
    request.resolve({ cancel: response.data.block });
});

browser.webRequest.onBeforeRequest.addListener(
    function (details) {
        //pages are not blocked, only what they load
        if (details.type === "main_frame") return;
        const id = String(details.requestId);
        return new Promise(resolve => {
            const timer = setTimeout(() => {
                if (!pending.delete(id)) return;
                timeouts++;
                console.log("TV Bro ad blocking: no answer in time for " + details.url);
                resolve({ cancel: false });
            }, RESOLVE_TIMEOUT_MS);
            pending.set(id, { resolve: resolve, started: performance.now(), timer: timer });
            tvBroPort.postMessage({
                action: "onBeforeRequest",
                details: {
                    requestId: details.requestId,
                    url: details.url,
                    type: details.type,
                    originUrl: details.originUrl,
                    documentUrl: details.documentUrl
                }
            });
        });
    },
    { urls: ["<all_urls>"] },
    ["blocking"]
);
