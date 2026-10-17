console.log("TV Bro background extension loaded");

let requests = new Map();
let tvBroPort = browser.runtime.connectNative("tvbro_bg");

tvBroPort.onMessage.addListener(response => {
    //console.log("Received: " + JSON.stringify(response));
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