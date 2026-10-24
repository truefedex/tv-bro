// The content script of every frame. It reports to AppContentScriptPortDelegate the state of the media of
// the frame (media_controls.js) and runs the commands of the app, reports the videos of the frame that
// other apps can play (page_videos.js), and hides the elements of ads (element_hiding.js); the build copies
// them here, they run before this script.

const communicatePort = browser.runtime.connectNative("tvbro_content");
const send = message => communicatePort.postMessage(message);
const mediaCommand = tvBroWatchMedia(send);

// The element hiding asks the app over the port; the answers come back by the id of the request
const hidingCallbacks = new Map();
let hidingRequests = 0;

function askHiding(message, callback) {
    const id = ++hidingRequests;
    // the top frame is the page of the tab: the url of the engine may still be the previous page
    message.top = window === window.top;
    hidingCallbacks.set(id, callback);
    message.hidingRequest = id;
    send(JSON.stringify(message));
}

communicatePort.onMessage.addListener(message => {
    if (!message) return;
    if (message.hidingReply) {
        const callback = hidingCallbacks.get(message.hidingReply);
        hidingCallbacks.delete(message.hidingReply);
        if (callback) callback(message);
        return;
    }
    if (message.command) mediaCommand(message.command);
});

tvBroHideElements({
    styles: callback => askHiding({ hidingStyles: location.href }, reply => callback(reply)),
    generic: (classes, ids, callback) =>
        askHiding({ genericHiding: location.href, classes: classes, ids: ids }, reply => callback(reply.css))
});

tvBroWatchPageVideos(send);

// The background script tells the top frame of the tab what it blocked, for the count of the site panel
browser.runtime.onMessage.addListener(message => {
    if (message && message.blockedUrl) send(JSON.stringify({ blockedUrl: message.blockedUrl }));
});
