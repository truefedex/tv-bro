var searchEngineUrlMap = {
        "google": "https://www.google.com/search?q=[query]",
        "bing": "https://www.bing.com/search?q=[query]",
        "yahoo": "https://search.yahoo.com/search?p=[query]",
        "ddg": "https://duckduckgo.com/?q=[query]",
        "yandex": "https://yandex.com/search/?text=[query]",
        "startpage": "https://www.startpage.com/sp/search?query=[query]",
        "custom": ""
    };
var recommendationsToDisplay = [];

//GeckoView shows this page as a page of TV Bro's extension, which talks to the app through the extension
//messaging (WebView adds TVBro to the page itself)
if (!window.TVBro && typeof browser !== "undefined" && browser.runtime && browser.runtime.connectNative) {
    var appPort = browser.runtime.connectNative("tvbro");
    var postToApp = function (action, data) {
        appPort.postMessage({ action: action, data: data });
    };
    window.TVBro = {
        startVoiceSearch: function () { postToApp("startVoiceSearch"); },
        setSearchEngine: function (engine, customSearchEngineURL) {
            postToApp("setSearchEngine", { engine: engine, customSearchEngineURL: customSearchEngineURL });
        },
        onEditBookmark: function (index) { postToApp("onEditBookmark", index); },
        onHomePageLoaded: function (searchBarX, searchBarY) {
            postToApp("onHomePageLoaded", { searchBarX: searchBarX, searchBarY: searchBarY });
        },
        requestFavicon: function (url) { postToApp("requestFavicon", url); },
        markBookmarkRecommendationAsUseful: function (index) { postToApp("markBookmarkRecommendationAsUseful", index); }
    };
    appPort.onMessage.addListener(function (message) {
        switch (message.action) {
            case "renderLinks":
                //an empty array may come as null
                renderLinks(message.data.mode, message.data.links || []);
                break;
            case "applySearchEngine":
                applySearchEngine(message.data.engine, message.data.url);
                break;
            case "hideVoiceSearchUI":
                hideVoiceSearchUI();
                break;
            case "favicon":
                onFaviconLoaded(message.data.url, message.data.data);
                break;
        }
    });
}
var homePageLinksMode = "RECOMMENDATIONS";

function onIcoLoadError(element) {
    element.src='ic_not_available.svg';
    element.style.filter = "invert(1)";
}

function onSuggestionClicked(index) {
    var bookmark = null;
    if (homePageLinksMode === "BOOKMARKS" && window.hasOwnProperty( "TVBro" )) {
        bookmark = recommendationsToDisplay.find(function (b) { return b.order === index; });
        if (bookmark && TVBro.hasOwnProperty("markBookmarkRecommendationAsUseful")) {
            TVBro.markBookmarkRecommendationAsUseful(index);
        }
    } else {
        if (index >= recommendationsToDisplay.length) return;
        bookmark = recommendationsToDisplay[index];
    }
    if (!bookmark) return;
    var url = bookmark.dest_url ? bookmark.dest_url : bookmark.url;
    window.location.href = url;
}

function loadRecommendations() {
    return [
        {"title": "Wikipedia - the free encyclopedia", "url":"https://www.wikipedia.org", "favicon": "https://ru.wikipedia.org/static/apple-touch/wikipedia.png"},
        {"title": "Reddit - Dive into anything", "url":"https://www.reddit.com", "favicon": "https://www.redditstatic.com/desktop2x/img/favicon/apple-icon-120x120.png"},
        {"title": "My Tuner", "url":"https://mytuner-radio.com/", "favicon": "https://static2.mytuner.mobi/static/icons/apple-touch-icon-120x120-precomposed.png"},
        {"title": "Instagram", "url":"https://www.instagram.com", "favicon": "https://static.cdninstagram.com/rsrc.php/v3/ys/r/aM-g435MtEX.png"},
        {"title": "IMDb", "url":"https://www.imdb.com/", "favicon": "https://m.media-amazon.com/images/G/01/imdb/images-ANDW73HA/favicon_iPhone_retina_180x180._CB1582158069_UX60_.png"},
        {"title": "eBay", "url":"https://www.ebay.com/", "favicon": "https://www.ebay.com/apple-touch-icon.png"},
        {"title": "Itch.io", "url":"https://itch.io/games/input-gamepad/platform-web", "favicon": "https://itch.io/static/images/itchio-square-144.png"},
        {"title": "BBC News", "url":"https://www.bbc.com/news", "favicon": "https://www.bbc.com/apple-touch-icon-120x120-precomposed.png"}
    ];
}

