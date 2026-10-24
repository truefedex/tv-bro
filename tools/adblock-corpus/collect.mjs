// Collects the requests that pages make, for the tests and the benchmark of ad blocking: the sites of
// sites.txt are opened in a headless Chrome without ad blocking, and each request is written as a line
// `url<TAB>type<TAB>document url` (types of WebExtensions, as GeckoView gives them; the document url is empty
// for pages, the one of the parent frame for frames).
//
//   node collect.mjs [sites.txt] [requests.tsv]
//
// Needs Node 22+ (its WebSocket) and Chrome (CHROME=<path to the executable> if it is not at the usual place).
// No packages: it talks to Chrome over the DevTools Protocol itself.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const sitesFile = process.argv[2] ?? join(here, 'sites.txt');
const outFile = process.argv[3] ?? 'requests.tsv';
const PAGE_TIMEOUT_MS = 30000;
const SETTLE_MS = 5000;

const CHROME_PATHS = [
    process.env.CHROME,
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
].filter(Boolean);

// Resource types of the DevTools Protocol -> types of WebExtensions (webRequest.ResourceType)
const TYPES = {
    Stylesheet: 'stylesheet', Image: 'image', Media: 'media', Font: 'font', Script: 'script',
    XHR: 'xmlhttprequest', Fetch: 'xmlhttprequest', EventSource: 'xmlhttprequest', WebSocket: 'websocket',
    Ping: 'ping', CSPViolationReport: 'csp_report', TextTrack: 'media', Manifest: 'other', Prefetch: 'other',
    Other: 'other',
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function readSites() {
    return readFileSync(sitesFile, 'utf8').split(/\r?\n/).map((s) => s.trim())
        .filter((s) => s && !s.startsWith('#'))
        .map((s) => (s.includes('://') ? s : 'https://' + s));
}

async function launchChrome() {
    const chrome = CHROME_PATHS.find((p) => existsSync(p));
    if (!chrome) throw new Error('Chrome not found, set CHROME');
    const profile = mkdtempSync(join(tmpdir(), 'tvbro-corpus-'));
    const proc = spawn(chrome, [
        '--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profile}`,
        '--no-first-run', '--no-default-browser-check', '--mute-audio', '--window-size=1920,1080',
        // all frames in one renderer: their requests come in the events of the page
        '--disable-site-isolation-trials', '--disable-features=IsolateOrigins,site-per-process',
        'about:blank',
    ], { stdio: 'ignore' });
    const portFile = join(profile, 'DevToolsActivePort');
    for (let i = 0; i < 100 && !existsSync(portFile); i++) await sleep(100);
    const [port, path] = readFileSync(portFile, 'utf8').split('\n');
    return { proc, profile, url: `ws://127.0.0.1:${port}${path}` };
}

class Cdp {
    constructor(url) {
        this.ws = new WebSocket(url);
        this.nextId = 1;
        this.calls = new Map();
        this.listeners = [];
        this.ws.onmessage = (e) => {
            const msg = JSON.parse(e.data);
            if (msg.id) {
                const call = this.calls.get(msg.id);
                this.calls.delete(msg.id);
                if (msg.error) call.reject(new Error(msg.error.message)); else call.resolve(msg.result);
            } else {
                for (const l of this.listeners) l(msg);
            }
        };
    }

    open() {
        return new Promise((resolve, reject) => { this.ws.onopen = resolve; this.ws.onerror = reject; });
    }

    send(method, params = {}, sessionId) {
        const id = this.nextId++;
        this.ws.send(JSON.stringify({ id, method, params, sessionId }));
        return new Promise((resolve, reject) => this.calls.set(id, { resolve, reject }));
    }

    on(listener) { this.listeners.push(listener); }
}

async function main() {
    const sites = readSites();
    const chrome = await launchChrome();
    const cdp = new Cdp(chrome.url);
    await cdp.open();
    const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true });
    const send = (method, params) => cdp.send(method, params, sessionId);
    await send('Page.enable');
    await send('Network.enable');
    const mainFrameId = (await send('Page.getFrameTree')).frameTree.frame.id;

    const parentOf = new Map(); // frame id -> parent frame id
    const frameUrl = new Map(); // frame id -> url
    const lines = new Set();
    let loaded = null;
    cdp.on((msg) => {
        if (msg.sessionId !== sessionId) return;
        const p = msg.params;
        switch (msg.method) {
            case 'Page.frameAttached': parentOf.set(p.frameId, p.parentFrameId); break;
            case 'Page.frameNavigated':
                if (p.frame.parentId) parentOf.set(p.frame.id, p.frame.parentId);
                frameUrl.set(p.frame.id, p.frame.url);
                break;
            case 'Page.loadEventFired': loaded?.(); break;
            case 'Network.requestWillBeSent': {
                const url = p.request.url;
                if (!/^(https?|wss?):/.test(url)) break;
                let type, documentUrl;
                if (p.type === 'Document') {
                    if (p.frameId === mainFrameId) { type = 'main_frame'; documentUrl = ''; }
                    else { type = 'sub_frame'; documentUrl = frameUrl.get(parentOf.get(p.frameId)) ?? p.documentURL ?? ''; }
                } else {
                    type = TYPES[p.type] ?? 'other';
                    documentUrl = p.documentURL ?? frameUrl.get(p.frameId) ?? '';
                }
                lines.add(`${url}\t${type}\t${documentUrl}`);
                break;
            }
        }
    });

    for (const [i, site] of sites.entries()) {
        const before = lines.size;
        try {
            const load = new Promise((r) => { loaded = r; });
            await send('Page.navigate', { url: site });
            await Promise.race([load, sleep(PAGE_TIMEOUT_MS)]);
            // lazy content and ads load while the page scrolls
            for (let y = 1; y <= 4; y++) {
                await send('Runtime.evaluate', { expression: `window.scrollTo(0, document.body.scrollHeight * ${y / 4})` });
                await sleep(1000);
            }
            await sleep(SETTLE_MS);
        } catch (e) {
            console.log(`${site}: ${e.message}`);
        }
        console.log(`[${i + 1}/${sites.length}] ${site}: ${lines.size - before} requests`);
        writeFileSync(outFile, [...lines].join('\n') + '\n');
    }

    chrome.proc.kill();
    await sleep(500);
    try { rmSync(chrome.profile, { recursive: true, force: true }); } catch { /* still locked */ }
    console.log(`${lines.size} requests -> ${outFile}`);
    process.exit(0);
}

main().catch((e) => { console.error(e); process.exit(1); });
