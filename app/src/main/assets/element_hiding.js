// Element hiding of the ad blocker in every frame, for both engines (the GeckoView build copies it into its
// extension). The engine gives an api:
//   styles(callback): callback({ css, generic }) with the CSS of the page (its site's selectors and the generic
//     ones every page gets) and whether to ask for the generic selectors of its classes and ids;
//   generic(classes, ids, callback): callback(css) for these classes and ids;
//   frames: true when the engine runs no script in frames of about:blank and about:srcdoc (WebView), so the
//     page hides their elements itself (their documents are of its origin, ads like to use them).
// The CSS goes into one <style> element at once; the classes and ids of the elements that appear are collected
// by a MutationObserver and asked for in batches (each name once), up to a limit, for heavy pages on weak TVs.
function tvBroHideElements(api, doc) {
    doc = doc || document;
    if (doc.tvBroHidingStarted) return;
    doc.tvBroHidingStarted = true;

    const MAX_NAMES = 20000;
    const BATCH_DELAY_MS = 50;
    const style = doc.createElement('style');
    style.setAttribute('data-tvbro-hiding', '');

    // the head may not exist yet at the start of the document, and pages may remove the element
    function attach() {
        const parent = doc.head || doc.documentElement;
        if (parent && style.parentNode !== parent) parent.appendChild(style);
    }

    function add(css) {
        if (!css) return;
        style.appendChild(doc.createTextNode(css));
        attach();
    }

    // a frame of about:blank or about:srcdoc gets its document when it loads (an empty one before)
    function watchFrame(frame) {
        if (!api.frames || frame.tvBroHidingWatched) return;
        frame.tvBroHidingWatched = true;
        const start = () => {
            let frameDocument = null;
            try {
                frameDocument = frame.contentDocument;
            } catch (e) {
                // another origin: the engine runs the script there itself
            }
            if (frameDocument && frameDocument.URL.startsWith('about:')) tvBroHideElements(api, frameDocument);
        };
        frame.addEventListener('load', start);
        start();
    }

    api.styles(result => {
        if (!result) return;
        add(result.css);
        const generic = result.generic;

        const seen = new Set();
        let classes = [];
        let ids = [];
        let names = 0;
        let timer = 0;

        function note(element) {
            if (element.tagName === 'IFRAME' || element.tagName === 'FRAME') watchFrame(element);
            if (!generic) return;
            const id = element.id;
            if (id && typeof id === 'string' && !seen.has('#' + id)) {
                seen.add('#' + id);
                ids.push(id);
            }
            const list = element.classList;
            if (!list) return;
            for (let i = 0; i < list.length; i++) {
                const name = list[i];
                if (!seen.has('.' + name)) {
                    seen.add('.' + name);
                    classes.push(name);
                }
            }
        }

        function noteTree(root) {
            note(root);
            if (!root.querySelectorAll) return;
            const elements = root.querySelectorAll(api.frames ? '[id],[class],iframe,frame' : '[id],[class]');
            for (let i = 0; i < elements.length; i++) note(elements[i]);
        }

        function flush() {
            timer = 0;
            if (classes.length === 0 && ids.length === 0) return;
            const batchClasses = classes;
            const batchIds = ids;
            classes = [];
            ids = [];
            names += batchClasses.length + batchIds.length;
            api.generic(batchClasses, batchIds, add);
            if (names > MAX_NAMES && !api.frames) observer.disconnect();
        }

        function later() {
            if (!timer && generic) timer = setTimeout(flush, BATCH_DELAY_MS);
        }

        if (!generic && !api.frames) return;
        const observer = new MutationObserver(mutations => {
            for (let i = 0; i < mutations.length; i++) {
                const mutation = mutations[i];
                if (mutation.type === 'attributes') {
                    note(mutation.target);
                } else {
                    const added = mutation.addedNodes;
                    for (let k = 0; k < added.length; k++) if (added[k].nodeType === 1) noteTree(added[k]);
                }
            }
            attach();
            later();
        });
        observer.observe(doc, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] });
        if (doc.documentElement) {
            noteTree(doc.documentElement);
            later();
        }
    });
}
