// The content script of every frame. It reports to AppContentScriptPortDelegate the state of the media of
// the frame (media_controls.js) and runs the commands of the app, and reports the videos of the frame that
// other apps can play (page_videos.js); the build copies both here, they run before this script.

const communicatePort = browser.runtime.connectNative("tvbro_content");
const send = message => communicatePort.postMessage(message);
const mediaCommand = tvBroWatchMedia(send);

communicatePort.onMessage.addListener(message => {
    if (message && message.command) mediaCommand(message.command);
});

tvBroWatchPageVideos(send);

// The background script tells the top frame of the tab what it blocked, for the count of the site panel
browser.runtime.onMessage.addListener(message => {
    if (message && message.blockedUrl) send(JSON.stringify({ blockedUrl: message.blockedUrl }));
});
