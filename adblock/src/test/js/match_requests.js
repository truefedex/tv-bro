// Runs adblock_engine.js under node for JsEngineParityTest.
//   node match_requests.js match <engine file> <psl.txt> <requests>
//     requests: lines of url, host, page url, page host, types (tab separated; empty page: none);
//     prints the decision of each (0 none, 1 block, 2 allow), one per line, then the time per check
//   node match_requests.js decide <engine file> <psl.txt> <cases.json>
//     cases.json: { settings: {enabled, hosts, homePageUrl}, requests: [[url, type, documentUrl, topUrl]...] };
//     prints shouldBlock of each as 0 or 1, one per line
'use strict';
const fs = require('fs');
const path = require('path');
const TVBroAdblock = require(path.join(__dirname, '../../main/js/adblock_engine.js'));

const [mode, enginePath, pslPath, inputPath] = process.argv.slice(2);
const file = fs.readFileSync(enginePath);
const buffer = file.buffer.slice(file.byteOffset, file.byteOffset + file.byteLength);
const engine = new TVBroAdblock.Engine(buffer, fs.readFileSync(pslPath, 'utf8'));
const out = [];

if (mode === 'match') {
    const requests = fs.readFileSync(inputPath, 'utf8').split('\n').filter(line => line.length > 0).map(line => {
        const [url, host, page, pageHost, types] = line.split('\t');
        return [url, host, page || null, pageHost || null, parseInt(types, 10)];
    });
    const started = process.hrtime.bigint();
    for (const r of requests) out.push(engine.match(r[0], r[1], r[2], r[3], r[4]));
    const micros = Number(process.hrtime.bigint() - started) / 1000 / Math.max(1, requests.length);
    out.push('time ' + micros.toFixed(2) + ' errors ' + engine.errors);
} else if (mode === 'decide') {
    const cases = JSON.parse(fs.readFileSync(inputPath, 'utf8'));
    const s = cases.settings;
    const settings = new TVBroAdblock.SiteSettings(s.enabled, s.hosts, s.homePageUrl);
    for (const r of cases.requests) out.push(TVBroAdblock.shouldBlock(engine, settings, r[0], r[1], r[2], r[3]) ? 1 : 0);
} else {
    throw new Error('unknown mode ' + mode);
}
process.stdout.write(out.join('\n') + '\n');
