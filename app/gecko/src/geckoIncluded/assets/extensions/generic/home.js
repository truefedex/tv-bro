console.log("TV Bro home content extension loaded");

const homeExtPort = browser.runtime.connectNative("tvbro");
function postMessageToHomePagePort(action, data) {
    //console.log("Sending message to native app: " + action);
    homeExtPort.postMessage({ action: action, data: data });
}

let TVBro = {
    startVoiceSearch: function () {
        postMessageToHomePagePort("startVoiceSearch");
    },
    setSearchEngine: function (engine, customSearchEngineURL) {
        postMessageToHomePagePort("setSearchEngine", { engine: engine, customSearchEngineURL: customSearchEngineURL });
    },
    onEditBookmark: function (bookmark) {
        postMessageToHomePagePort("onEditBookmark", bookmark);
    },
    onHomePageLoaded: function (searchBarX, searchBarY) {
        postMessageToHomePagePort("onHomePageLoaded", { searchBarX: searchBarX, searchBarY: searchBarY });
    },
    requestFavicon: function (url) {
        postMessageToHomePagePort("requestFavicon", url);
    },
    markBookmarkRecommendationAsUseful: function (bookmarkIndex) {
        postMessageToHomePagePort("markBookmarkRecommendationAsUseful", bookmarkIndex);
    }
}
window.wrappedJSObject.TVBro = cloneInto(
    TVBro,
    window,
    { cloneFunctions: true });

//calls a function of the page
function callPage(name, ...args) {
    let fn = window.wrappedJSObject[name];
    if (fn) {
        fn(...args.map(arg => (arg !== null && typeof arg === "object") ? cloneInto(arg, window) : arg));
    }
}

homeExtPort.onMessage.addListener(message => {
    switch (message.action) {
        case "renderLinks":
            //an empty array may come as null
            callPage("renderLinks", message.data.mode, message.data.links || []);
            break;
        case "applySearchEngine":
            callPage("applySearchEngine", message.data.engine, message.data.url);
            break;
        case "hideVoiceSearchUI":
            callPage("hideVoiceSearchUI");
            break;
        case "favicon": {
            let favicon = message.data;
            if (window.wrappedJSObject.onFaviconLoaded) {
                window.wrappedJSObject.onFaviconLoaded(favicon.url, favicon.data);
            }
        }
    }
});