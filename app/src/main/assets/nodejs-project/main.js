const fs = require('fs');
const path = require('path');

console.log('[Shorts Bridge] main.js started.');

let Innertube = null;
let UniversalCache = null;
let ytClient = null;
let continuationToken = null;
const seenVideoIds = new Set();
const MAX_SEEN_VIDEOS = 2000;
let isInitialized = false;
let initPromise = null;

const defaultCookiesPath = process.argv[2] || './cookies.txt';

function parseCookies(cookieInput) {
    if (!cookieInput) return '';
    let content = cookieInput;
    try {
        const resolvedPath = path.resolve(cookieInput);
        if (fs.existsSync(resolvedPath) && fs.statSync(resolvedPath).isFile()) {
            content = fs.readFileSync(resolvedPath, 'utf8');
        }
    } catch (_) {}

    if (content.includes('\n') || content.includes('\t') || content.startsWith('#')) {
        const lines = content.split(/\r?\n/);
        const cookieMap = new Map();
        for (const line of lines) {
            let trimmedLine = line.trim();
            if (!trimmedLine) continue;
            if (trimmedLine.startsWith('#HttpOnly_')) trimmedLine = trimmedLine.substring(10).trim();
            else if (trimmedLine.startsWith('#')) continue;

            const parts = trimmedLine.split(/\t+/);
            if (parts.length >= 7) {
                const name = parts[5].trim();
                const value = parts[6].trim();
                if (name) cookieMap.set(name, value);
            } else if (trimmedLine.includes('=')) {
                const eqIdx = trimmedLine.indexOf('=');
                const name = trimmedLine.substring(0, eqIdx).trim();
                const value = trimmedLine.substring(eqIdx + 1).trim();
                if (name) cookieMap.set(name, value);
            }
        }
        if (cookieMap.size > 0) return Array.from(cookieMap.entries()).map(([k, v]) => `${k}=${v}`).join('; ');
    }
    return content.trim();
}

function getReelEndpoint(entry) {
    if (!entry || typeof entry !== 'object') return null;
    return entry.command?.reelWatchEndpoint
        || entry.reelWatchEndpoint
        || entry.reelWatchSequenceEntryRenderer?.command?.reelWatchEndpoint
        || entry.reelWatchSequenceEntryRenderer?.reelWatchEndpoint
        || entry.reelItemRenderer?.command?.reelWatchEndpoint
        || entry.reelItemRenderer?.reelWatchEndpoint
        || entry.command?.watchEndpoint
        || entry.watchEndpoint
        || entry.reelAdRenderer?.command?.reelWatchEndpoint
        || entry.reelAdRenderer?.reelWatchEndpoint
        || null;
}

function extractVideoId(entry, endpoint) {
    if (endpoint?.videoId && typeof endpoint.videoId === 'string') return endpoint.videoId;
    if (entry.videoId && typeof entry.videoId === 'string') return entry.videoId;
    if (entry.reelWatchSequenceEntryRenderer?.videoId && typeof entry.reelWatchSequenceEntryRenderer.videoId === 'string') return entry.reelWatchSequenceEntryRenderer.videoId;
    if (entry.reelItemRenderer?.videoId && typeof entry.reelItemRenderer.videoId === 'string') return entry.reelItemRenderer.videoId;
    return null;
}

const AD_RENDERER_KEYS = new Set([
    'reelAdRenderer', 'adPlacementRenderer', 'instreamAdRenderer',
    'reelPlayerAdHeaderRenderer', 'displayAdRenderer', 'displayAdsRenderer',
    'adSlotRenderer', 'inFeedAdLayoutRenderer'
]);

const STRONG_AD_KEYS = new Set([
    'adLayoutId', 'adClientParams', 'adTrackingParams', 'adType',
    'sponsoredText', 'promotionLabel', 'adLoggingContext'
]);

function hasAdLoggingContext(obj) {
    if (!obj || typeof obj !== 'object') return false;
    return !!(obj.adLoggingContext || obj.loggingContext?.adLoggingContext || obj.loggingContext?.adLoggingContext?.serializedExperimentFlags);
}

