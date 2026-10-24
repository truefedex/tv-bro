// Decisions of @ghostery/adblocker (the oracle) for the requests of a corpus, with the same lists.
// node oracle.mjs <node_modules dir> <lists dir> <requests.tsv> <out.tsv>
// out: url, type, document url, decision (block|allow|none), the filter that decided
import { createRequire } from 'node:module';
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const [modules, listsDir, requestsFile, outFile] = process.argv.slice(2);
const require = createRequire(join(modules, 'x.js'));
const { FiltersEngine, Request } = require('@ghostery/adblocker');

const lists = readdirSync(listsDir).filter((f) => f.endsWith('.txt')).sort()
    .map((f) => readFileSync(join(listsDir, f), 'utf8'));
const engine = FiltersEngine.parse(lists.join('\n'), {
    loadCosmeticFilters: false, loadNetworkFilters: true, enableHtmlFiltering: false, debug: true,
});

const out = [];
for (const line of readFileSync(requestsFile, 'utf8').split('\n')) {
    if (!line.trim()) continue;
    const [url, type, documentUrl] = line.split('\t');
    let decision = 'none';
    let filter = '';
    try {
        const request = Request.fromRawDetails({ url, type: type || 'other', sourceUrl: documentUrl || url });
        const result = engine.match(request);
        if (result.match) {
            decision = 'block';
            filter = result.filter ? result.filter.toString() : '';
            if (result.redirect) filter = 'REDIRECT ' + filter;
        } else if (result.exception) {
            decision = 'allow';
            filter = result.exception.toString();
        }
    } catch (e) {
        decision = 'error';
        filter = String(e);
    }
    out.push([url, type, documentUrl, decision, filter].join('\t'));
}
writeFileSync(outFile, out.join('\n') + '\n');
console.log(`${out.length} requests, blocked ${out.filter((l) => l.split('\t')[3] === 'block').length}`);