function renderLinks(mode, linksArray) {
    //both parameters are optional
    console.log("Rendering links: mode=" + mode + ", linksArray=" + JSON.stringify(linksArray));
    homePageLinksMode = mode ? mode : "RECOMMENDATIONS";
    recommendationsToDisplay = linksArray ? linksArray : loadRecommendations();

    recommendationsToDisplay.map(function(c, i, arr) {
        if (!c.favicon) {
            var urlStr = c.url;
            try {
                var url = new URL(urlStr);
                c.favicon = url.origin + "/favicon.ico";

                if (window.hasOwnProperty( "TVBro" )) {
                    if (TVBro.requestFavicon) {
                        //GeckoView
                        TVBro.requestFavicon(urlStr);
                    } else {
                        //WebView
                        c.favicon = "favicon://" + url.host;
                    }                    
                }
            } catch (e) {
                console.log("Invalid URL: " + urlStr);
                console.log("Invalid URL: " + urlStr);
            }
        }
        return c;
    })
    
    console.log("Rendering links: recommendationsToDisplay=" + JSON.stringify(recommendationsToDisplay));
    
    for (var i = 0; i < 8; i++) {
        var suggElement = document.getElementById("sugg" + i);
        if (suggElement) {
            var link;
            if (homePageLinksMode == "BOOKMARKS" && window.hasOwnProperty( "TVBro" )) {
                link = recommendationsToDisplay.find(function(c) { return c.order == i; });
            } else {
                link = recommendationsToDisplay[i];
            }

            //console.log("Rendering link: " + link);

            var titleElement = suggElement.getElementsByClassName("sugg-title")[0];
            var descriptionElement = suggElement.getElementsByClassName("sugg-desk")[0];
            var icon = suggElement.getElementsByClassName("sugg-ico")[0];
            if (link) {
                icon.src = link.favicon;
                //color-scheme: only light;
                icon.style.filter = "";
                //titles come from the pages: text, not markup
                titleElement.textContent = link.title;
                descriptionElement.textContent = link.description ? link.description : link.url;
            } else {
                icon.src = "ic_not_available.svg";
                //color-scheme: default;
                icon.style.filter = "invert(1)";
                titleElement.textContent = "\u00a0";
                descriptionElement.textContent = "\u00a0";
            }

            titleElement.classList.toggle("empty", !link);
            descriptionElement.classList.toggle("empty", !link);

            if (homePageLinksMode == "BOOKMARKS" && window.hasOwnProperty( "TVBro" )) {
                var moreElement = suggElement.getElementsByClassName("more")[0]; 
                moreElement.style.display = "block";
                moreElement.onclick = function(e) {
                    var index = parseInt(e.target.parentElement.id.substring(4));
                    TVBro.onEditBookmark(index);
                    e.stopPropagation();
                };
            }
        }
    }
}

function onSearchEngineDropDownClick() {
    document.getElementById("search-engines-list").classList.toggle("active");
}

function promptCustomSearchEngineUrl() {
    var engine = localStorage.getItem("search-engine");
    var searchEngineUrl = localStorage.getItem("search-engine-url");
    searchEngineUrl = searchEngineUrl ? searchEngineUrl : searchEngineUrlMap[engine];

    return prompt("Enter search engine URL (with [query] as placeholder)", searchEngineUrl);
}