function hasExplicitAdRenderer(obj) {
    if (!obj || typeof obj !== 'object') return false;
    return Object.keys(obj).some(key => AD_RENDERER_KEYS.has(key));
}

function hasStrongAdFields(obj) {
    if (!obj || typeof obj !== 'object') return false;
    return Object.keys(obj).some(key => STRONG_AD_KEYS.has(key)) || hasAdLoggingContext(obj);
}

function hasNestedAdStructure(obj, depth = 0) {
    if (!obj || typeof obj !== 'object' || depth > 4) return false;
    if (hasExplicitAdRenderer(obj) || hasStrongAdFields(obj)) return true;
    const interestingKeys = [
        'reelAdRenderer', 'reelItemRenderer', 'reelWatchSequenceEntryRenderer',
        'command', 'endpoint', 'loggingContext', 'overlay', 'header', 'badge',
        'badges', 'metadata', 'promotedVideoRenderer', 'adLayoutMetadata'
    ];
    return interestingKeys.some(key => {
        const value = obj[key];
        return value && typeof value === 'object' && hasNestedAdStructure(value, depth + 1);
    });
}

function hasAdBadge(entry) {
    if (!entry || typeof entry !== 'object') return false;
    const candidates = [entry.badges, entry.reelItemRenderer?.badges, entry.reelWatchSequenceEntryRenderer?.badges, entry.metadata?.badges];
    for (const badges of candidates) {
        if (!Array.isArray(badges)) continue;
        for (const badge of badges) {
            const renderer = badge?.metadataBadgeRenderer;
            if (!renderer) continue;
            const label = renderer.label || '';
            const tooltip = renderer.tooltip || '';
            if (/\b(sponsored|advertisement|advertiser|promoted)\b/i.test(label) || /\b(sponsored|advertisement|advertiser|promoted)\b/i.test(tooltip)) return true;
        }
    }
    return false;
}

function isAd(entry, endpoint = null) {
    if (!entry || typeof entry !== 'object') return false;
    if (hasExplicitAdRenderer(entry) || hasStrongAdFields(entry)) return true;
    if (endpoint && (hasStrongAdFields(endpoint) || hasAdLoggingContext(endpoint))) return true;
    if (hasAdBadge(entry) || hasNestedAdStructure(entry)) return true;
    return !!(entry.reelAdItem || entry.adPlacement || entry.adSlot ||
              entry.playerOverlay?.reelPlayerOverlayRenderer?.adInfo ||
              entry.command?.reelWatchEndpoint?.adParams ||
              entry.reelWatchEndpoint?.adParams ||
              (endpoint && endpoint.playerParams && endpoint.playerParams.startsWith('ad')) ||
              entry.reelPlayerConfig?.isAds);
}

function classifyReelEntry(entry) {
    if (!entry || typeof entry !== 'object') return 'unknown';
    const endpoint = getReelEndpoint(entry);
    if (isAd(entry, endpoint)) return 'ad';
    if (entry.reelItemRenderer || entry.reelWatchSequenceEntryRenderer) return 'short';
    const videoId = extractVideoId(entry, endpoint);
    return (videoId && /^[a-zA-Z0-9_-]{11}$/.test(videoId)) ? 'short' : 'unknown';
}

function extractShorts(entries) {
    const list = [];
    for (const entry of entries || []) {
        const endpoint = getReelEndpoint(entry);
        const classification = classifyReelEntry(entry);
        if (classification === 'ad' || classification !== 'short') continue;
        const videoId = extractVideoId(entry, endpoint);
        if (!videoId || !/^[a-zA-Z0-9_-]{11}$/.test(videoId) || seenVideoIds.has(videoId)) continue;
        if (seenVideoIds.size >= MAX_SEEN_VIDEOS) {
            const iterator = seenVideoIds.values();
            for (let i = 0; i < 500; i++) {
                const next = iterator.next();
                if (next.done) break;
                seenVideoIds.delete(next.value);
            }
        }
        seenVideoIds.add(videoId);
        const thumbnails = endpoint?.thumbnail?.thumbnails || entry.reelItemRenderer?.thumbnail?.thumbnails || entry.reelWatchSequenceEntryRenderer?.thumbnail?.thumbnails;
        let thumbnailUrl = (thumbnails && thumbnails.length > 0) ? thumbnails[thumbnails.length - 1].url : `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`;
        if (thumbnailUrl && thumbnailUrl.startsWith('//')) thumbnailUrl = `https:${thumbnailUrl}`;
        list.push({ videoId, url: `https://www.youtube.com/shorts/${videoId}`, thumbnailUrl });
    }
    return list;
}

