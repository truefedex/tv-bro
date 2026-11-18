// The zoom of the page and the width of its desktop view, for both engines (the GeckoView build copies it into
// its extension), in the top frame only. Engines zoom pages only as a pinch does, which stops where the page
// fits the screen: a page made for the width of the device can not get smaller. This script widens the layout
// of the page instead, as the zoom of a desktop browser does: it writes the width of its viewport (the content
// of its <meta name="viewport">, or a meta of its own for a page without one), and the engine fits that width
// into the screen. The media queries of the page see the wider viewport, so sites show their wider layouts.
//
// tvBroPageZoom(settings) starts it with { zoom: percent, desktopWidth: CSS pixels or 0 } and returns
// { set(settings) } to change them on the page shown (both engines fit the new width without a reload) and
// { zoomedIn() }, whether the user zoomed in (a pinch) past the width of the page, and { visualZoom() }, that zoom
// (1 where the page fits the screen), or null in frames.
// desktopWidth is the least width of the desktop view (0 without it); screens about as wide as that (TVs)
// keep the widths of pages, as in the desktop view of Chrome.
// The content the page gives its metas is kept, so 100% without a desktop width gives it back.
function tvBroPageZoom(settings) {
    if (window !== window.top) return null;
    if (window.tvBroPageZoomApi) {
        window.tvBroPageZoomApi.set(settings);
        return window.tvBroPageZoomApi;
    }

    // the width of the layout of a page without a meta viewport (both engines)
    const DEFAULT_WIDTH = 980;
    // screens at least this wide get no desktop width
    const DESKTOP_SCREEN_WIDTH = 960;
    let zoom = 100;
    let desktopWidth = 0;
    // the content the page gave each of its metas, and the content this script wrote there
    const pageContents = new WeakMap();
    const written = new WeakMap();
    // the meta of a page without one
    let added = null;

    function read(value) {
        zoom = value && value.zoom > 0 ? value.zoom : 100;
        desktopWidth = value && value.desktopWidth > 0 && screen.width < DESKTOP_SCREEN_WIDTH ? value.desktopWidth : 0;
    }

    // the content the meta should have for the content the page gave it (null: a page without a meta)
    function contentFor(pageContent) {
        if (zoom === 100 && !desktopWidth) return pageContent;
        let width;
        if (pageContent == null) {
            width = Math.max(DEFAULT_WIDTH, screen.width);
        } else {
            // a meta without a width (only a scale) gets the width of the device
            const match = /(?:^|[,;\s])width\s*=\s*([^,;\s]+)/i.exec(pageContent);
            const value = match ? match[1].toLowerCase() : 'device-width';
            width = value === 'device-width' ? screen.width : parseFloat(value);
            if (!(width > 0)) width = screen.width;
        }
        width = Math.max(width, desktopWidth);
        // no scales: the engine fits the width into the screen, and the user can zoom in from there
        return 'width=' + Math.round(width * 100 / zoom);
    }

    function apply() {
        let pageHasMeta = false;
        const metas = document.querySelectorAll('meta[name="viewport" i]');
        for (let i = 0; i < metas.length; i++) {
            const meta = metas[i];
            if (meta === added) continue;
            pageHasMeta = true;
            const content = meta.getAttribute('content');
            // content this script did not write is the page's
            if (!written.has(meta) || written.get(meta) !== content) pageContents.set(meta, content);
            const wanted = contentFor(pageContents.get(meta));
            written.set(meta, wanted);
            if (wanted !== content) {
                if (wanted == null) meta.removeAttribute('content'); else meta.setAttribute('content', wanted);
            }
        }
        const wanted = pageHasMeta ? null : contentFor(null);
        if (wanted == null) {
            if (added) {
                added.remove();
                added = null;
            }
            return;
        }
        // the meta of the page may still come while the document loads
        if (document.readyState === 'loading') return;
        if (!added) {
            added = document.createElement('meta');
            added.setAttribute('name', 'viewport');
            added.setAttribute('data-tvbro-zoom', '');
        }
        if (added.getAttribute('content') !== wanted) added.setAttribute('content', wanted);
        const parent = document.head || document.documentElement;
        if (parent && added.parentNode !== parent) parent.appendChild(added);
    }

    read(settings);
    const options = { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] };
    const observer = new MutationObserver(apply);
    observer.observe(document, options);
    function loaded() {
        apply();
        // after the load only the head is watched, the body of a heavy page changes a lot
        observer.disconnect();
        if (document.head) observer.observe(document.head, options);
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', loaded);
    else loaded();
    apply();

    window.tvBroPageZoomApi = {
        set: function (value) {
            read(value);
            apply();
        },
        zoomedIn: function () {
            return this.visualZoom() > 1.03;
        },
        visualZoom: function () {
            const viewport = window.visualViewport;
            const width = document.documentElement.clientWidth;
            return viewport && viewport.width > 0 && width > 0 ? width / viewport.width : 1;
        }
    };
    return window.tvBroPageZoomApi;
}
