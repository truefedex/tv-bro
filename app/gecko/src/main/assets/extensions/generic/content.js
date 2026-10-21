// The content script of every frame. It reports the videos of the frame that other apps can play
// (page_videos.js, copied here by the build and run before this script) to AppContentScriptPortDelegate.

const communicatePort = browser.runtime.connectNative("tvbro_content");

communicatePort.onMessage.addListener(message => {
    console.log("Received message from native app:", message);
});

tvBroWatchPageVideos(message => communicatePort.postMessage(message));
