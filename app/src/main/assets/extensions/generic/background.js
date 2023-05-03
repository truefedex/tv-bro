console.log("TV Bro background extension loaded");

//listen for messages from the content script
browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
    console.log("Received message from content script: " + message.action);
    switch (message.action) {
        case "zoomIn": zoomIn(); break;
        case "zoomOut": zoomOut(); break;
    }
});

async function zoomIn() {
    // get the active tab
    let tabs = await browser.tabs.query({ });
    console.log("tabs: " + tabs);
    for (const tab of tabs) {
        // tab.url requires the `tabs` permission or a matching host permission.
        console.log(JSON.stringify(tab));
      }
    let tab = tabs[0];
    // array of default zoom levels
    let zoomLevels = [.3, .5, .67, .8, .9, 1, 1.1, 1.2, 1.33, 1.5, 1.7, 2, 2.4, 3];
    // maximal zoom level
    let maxZoom = 3, newZoom;

    //const currentZoom = await browser.tabs.getZoom(tab.id);

    //newZoom = zoomLevels.reduce((acc, cur) => cur > currentZoom && cur < acc ? cur : acc, maxZoom);

    if (/*newZoom > currentZoom*/true) {
        await browser.tabs.setZoom(tab.id, 3/*newZoom*/);
        // confirm success
        return true;
    }

    return false;
}