async function init(cookiesPathOrString = null) {
    if (initPromise) return initPromise;
    initPromise = (async () => {
        const finalCookiesPath = cookiesPathOrString || defaultCookiesPath;
        try {
            if (!Innertube || !UniversalCache) {
                const youtubei = await import('youtubei.js');
                Innertube = youtubei.Innertube;
                UniversalCache = youtubei.UniversalCache;
            }
            // Configuration for better mobile support
            ytClient = await Innertube.create({
                cookie: parseCookies(finalCookiesPath),
                cache: new UniversalCache(false),
                generate_session_locally: true
            });

            // Set the evaluator manually to avoid "To decipher URLs, you must provide your own JavaScript evaluator"
            // Node.js mobile environment sometimes doesn't expose it correctly to youtubei.js
            if (ytClient.session.player) {
                ytClient.session.player.eval = (js) => {
                    return eval(js);
                };
            }

            continuationToken = null;
            seenVideoIds.clear();
            isInitialized = true;
            return { success: true };
        } catch (err) {
            console.error('[Node] Initialization failed:', err);
            isInitialized = false;
            throw err;
        }
    })().finally(() => { initPromise = null; });
    return initPromise;
}

async function reset(seedVideoId = null) {
    if (!isInitialized || !ytClient) await init();
    seenVideoIds.clear();
    continuationToken = null;
    if (seedVideoId) {
        try {
            const info = await ytClient.getShortsVideoInfo(seedVideoId);
            if (info?.watch_next_feed) extractShorts(info.watch_next_feed);
        } catch (err) {}
    }
    return { success: true };
}

async function getShorts() {
    if (!isInitialized || !ytClient) await init();
    let attempts = 0;
    try {
        if (!continuationToken) {
            let params = 'CAw%3D';
            try {
                const nav = await ytClient.actions.execute('/navigation/resolve_url', { url: 'https://www.youtube.com/shorts' });
                if (nav.data?.endpoint?.reelWatchEndpoint?.params) params = nav.data.endpoint.reelWatchEndpoint.params;
            } catch (e) {}
            const res = await ytClient.actions.execute('/reel/reel_watch_sequence', { sequenceParams: params });
            const initialItems = extractShorts(res.data?.entries);
            const nextToken = res.data?.continuationEndpoint?.continuationCommand?.token || null;
            if (nextToken) {
                try {
                    const batchRes = await ytClient.actions.execute('/reel/reel_watch_sequence', { sequenceParams: nextToken });
                    const batchItems = extractShorts(batchRes.data?.entries);
                    continuationToken = batchRes.data?.continuationEndpoint?.continuationCommand?.token || null;
                    const combined = [...initialItems, ...batchItems];
                    if (combined.length > 0) return combined;
                } catch (e) { continuationToken = nextToken; }
            } else { continuationToken = nextToken; }
            if (initialItems.length > 0) return initialItems;
        }
        while (attempts < 3) {
            attempts++;
            if (!continuationToken) break;
            const res = await ytClient.actions.execute('/reel/reel_watch_sequence', { sequenceParams: continuationToken });
            continuationToken = res.data?.continuationEndpoint?.continuationCommand?.token || null;
            const items = extractShorts(res.data?.entries);
            if (items.length > 0) return items;
        }
        if (attempts > 0 && !continuationToken) return await getShorts();
        return [];
    } catch (err) {
        continuationToken = null;
        throw err;
    }
}

