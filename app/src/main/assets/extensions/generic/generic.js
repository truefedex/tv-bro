console.log("TV Bro generic content extension loaded");

const port = browser.runtime.connectNative("tvbro");
port.onMessage.addListener(message => {
    console.log("Received message from native app: " + message.action);
    switch (message.action) {
        case "zoomIn": zoomIn(); break;
        case "zoomOut": zoomOut(); break;
    }
});
//port.postMessage("Hello from WebExtension!");

function zoomIn() {
    //browser.runtime.sendMessage({ action: "zoomIn" });
    var currentZoom = 1;
    if (typeof window.TVBroZoom !== 'undefined') {
        currentZoom = window.TVBroZoom;
    } else {
        window.TVBroZoom = 1;
    }
    if (currentZoom < 10) {
        currentZoom = currentZoom + 0.1;
        window.TVBroZoom = currentZoom;
    }
    document.body.style.MozTransform = "scale(" + currentZoom + ")";
}

function zoomOut() {
    var currentZoom = 1;
    if (typeof window.TVBroZoom !== 'undefined') {
        currentZoom = window.TVBroZoom;
    } else {
        window.TVBroZoom = 1;
    }
    if (currentZoom > 0.1) {
        currentZoom = currentZoom - 0.1;
        window.TVBroZoom = currentZoom;
    }
    document.body.style.MozTransform = "scale(" + currentZoom + ")";
}