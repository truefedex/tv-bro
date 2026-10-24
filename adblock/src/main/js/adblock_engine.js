// The request filters matcher of the extension of GeckoView: a port of FilterEngine.match (the Kotlin engine
// of this module) over the same file (FilterEngine.requestFiltersFile), so the extension decides about a
// request without asking the app. JsEngineParityTest runs it under node on a corpus of requests and compares
// its decisions with the Kotlin engine: change both together. The build of the gecko module copies it into
// the extension. Plain script (the background page of the extension has no modules), exported for node.
var TVBroAdblock = (function () {
    'use strict';

    // ---- the engine file (EngineFile.kt) ----
    const MAGIC = 0x41425654;
    const FORMAT_VERSION = 2;
    const HEADER_SIZE = 32;
    const KIND_BYTES = 1;
    const KIND_INTS = 2;
    const KIND_LONGS = 3;
    const FLAGS = 1;
    const PATTERN_OFFSETS = 2;
    const PATTERN_LENGTHS = 3;
    const DOMAIN_REFS = 4;
    const POOL = 5;
    const DOMAIN_LISTS = 6;
    const DOMAIN_HASHES = 7;
    const REGEX_IDS = 8;
    const REGEX_LITERALS = 9;
    const INFO = 10;
    const TABLE_ARRAYS = 7;

    function tableSection(table, array) {
        return 100 + table * 10 + array;
    }

    let crcTable = null;

    function crc32(bytes, from, to) {
        if (crcTable === null) {
            crcTable = new Int32Array(256);
            for (let n = 0; n < 256; n++) {
                let c = n;
                for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
                crcTable[n] = c;
            }
        }
        let crc = -1;
        for (let i = from; i < to; i++) crc = crcTable[(crc ^ bytes[i]) & 0xFF] ^ (crc >>> 8);
        return (crc ^ -1) | 0;
    }

    /** The sections of an engine file as views over its buffer (the sections are aligned to 8 bytes) */
    function readFile(buffer) {
        if (buffer.byteLength < HEADER_SIZE) throw new Error('truncated header');
        const view = new DataView(buffer);
        if (view.getInt32(0, true) !== MAGIC) throw new Error('not an engine file');
        const version = view.getInt32(4, true);
        if (version !== FORMAT_VERSION) throw new Error('format version ' + version + ', not ' + FORMAT_VERSION);
        const fingerprint = view.getBigInt64(8, true);
        const bodyLength = view.getInt32(24, true);
        if (bodyLength !== buffer.byteLength - HEADER_SIZE) throw new Error('truncated or extended body');
        if (crc32(new Uint8Array(buffer), HEADER_SIZE, buffer.byteLength) !== view.getInt32(28, true)) {
            throw new Error('damaged body (CRC)');
        }
        const sections = new Map();
        let position = HEADER_SIZE;
        while (position < buffer.byteLength) {
            if (buffer.byteLength - position < 16) throw new Error('truncated section header');
            const id = view.getInt32(position, true);
            const kind = view.getInt32(position + 4, true);
            const count = view.getInt32(position + 8, true);
            position += 16;
            const size = kind === KIND_BYTES ? 1 : kind === KIND_INTS ? 4 : kind === KIND_LONGS ? 8 : 0;
            if (size === 0) throw new Error('section ' + id + ' of kind ' + kind);
            if (count < 0 || count * size > buffer.byteLength - position) throw new Error('section ' + id + ' size');
            // bytes are signed, as in Kotlin: a byte of 0x80 or more never equals a character of a url;
            // longs are pairs of ints (low, high), compared without BigInt, which is slow
            sections.set(id, kind === KIND_BYTES ? new Int8Array(buffer, position, count) :
                new Int32Array(buffer, position, kind === KIND_INTS ? count : count * 2));
            position += count * size;
            position += (8 - position % 8) % 8;
        }
        return { fingerprint: fingerprint, sections: sections };
    }

    // ---- request types (ResourceType.kt) and filter flags (FilterFlags.kt) ----
    const ResourceType = {
        OTHER: 1, SCRIPT: 1 << 1, IMAGE: 1 << 2, STYLESHEET: 1 << 3, OBJECT: 1 << 4, SUBDOCUMENT: 1 << 5,
        XHR: 1 << 6, PING: 1 << 7, MEDIA: 1 << 8, FONT: 1 << 9, WEBSOCKET: 1 << 10, DOCUMENT: 1 << 11,
    };
    const TYPES = (1 << 12) - 1;

    function typeOf(webExtensionType) {
        switch (webExtensionType) {
            case 'main_frame': return ResourceType.DOCUMENT;
            case 'sub_frame': return ResourceType.SUBDOCUMENT;
            case 'stylesheet': return ResourceType.STYLESHEET;
            case 'script': return ResourceType.SCRIPT;
            case 'image': case 'imageset': return ResourceType.IMAGE;
            case 'font': return ResourceType.FONT;
            case 'object': case 'object_subrequest': return ResourceType.OBJECT;
            case 'xmlhttprequest': return ResourceType.XHR;
            case 'ping': case 'beacon': case 'csp_report': return ResourceType.PING;
            case 'media': return ResourceType.MEDIA;
            case 'websocket': return ResourceType.WEBSOCKET;
            default: return ResourceType.OTHER;
        }
    }

    const THIRD_PARTY = 1 << 12;
    const FIRST_PARTY = 1 << 13;
    const IMPORTANT = 1 << 14;
    const MATCH_CASE = 1 << 15;
    const ANCHOR_HOST = 1 << 16;
    const ANCHOR_START = 1 << 17;
    const ANCHOR_END = 1 << 18;
    const REGEX = 1 << 19;
    const EXCEPTION = 1 << 20;
    const F_ELEMHIDE = 1 << 21;
    const F_GENERICHIDE = 1 << 22;
    const WILDCARD = 1 << 24;
    const TOKEN_OFFSET_SHIFT = 25;
    const TOKEN_OFFSET_UNKNOWN = 0x7F;

    const T_PAGE = 0;
    const T_IMPORTANT = 1;
    const T_BLOCK = 2;
    const T_ALLOW = 3;

    const NONE = 0;
    const BLOCK = 1;
    const ALLOW = 2;
    const NO_FILTER = -1;
    const PAGE_EXCEPTION = -2;
    const ELEMHIDE = 1 << 12;
    const GENERICHIDE = 1 << 13;
    const MAX_REGEX_URL_LENGTH = 4096;
    const MAX_TOKENIZED_LENGTH = 2048;
    const MAX_LABELS = 16;

    // ---- hashing (Hashing.kt) ----
    const FNV32_OFFSET = 0x811C9DC5 | 0;
    const FNV32_PRIME = 0x01000193;
    // 0xCBF29CE484222325 and 0x100000001B3 = 2^40 + 0x1B3, in 32-bit halves
    const FNV64_OFFSET_HIGH = 0xCBF29CE4 | 0;
    const FNV64_OFFSET_LOW = 0x84222325 | 0;
    const FNV64_PRIME_LOW = 0x1B3;
    const TWO_32 = 4294967296;
    const TOKEN_HASH_CHARS = 16;

    function finishToken(h) {
        return h ^ (h >>> 15);
    }

    /** Hashing.domain as two ints, written to highs[index] and lows[index] */
    function domainHash(s, from, to, highs, lows, index) {
        let high = FNV64_OFFSET_HIGH;
        let low = FNV64_OFFSET_LOW;
        for (let i = from; i < to; i++) {
            const a = (low ^ s.charCodeAt(i)) >>> 0;
            // (high, a) * (2^40 + 0x1B3) modulo 2^64
            const product = a * FNV64_PRIME_LOW;
            high = (Math.imul(high, FNV64_PRIME_LOW) + Math.floor(product / TWO_32) + (a << 8)) | 0;
            low = product | 0;
        }
        // h xor (h ushr 29)
        lows[index] = low ^ ((low >>> 29) | (high << 3));
        highs[index] = high ^ (high >>> 29);
    }

    /** A-Z only: toLowerCase() changes the length of some strings, and positions in the url must stay */
    const UPPERCASE = /[A-Z]+/g;
    const lowercaseRun = run => run.toLowerCase();

    function asciiLowercase(text) {
        for (let i = 0; i < text.length; i++) {
            const c = text.charCodeAt(i);
            if (c >= 65 && c <= 90) return text.replace(UPPERCASE, lowercaseRun);
        }
        return text;
    }

    function isTokenChar(c) {
        return (c >= 97 && c <= 122) || (c >= 48 && c <= 57) || c === 37;
    }

    function isSeparator(c) {
        if (c >= 0x80) return false;
        return !((c >= 97 && c <= 122) || (c >= 65 && c <= 90) || (c >= 48 && c <= 57) || c === 95 || c === 45 ||
            c === 46 || c === 37);
    }

    // ---- the Public Suffix List (PublicSuffixList.kt), from the text of psl.txt ----
    function PublicSuffixList(text) {
        const lines = text.split('\n').map(line => line.endsWith('\r') ? line.slice(0, -1) : line);
        if (!lines[0].startsWith('PSL1')) throw new Error('not a compiled public suffix list');
        let position = 1;
        const table = () => {
            const count = parseInt(lines[position++], 10);
            if (!(count >= 0)) throw new Error('no rule count');
            const set = new Set(lines.slice(position, position + count));
            position += count;
            return set;
        };
        this.exact = table();
        this.wildcard = table();
        this.exception = table();
    }

    PublicSuffixList.prototype.publicSuffixStart = function (host) {
        if (isIpAddress(host)) return 0;
        let start = 0;
        while (true) {
            const next = host.indexOf('.', start);
            if (this.exception.has(host.substring(start))) return next < 0 ? start : next + 1;
            if (this.exact.has(host.substring(start))) return start;
            if (next < 0) return start;
            if (this.wildcard.has(host.substring(next + 1))) return start;
            start = next + 1;
        }
    };

    PublicSuffixList.prototype.registrableDomainStart = function (host) {
        const suffix = this.publicSuffixStart(host);
        if (suffix < 2) return 0;
        return host.lastIndexOf('.', suffix - 2) + 1;
    };

    function isIpAddress(host) {
        if (host.length === 0 || host.indexOf(':') >= 0) return true;
        const last = host.charCodeAt(host.length - 1);
        if (!(last >= 48 && last <= 57)) return false;
        for (let i = 0; i < host.length; i++) {
            const c = host.charCodeAt(i);
            if (!((c >= 48 && c <= 57) || c === 46)) return false;
        }
        return true;
    }

    // ---- patterns (PatternMatcher.kt) ----
    const STAR = 42;
    const CARET = 94;

    function indexOfStar(pool, from, to) {
        for (let i = from; i < to; i++) if (pool[i] === STAR) return i;
        return to;
    }

    function matchSegment(url, p, pool, from, to) {
        let u = p;
        const length = url.length;
        for (let k = from; k < to; k++) {
            const c = pool[k];
            if (c === CARET) {
                if (u < length) {
                    if (!isSeparator(url.charCodeAt(u))) return -1;
                    u++;
                }
            } else {
                if (u >= length || url.charCodeAt(u) !== c) return -1;
                u++;
            }
        }
        return u;
    }

    function matchRest(url, from, pool, segStart, end, anchorEnd) {
        if (segStart === end) return !anchorEnd || from === url.length;
        let position = from;
        let star = segStart;
        while (true) {
            const begin = star + 1;
            const segEnd = indexOfStar(pool, begin, end);
            const last = segEnd === end;
            if (last && anchorEnd) {
                for (let q = position; q <= url.length; q++) {
                    if (matchSegment(url, q, pool, begin, segEnd) === url.length) return true;
                }
                return false;
            }
            let found = -1;
            for (let q = position; q <= url.length; q++) {
                const e = matchSegment(url, q, pool, begin, segEnd);
                if (e >= 0) {
                    found = e;
                    break;
                }
            }
            if (found < 0) return false;
            if (last) return true;
            position = found;
            star = segEnd;
        }
    }

    function tryAt(url, p, pool, off, firstEnd, end, anchorEnd) {
        const e = matchSegment(url, p, pool, off, firstEnd);
        if (e < 0) return false;
        return matchRest(url, e, pool, firstEnd, end, anchorEnd);
    }

    function patternMatches(url, pool, off, len, anchorStart, anchorHost, anchorEnd, wildcard, hostStart, hostEnd) {
        if (len === 0) return true;
        const end = off + len;
        const firstEnd = wildcard ? indexOfStar(pool, off, end) : end;
        if (anchorStart) return tryAt(url, 0, pool, off, firstEnd, end, anchorEnd);
        if (anchorHost) {
            if (hostStart < 0) return false;
            for (let p = hostStart; p < hostEnd; p++) {
                if ((p === hostStart || url.charCodeAt(p - 1) === 46) && tryAt(url, p, pool, off, firstEnd, end, anchorEnd)) {
                    return true;
                }
            }
            return false;
        }
        const first = pool[off];
        const firstChar = String.fromCharCode(first);
        let p = 0;
        while (p <= url.length) {
            if (first !== CARET) {
                p = url.indexOf(firstChar, p);
                if (p < 0) return false;
            }
            if (tryAt(url, p, pool, off, firstEnd, end, anchorEnd)) return true;
            p++;
        }
        return false;
    }

    function patternMatchesAt(url, start, pool, off, len, anchorStart, anchorHost, anchorEnd, wildcard, hostStart, hostEnd) {
        if (start < 0) return false;
        if (anchorStart && start !== 0) return false;
        if (anchorHost && (start < hostStart || start >= hostEnd || (start !== hostStart && url.charCodeAt(start - 1) !== 46))) {
            return false;
        }
        const end = off + len;
        const firstEnd = wildcard ? indexOfStar(pool, off, end) : end;
        return tryAt(url, start, pool, off, firstEnd, end, anchorEnd);
    }

    // ---- what is known about the request being checked (FilterEngine.Scratch) ----
    const UNKNOWN = -1;
    const NO_PAGE = -2;

    function Scratch() {
        this.originalUrl = '';
        this.url = '';
        this.host = '';
        this.pageHost = null;
        this.hostStart = -1;
        this.hostEnd = -1;
        this.tokens = new Int32Array(64);
        this.tokenPositions = new Int32Array(64);
        this.tokenCount = 0;
        this.hostSuffixStarts = new Int32Array(MAX_LABELS);
        this.hostSuffixHashes = new Int32Array(MAX_LABELS);
        this.hostSuffixCount = 0;
        this.pageSuffixHighs = new Int32Array(MAX_LABELS);
        this.pageSuffixLows = new Int32Array(MAX_LABELS);
        this.pageSuffixes = -1;
        this.pageSuffixesHost = undefined;
        this.thirdPartyValue = UNKNOWN;
        this.pageSiteHost = undefined;
        this.pageSiteStart = 0;
        this.lastPageUrl = undefined;
        this.lastPageHost = undefined;
        this.lastPageExceptions = 0;
    }

    Scratch.prototype.prepare = function (requestUrl, requestHost, page) {
        this.originalUrl = requestUrl;
        this.url = asciiLowercase(requestUrl);
        this.host = asciiLowercase(requestHost);
        this.pageHost = page === null || page === undefined ? null : asciiLowercase(page);
        if (this.pageHost !== this.pageSuffixesHost) this.pageSuffixes = -1;
        this.thirdPartyValue = UNKNOWN;
        this.findHost();
        this.tokenize();
        this.hostSuffixes();
    };

    Scratch.prototype.findHost = function () {
        const url = this.url;
        const host = this.host;
        const scheme = url.indexOf('://');
        const from = scheme < 0 ? 0 : scheme + 3;
        this.hostStart = url.startsWith(host, from) ? from : host.length === 0 ? -1 : url.indexOf(host, from);
        this.hostEnd = this.hostStart < 0 ? -1 : this.hostStart + host.length;
    };

    Scratch.prototype.tokenize = function () {
        let count = 0;
        const u = this.url;
        const length = Math.min(u.length, MAX_TOKENIZED_LENGTH);
        let i = 0;
        while (i < length) {
            let c = u.charCodeAt(i);
            if (!isTokenChar(c)) {
                i++;
                continue;
            }
            let h = FNV32_OFFSET;
            let n = 0;
            let j = i;
            while (true) {
                if (n < TOKEN_HASH_CHARS) {
                    h = Math.imul(h ^ c, FNV32_PRIME);
                    n++;
                }
                j++;
                if (j === length) break;
                c = u.charCodeAt(j);
                if (!isTokenChar(c)) break;
            }
            if (j - i >= 2) {
                if (count === this.tokens.length) {
                    const tokens = new Int32Array(count * 2);
                    tokens.set(this.tokens);
                    this.tokens = tokens;
                    const positions = new Int32Array(count * 2);
                    positions.set(this.tokenPositions);
                    this.tokenPositions = positions;
                }
                this.tokenPositions[count] = i;
                this.tokens[count++] = finishToken(h);
            }
            i = j;
        }
        this.tokenCount = count;
    };

    Scratch.prototype.hostSuffixes = function () {
        const h = this.host;
        let hash = FNV32_OFFSET;
        let dots = 0;
        let count = 0;
        for (let i = h.length - 1; i >= 0; i--) {
            const c = h.charCodeAt(i);
            if (c === 46) {
                dots++;
            } else if (i === 0 || h.charCodeAt(i - 1) === 46) {
                hash = Math.imul(hash ^ c, FNV32_PRIME);
                if (dots > 0 && count < MAX_LABELS) {
                    this.hostSuffixStarts[count] = i;
                    this.hostSuffixHashes[count] = finishToken(hash);
                    count++;
                }
                continue;
            }
            hash = Math.imul(hash ^ c, FNV32_PRIME);
        }
        for (let k = 0; k < (count >> 1); k++) {
            const start = this.hostSuffixStarts[k];
            this.hostSuffixStarts[k] = this.hostSuffixStarts[count - 1 - k];
            this.hostSuffixStarts[count - 1 - k] = start;
            const hashK = this.hostSuffixHashes[k];
            this.hostSuffixHashes[k] = this.hostSuffixHashes[count - 1 - k];
            this.hostSuffixHashes[count - 1 - k] = hashK;
        }
        this.hostSuffixCount = count;
    };

    Scratch.prototype.pageSuffixCount = function () {
        if (this.pageSuffixes >= 0) return this.pageSuffixes;
        const p = this.pageHost;
        this.pageSuffixesHost = p;
        let count = 0;
        if (p !== null && p.length > 0) {
            let start = 0;
            while (count < MAX_LABELS) {
                domainHash(p, start, p.length, this.pageSuffixHighs, this.pageSuffixLows, count++);
                const dot = p.indexOf('.', start);
                if (dot < 0) break;
                start = dot + 1;
            }
        }
        this.pageSuffixes = count;
        return count;
    };

    Scratch.prototype.thirdParty = function (psl) {
        if (this.thirdPartyValue === UNKNOWN) {
            const p = this.pageHost;
            this.thirdPartyValue = p === null || p.length === 0 ? NO_PAGE : this.sameSite(psl, this.host, p) ? 0 : 1;
        }
        return this.thirdPartyValue === NO_PAGE ? null : this.thirdPartyValue === 1;
    };

    Scratch.prototype.sameSite = function (psl, requestHost, page) {
        if (page !== this.pageSiteHost) {
            this.pageSiteStart = psl.registrableDomainStart(page);
            this.pageSiteHost = page;
        }
        const start = psl.registrableDomainStart(requestHost);
        const length = requestHost.length - start;
        return length === page.length - this.pageSiteStart &&
            requestHost.substring(start) === page.substring(this.pageSiteStart);
    };

    // ---- the engine (FilterEngine.kt) ----
    function IndexTable(arrays) {
        this.hostKeys = arrays[0];
        this.hostSlots = arrays[1];
        this.tokenKeys = arrays[2];
        this.tokenSlots = arrays[3];
        this.bucketStarts = arrays[4];
        this.bucketFilters = arrays[5];
        this.noTokenBucket = arrays[6][0];
        this.filterCount = arrays[6][1];
    }

    function findSlot(keys, slots, hash) {
        const size = slots.length;
        if (size === 0) return 0;
        let i = (hash & 0x7FFFFFFF) % size;
        while (true) {
            const value = slots[i];
            if (value === 0 || keys[i] === hash) return value;
            i++;
            if (i === size) i = 0;
        }
    }

    /** Whether the sorted longs (int pairs: low, high) from..to of [array] contain (high, low), signed as Kotlin */
    function containsLong(array, from, to, high, low) {
        let lo = from;
        let hi = to - 1;
        const lowUnsigned = low >>> 0;
        while (lo <= hi) {
            const mid = (lo + hi) >>> 1;
            const h = array[mid * 2 + 1];
            if (h < high) lo = mid + 1;
            else if (h > high) hi = mid - 1;
            else {
                const l = array[mid * 2] >>> 0;
                if (l < lowUnsigned) lo = mid + 1;
                else if (l > lowUnsigned) hi = mid - 1;
                else return true;
            }
        }
        return false;
    }

    function binarySearch(array, from, to, value) {
        let low = from;
        let high = to - 1;
        while (low <= high) {
            const mid = (low + high) >>> 1;
            const v = array[mid];
            if (v < value) low = mid + 1;
            else if (v > value) high = mid - 1;
            else return mid;
        }
        return -1;
    }

    /**
     * An engine of the bytes of FilterEngine.requestFiltersFile (an ArrayBuffer) and the text of psl.txt (or a
     * PublicSuffixList already made). Throws for a file of another format version or a damaged one.
     */
    function Engine(buffer, psl) {
        const file = readFile(buffer);
        const s = file.sections;
        const get = id => {
            const array = s.get(id);
            if (array === undefined) throw new Error('bad engine file: no section ' + id);
            return array;
        };
        this.fingerprint = file.fingerprint;
        this.flags = get(FLAGS);
        this.patternOffsets = get(PATTERN_OFFSETS);
        this.patternLengths = get(PATTERN_LENGTHS);
        this.domainRefs = get(DOMAIN_REFS);
        this.pool = get(POOL);
        this.domainLists = get(DOMAIN_LISTS);
        this.domainHashes = get(DOMAIN_HASHES);
        this.regexIds = get(REGEX_IDS);
        this.regexLiterals = get(REGEX_LITERALS);
        this.pageBlockingFilters = get(INFO)[0];
        this.tables = [];
        for (let t = 0; t < 4; t++) {
            const arrays = [];
            for (let i = 0; i < TABLE_ARRAYS; i++) arrays.push(get(tableSection(t, i)));
            this.tables.push(new IndexTable(arrays));
        }
        const n = this.flags.length;
        if (this.patternOffsets.length !== n || this.patternLengths.length !== n || this.domainRefs.length !== n ||
            this.regexLiterals.length !== this.regexIds.length * 2) {
            throw new Error('bad engine file: filter arrays');
        }
        this.psl = typeof psl === 'string' ? new PublicSuffixList(psl) : psl;
        this.regexes = new Array(this.regexIds.length);
        this.scratch = new Scratch();
        this.pageScratch = new Scratch();
        this.errors = 0;
        this.decoder = new TextDecoder('utf-8');
    }

    Engine.prototype.blocksPages = function () {
        return this.pageBlockingFilters > 0;
    };

    Engine.prototype.filterCount = function () {
        return this.flags.length;
    };

    /** As FilterEngine.match: NONE, BLOCK or ALLOW */
    Engine.prototype.match = function (url, host, pageUrl, pageHost, types) {
        try {
            const id = this.matchFilter(url, host, pageUrl, pageHost, types);
            if (id === NO_FILTER) return NONE;
            if (id === PAGE_EXCEPTION) return ALLOW;
            return this.flags[id] & EXCEPTION ? ALLOW : BLOCK;
        } catch (e) {
            this.errors++;
            return NONE;
        }
    };

    Engine.prototype.matchFilter = function (url, host, pageUrl, pageHost, types) {
        const s = this.scratch;
        s.prepare(url, host, pageHost);
        const block = this.find(s, this.tables[T_BLOCK], types);
        if (block < 0) return NO_FILTER;
        if (pageUrl !== null && pageUrl !== undefined && pageHost !== null && pageHost !== undefined &&
            this.tables[T_PAGE].filterCount > 0 && (this.pageExceptions(pageUrl, pageHost) & ResourceType.DOCUMENT) !== 0) {
            return PAGE_EXCEPTION;
        }
        if (this.flags[block] & IMPORTANT) return block;
        const allow = this.find(s, this.tables[T_ALLOW], types);
        if (allow < 0) return block;
        const important = this.find(s, this.tables[T_IMPORTANT], types);
        return important >= 0 ? important : allow;
    };

    Engine.prototype.pageExceptions = function (pageUrl, pageHost) {
        const s = this.pageScratch;
        if (pageUrl === s.lastPageUrl && pageHost === s.lastPageHost) return s.lastPageExceptions;
        s.prepare(pageUrl, pageHost, pageHost);
        let result = 0;
        this.forEachCandidate(s, this.tables[T_PAGE], (id, position) => {
            const f = this.flags[id];
            if (this.checkOptions(s, id, f) && this.matchesPattern(s, id, f, position)) {
                if (f & ResourceType.DOCUMENT) result |= ResourceType.DOCUMENT;
                if (f & F_ELEMHIDE) result |= ELEMHIDE;
                if (f & F_GENERICHIDE) result |= GENERICHIDE;
            }
            return false;
        });
        s.lastPageUrl = pageUrl;
        s.lastPageHost = pageHost;
        s.lastPageExceptions = result;
        return result;
    };

    Engine.prototype.find = function (s, table, types) {
        if (table.filterCount === 0) return -1;
        let found = -1;
        this.forEachCandidate(s, table, (id, position) => {
            const f = this.flags[id];
            if ((f & types & TYPES) !== 0 && this.checkOptions(s, id, f) && this.matchesPattern(s, id, f, position)) {
                found = id;
                return true;
            }
            return false;
        });
        return found;
    };

    Engine.prototype.forEachCandidate = function (s, table, check) {
        const starts = table.bucketStarts;
        const filters = table.bucketFilters;
        for (let i = 0; i < s.hostSuffixCount; i++) {
            const slot = findSlot(table.hostKeys, table.hostSlots, s.hostSuffixHashes[i]);
            if (slot === 0) continue;
            const from = s.hostSuffixStarts[i];
            if (slot < 0) {
                const id = -slot - 1;
                if (this.hostEquals(id, s.host, from) && check(id, -1)) return;
            } else {
                for (let k = starts[slot - 1]; k < starts[slot]; k++) {
                    const id = filters[k];
                    if (this.hostEquals(id, s.host, from) && check(id, -1)) return;
                }
            }
        }
        for (let i = 0; i < s.tokenCount; i++) {
            const slot = findSlot(table.tokenKeys, table.tokenSlots, s.tokens[i]);
            if (slot === 0) continue;
            const position = s.tokenPositions[i];
            if (slot < 0) {
                if (check(-slot - 1, position)) return;
            } else {
                for (let k = starts[slot - 1]; k < starts[slot]; k++) if (check(filters[k], position)) return;
            }
        }
        const noToken = table.noTokenBucket;
        if (noToken >= 0) {
            for (let k = starts[noToken]; k < starts[noToken + 1]; k++) if (check(filters[k], -1)) return;
        }
    };

    Engine.prototype.hostEquals = function (id, host, from) {
        const off = this.patternOffsets[id];
        const len = this.patternLengths[id];
        if (host.length - from !== len) return false;
        const pool = this.pool;
        for (let i = 0; i < len; i++) if (pool[off + i] !== host.charCodeAt(from + i)) return false;
        return true;
    };

    Engine.prototype.checkOptions = function (s, id, f) {
        if (f & (THIRD_PARTY | FIRST_PARTY)) {
            const thirdParty = s.thirdParty(this.psl);
            if (thirdParty === null) return false;
            if ((f & THIRD_PARTY) && !thirdParty) return false;
            if ((f & FIRST_PARTY) && thirdParty) return false;
        }
        const domains = this.domainRefs[id];
        return domains < 0 || this.domainsMatch(s, domains);
    };

    Engine.prototype.domainsMatch = function (s, list) {
        const lists = this.domainLists;
        const includeStart = lists[list];
        const includeCount = lists[list + 1];
        const excludeStart = lists[list + 2];
        const excludeCount = lists[list + 3];
        const count = s.pageSuffixCount();
        for (let i = 0; i < count; i++) {
            const high = s.pageSuffixHighs[i];
            const low = s.pageSuffixLows[i];
            if (excludeCount > 0 && containsLong(this.domainHashes, excludeStart, excludeStart + excludeCount, high, low)) {
                return false;
            }
            if (includeCount > 0 && containsLong(this.domainHashes, includeStart, includeStart + includeCount, high, low)) {
                return true;
            }
        }
        return includeCount === 0;
    };

    Engine.prototype.matchesPattern = function (s, id, f, position) {
        if (f & REGEX) return this.matchesRegex(s, id, f);
        const url = (f & MATCH_CASE) && s.originalUrl.length === s.url.length ? s.originalUrl : s.url;
        const offset = (f >>> TOKEN_OFFSET_SHIFT) & TOKEN_OFFSET_UNKNOWN;
        if (position >= 0 && offset !== TOKEN_OFFSET_UNKNOWN) {
            return patternMatchesAt(url, position - offset, this.pool, this.patternOffsets[id], this.patternLengths[id],
                (f & ANCHOR_START) !== 0, (f & ANCHOR_HOST) !== 0, (f & ANCHOR_END) !== 0, (f & WILDCARD) !== 0,
                s.hostStart, s.hostEnd);
        }
        return patternMatches(url, this.pool, this.patternOffsets[id], this.patternLengths[id],
            (f & ANCHOR_START) !== 0, (f & ANCHOR_HOST) !== 0, (f & ANCHOR_END) !== 0, (f & WILDCARD) !== 0,
            s.hostStart, s.hostEnd);
    };

    const NEVER = {};

    Engine.prototype.matchesRegex = function (s, id, f) {
        if (s.originalUrl.length > MAX_REGEX_URL_LENGTH) return false;
        const slot = binarySearch(this.regexIds, 0, this.regexIds.length, id);
        const literalLength = this.regexLiterals[slot * 2 + 1];
        if (literalLength > 0) {
            const url = f & MATCH_CASE ? s.originalUrl : s.url;
            if (!this.containsPoolBytes(url, this.regexLiterals[slot * 2], literalLength)) return false;
        }
        let regex = this.regexes[slot];
        if (regex === undefined) {
            const off = this.patternOffsets[id];
            const source = this.decoder.decode(new Uint8Array(this.pool.buffer, this.pool.byteOffset + off,
                this.patternLengths[id]));
            try {
                // no 'g': a global regex keeps its lastIndex from test to test
                regex = new RegExp(source, f & MATCH_CASE ? '' : 'i');
            } catch (e) {
                regex = NEVER;
            }
            this.regexes[slot] = regex;
        }
        return regex !== NEVER && regex.test(s.originalUrl);
    };

    Engine.prototype.containsPoolBytes = function (url, off, len) {
        const pool = this.pool;
        const first = String.fromCharCode(pool[off]);
        let i = url.indexOf(first);
        while (i >= 0 && i + len <= url.length) {
            let k = 1;
            while (k < len && url.charCodeAt(i + k) === pool[off + k]) k++;
            if (k === len) return true;
            i = url.indexOf(first, i + 1);
        }
        return false;
    };

    // ---- the decision of the app (AppEngineAdBlocker.shouldBlock) ----
    /**
     * The host of a url as Android's Uri.getHost gives it (without new URL(), which is slow on TVs): null for urls
     * without one (about:, data:, blob:)
     */
    function hostOf(url) {
        if (!url) return null;
        const scheme = url.indexOf('://');
        if (scheme <= 0) return null;
        for (let i = 0; i < scheme; i++) {
            const c = url.charCodeAt(i);
            const letter = (c >= 97 && c <= 122) || (c >= 65 && c <= 90);
            if (!letter && (i === 0 || !((c >= 48 && c <= 57) || c === 43 || c === 45 || c === 46))) return null;
        }
        const start = scheme + 3;
        let end = url.length;
        for (let i = start; i < url.length; i++) {
            const c = url.charCodeAt(i);
            if (c === 47 || c === 63 || c === 35) {
                end = i;
                break;
            }
        }
        const at = url.lastIndexOf('@', end - 1);
        const from = at >= start ? at + 1 : start;
        let to = end;
        if (url.charCodeAt(from) === 91) {
            const close = url.indexOf(']', from);
            if (close >= 0 && close < end) to = close + 1;
        } else {
            const colon = url.indexOf(':', from);
            if (colon >= 0 && colon < end) to = colon;
        }
        return to > from ? url.substring(from, to) : null;
    }

    function isWebUrl(url) {
        return (url.startsWith('http://') || url.startsWith('https://')) && hostOf(url) !== null;
    }

    /**
     * The site settings of the app: [enabled] the global setting, [hosts] the hosts with a setting of their
     * own (host: true or false), [homePageUrl] where nothing is blocked
     */
    function SiteSettings(enabled, hosts, homePageUrl) {
        this.enabled = enabled;
        this.hosts = hosts || {};
        this.homePageUrl = homePageUrl || null;
    }

    SiteSettings.prototype.isEnabledOn = function (page) {
        if (page && this.homePageUrl && page.startsWith(this.homePageUrl)) return false;
        const host = hostOf(page);
        if (host === null) return this.enabled;
        const own = this.hosts[host];
        return typeof own === 'boolean' ? own : this.enabled;
    };

    /**
     * Whether to block a request of the WebExtensions API, as AppEngineAdBlocker.shouldBlock: [documentUrl] the
     * frame that made it (or its origin), [topUrl] the page of the tab
     */
    function shouldBlock(engine, settings, url, type, documentUrl, topUrl) {
        const isMainFrame = type === 'main_frame';
        const document = documentUrl || null;
        const top = topUrl || document;
        if (!settings.isEnabledOn(isMainFrame ? url : top || document)) return false;
        const host = hostOf(url);
        if (host === null) return false;
        const page = isMainFrame ? url : document !== null && isWebUrl(document) ? document : top || document;
        return engine.match(url, host, page, hostOf(page), typeOf(type)) === BLOCK;
    }

    return {
        Engine: Engine,
        PublicSuffixList: PublicSuffixList,
        SiteSettings: SiteSettings,
        shouldBlock: shouldBlock,
        hostOf: hostOf,
        typeOf: typeOf,
        NONE: NONE,
        BLOCK: BLOCK,
        ALLOW: ALLOW,
    };
})();

if (typeof module !== 'undefined') module.exports = TVBroAdblock;