function applySearchEngine(engine, searchEngineUrl, notifyTVBro) {
    document.getElementById("search-engines-list").classList.remove("active");

    searchEngineUrl = searchEngineUrl ? searchEngineUrl : searchEngineUrlMap[engine];

    localStorage.setItem("search-engine", engine);
    localStorage.setItem("search-engine-url", searchEngineUrl);

    var iconUrl = "";
    if (engine == "custom") {
        var urlOrigin = new URL(searchEngineUrl.replace("[query]", "")).origin;
        iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&size=120&url=" + urlOrigin;
    } else {
        iconUrl = "favicons/" + engine + ".png";
    }
    document.getElementsByClassName("default_option")[0].style.backgroundImage = "url('" + iconUrl + "')";    

    if (window.hasOwnProperty( "TVBro" ) && notifyTVBro) {
        TVBro.setSearchEngine(engine, searchEngineUrl ? searchEngineUrl : searchEngineUrlMap[engine]);
    }
}

function performSearch(text) {
    var searchEngineUrl = localStorage.getItem('search-engine-url') ? localStorage.getItem('search-engine-url') : searchEngineUrlMap['google'];
    var url = searchEngineUrl.replace("[query]", encodeURIComponent(text));
    window.open(url, "_self");
}

//callback called from TVBro/GeckoView
function onFaviconLoaded(url, data) {
    let imgElements = document.getElementsByTagName("img");
    for (let i = 0; i < imgElements.length; i++) {
        if (imgElements[i].src == "favicon://" + url) {
            imgElements[i].src = data;
        }
    }
}

function hideVoiceSearchUI() {
    document.getElementsByClassName("mic")[0].style.display = "none";
    document.getElementsByClassName("lenz")[0].style.display = "block";
}

//initialization

var searchEngine = localStorage.getItem('search-engine') ? localStorage.getItem('search-engine') : "google";
var searchEngineUrl = localStorage.getItem('search-engine-url') ? localStorage.getItem('search-engine-url') : searchEngineUrlMap[searchEngine];

//the page has no inline handlers: TV Bro also shows it as a page of its GeckoView extension,
//where only scripts from files run
for (var i = 0; i < 8; i++) {
    (function (index) {
        var suggElement = document.getElementById("sugg" + index);
        suggElement.parentElement.addEventListener("click", function () { onSuggestionClicked(index); });
        suggElement.getElementsByClassName("sugg-ico")[0].addEventListener("error", function (e) {
            onIcoLoadError(e.target);
        });
    })(i);
}
document.getElementsByClassName("default_option")[0].addEventListener("click", onSearchEngineDropDownClick);
var engineItems = document.querySelectorAll("#search-engines-list li");
for (var j = 0; j < engineItems.length; j++) {
    engineItems[j].addEventListener("click", function (e) {
        var engine = e.currentTarget.getAttribute("data-engine");
        applySearchEngine(engine, engine == "custom" ? promptCustomSearchEngineUrl() : null, true);
    });
}

document.getElementById("search-input").addEventListener("keydown", function(e) {
    if (e.keyCode == 13) {
        performSearch(document.getElementById("search-input").value);
    }
});

document.getElementsByClassName("search_action_container")[0].onclick = function() {
    if (document.getElementsByClassName("mic")[0].style.display == "none") {
        performSearch(document.getElementById("search-input").value);
    } else {
        if (window.hasOwnProperty( "TVBro" )) {
            TVBro.startVoiceSearch();
        }
    }
};

//there are three modes this page can run in:
//1. Running inside ordinary web page - TVBro variable is not installed - no bookmarks, no voice search
//2. Running inside TVBro WebView - TVBro variable is installed
//3. Running inside TVBro GeckoView - TVBro variable is installed

if (window.hasOwnProperty( "TVBro" )) {
    console.log("Running inside TVBro");
    //the center of the search bar in device pixels of the page view: the browser may put its cursor there
    var searchBar = document.getElementById("search-input").getBoundingClientRect();
    var ratio = window.devicePixelRatio || 1;
    TVBro.onHomePageLoaded(Math.round((searchBar.left + searchBar.width / 2) * ratio),
        Math.round((searchBar.top + searchBar.height / 2) * ratio));
} else {
    hideVoiceSearchUI();
    applySearchEngine(searchEngine, searchEngineUrl, false);
    renderLinks();
}