async function getShortDetails(videoId) {
    if (!isInitialized || !ytClient) await init();
    try {
        const info = await ytClient.getBasicInfo(videoId);
        const basic = info.basic_info || {};
        const thumbnails = basic.thumbnail || [];
        return { videoId: basic.id || videoId, title: basic.title || '', author: basic.author || '', channelId: basic.channel_id || '', viewCount: basic.view_count || 0, likeCount: basic.like_count || 0, duration: basic.duration || 0, description: basic.short_description || '', thumbnailUrl: thumbnails.length > 0 ? thumbnails[thumbnails.length - 1].url : `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`, url: `https://www.youtube.com/shorts/${basic.id || videoId}` };
    } catch (err) {
        return { videoId, title: '', author: '', channelId: '', viewCount: 0, likeCount: 0, duration: 0, description: '', thumbnailUrl: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`, url: `https://www.youtube.com/shorts/${videoId}` };
    }
}

// Global error handling to prevent process crashes
process.on('uncaughtException', (err) => {
    console.error('[Node] Uncaught Exception:', err.message);
    if (err.stack) console.error(err.stack);
});

process.on('unhandledRejection', (reason, promise) => {
    console.error('[Node] Unhandled Rejection:', reason);
});

let NativeBridge = null;
if (typeof process._linkedBinding === 'function') { try { NativeBridge = process._linkedBinding('rn_bridge'); } catch (e) {} }
let rn_bridge = null;
try { rn_bridge = require('rn-bridge'); } catch (e) { try { rn_bridge = require('rn_bridge'); } catch (e2) {} }

function sendToBridge(eventName, data) {
    const strData = typeof data === 'string' ? data : JSON.stringify(data);
    if (NativeBridge?.sendMessage) { try { NativeBridge.sendMessage(eventName, strData); return; } catch (e) {} }
    if (rn_bridge?.channel) { try { if (rn_bridge.channel.post) { rn_bridge.channel.post(eventName, data); return; } if (rn_bridge.channel.send) { rn_bridge.channel.send(eventName, data); return; } } catch (e) {} }
}

async function handleIncomingMessage(rawMsg) {
    let msg = rawMsg;
    if (typeof rawMsg === 'string') { try { msg = JSON.parse(rawMsg); } catch (e) {} }
    if (!msg || typeof msg !== 'object') return;
    const action = msg.action || msg.type || msg.event;
    const reqId = msg.reqId || msg.id;
    try {
        switch (action) {
            case 'init': const iRes = await init(msg.cookies || msg.cookiesPath || msg.payload); sendToBridge('init_result', { reqId, success: true, ...iRes }); break;
            case 'getShorts':
            case 'fetchShorts': const shorts = await getShorts(); sendToBridge('shorts_batch', reqId ? { reqId, shorts } : { shorts }); break;
            case 'reset': const rRes = await reset(msg.seedVideoId || msg.payload); sendToBridge('reset_result', { reqId, success: true, ...rRes }); break;
            case 'getShortDetails': const details = await getShortDetails(msg.videoId || msg.payload); sendToBridge('short_details', reqId ? { reqId, details } : { details }); break;
        }
    } catch (err) { sendToBridge('error', { reqId, action, error: err.message || String(err) }); }
}

if (NativeBridge?.registerChannel) { try { NativeBridge.registerChannel('message', (c, d) => handleIncomingMessage(d)); NativeBridge.registerChannel('_EVENTS_', (c, d) => handleIncomingMessage(d)); } catch (e) {} }
if (rn_bridge?.channel) rn_bridge.channel.on('message', handleIncomingMessage);
sendToBridge('ready', { status: 'ready' });
global.init = init; global.reset = reset; global.getShorts = getShorts; global.getShortDetails = getShortDetails;
module.exports = { init, reset, getShorts, getShortDetails, parseCookies, extractShorts, isAd, classifyReelEntry };
setInterval(() => {}, 1000);
init().catch(e => {});
