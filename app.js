/**
 * Crypto Visor PRO V2 - Android Edition (Honor Magic6 Lite)
 * 
 * Funcionalidades V8 portadas para Web / PWA:
 * 1. 🪙 8 Tickers Predeterminados con Binance WebSockets en tiempo real.
 * 2. 📊 Indicadores Pine Script: [V] VIX Fix, [F] Franja Dinámica (VWMA+ATR 1.8), [E] EMA 100 con Confluencia V+F.
 * 3. ⚡ Flash Shock (PUMP / DUMP) por variación brusca 1m + multiplicador de volumen.
 * 4. 📰 Motor de Noticias Flash (<2h) con filtro de fuentes y memoria de 3 min en botón [📰].
 * 5. 🚨 MEGA SHOCK: Confluencia Noticia Urgente + Flash Shock con sirena nuclear, Toast pop-up y vibración háptica.
 * 6. 📱 Screen Wake Lock para mantener encendida la pantalla AMOLED del teléfono.
 * 7. 🪟 Modo Ventana Flotante / Compacto para MagicOS.
 */

// ==========================================
// 1. CONFIGURACIÓN Y ESTADO GLOBAL
// ==========================================
const DEFAULT_TICKERS = [
    { symbol: "BTC", pair: "btcusdt", decimals: 2 },
    { symbol: "ETH", pair: "ethusdt", decimals: 2 },
    { symbol: "SOL", pair: "solusdt", decimals: 3 },
    { symbol: "ZEC", pair: "zecusdt", decimals: 2 },
    { symbol: "SUI", pair: "suiusdt", decimals: 2 },
    { symbol: "UNI", pair: "uniusdt", decimals: 2 },
    { symbol: "ENA", pair: "enausdt", decimals: 2 },
    { symbol: "WLD", pair: "wldusdt", decimals: 2 }
];

const TRUSTED_SOURCES = [
    'reuters', 'bloomberg', 'cnbc', 'investing', 'marketwatch', 'yahoo', 'financial times', 
    'wall street journal', 'wsj', 'coindesk', 'cointelegraph', 'decrypt', 'the block', 
    'bitcoin magazine', 'beincrypto', 'el economista', 'cinco días', 'expansion', 
    'cronista', 'infobae', 'forbes', 'morningstar', 'ambit', 'fxstreet', 'dailyfx'
];

const URGENT_KEYWORDS = [
    'guerra', 'war', 'ataque', 'attack', 'misil', 'missile', 'iran', 'israel', 'rusia', 'ucrania', 'china', 'taiwan',
    'fed', 'fomc', 'powell', 'inflacion', 'inflation', 'cpi', 'ipc', 'tasas', 'rates', 'aranceles', 'tariffs',
    'quiebra', 'bankrupt', 'sec', 'etf', 'hack', 'banco central', 'urgente', 'breaking', 'alerta', 'crash', 'nuclear'
];

let state = {
    tickers: JSON.parse(localStorage.getItem('crypto_tickers')) || DEFAULT_TICKERS,
    timeframe: localStorage.getItem('crypto_timeframe') || '3m',
    shockThreshold: parseFloat(localStorage.getItem('crypto_shock_thresh') || '1.5'),
    shockVolMult: parseFloat(localStorage.getItem('crypto_shock_vol_mult') || '2.5'),
    vibrationEnabled: localStorage.getItem('crypto_vibration') !== 'false',
    soundMuted: localStorage.getItem('crypto_sound_muted') === 'true',
    compactView: localStorage.getItem('crypto_compact_view') === 'true',
    
    // Runtime data
    tickerData: {}, // pair -> { price, change, candleHistory: [], candle1mHistory: [], indicators: {}, lastShock: null }
    latestNews: [],
    recentUrgentNews: null, // Ultima noticia urgente detectada en <45m
    newsAlertEndTime: 0,
    wakeLock: null,
    ws: null,
    audioCtx: null
};

// ==========================================
// 2. MOTOR DE SONIDO (WEB AUDIO API IDÉNTICO A WINSOUND) & VIBRACIÓN
// ==========================================
function getAudioContext() {
    if (!state.audioCtx) {
        const AudioContextClass = window.AudioContext || window.webkitAudioContext;
        if (AudioContextClass) {
            state.audioCtx = new AudioContextClass();
        }
    }
    if (state.audioCtx && state.audioCtx.state === 'suspended') {
        state.audioCtx.resume();
    }
    return state.audioCtx;
}

function playTone(freq, durationMs, delayMs = 0) {
    if (state.soundMuted) return;
    try {
        const ctx = getAudioContext();
        if (!ctx) return;

        setTimeout(() => {
            const osc = ctx.createOscillator();
            const gain = ctx.createGain();
            osc.type = 'sine';
            osc.frequency.setValueAtTime(freq, ctx.currentTime);

            gain.gain.setValueAtTime(0.18, ctx.currentTime);
            gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + (durationMs / 1000));

            osc.connect(gain);
            gain.connect(ctx.destination);

            osc.start();
            osc.stop(ctx.currentTime + (durationMs / 1000));
        }, delayMs);
    } catch (e) {
        console.warn('Audio play error:', e);
    }
}

function playSoundPattern(type) {
    if (state.soundMuted) return;

    switch (type) {
        case 'confluence_vf':
        case 'soft_beep':
            playTone(880, 140);
            break;
        case 'double_ding':
            playTone(750, 90, 0);
            playTone(1100, 130, 130);
            break;
        case 'flash_dump':
            playTone(1200, 110, 0);
            playTone(700, 180, 140);
            triggerHaptic([120, 60, 200]);
            break;
        case 'flash_pump':
            playTone(750, 90, 0);
            playTone(1350, 160, 120);
            triggerHaptic([80, 50, 150]);
            break;
        case 'urgent_news':
            playTone(1000, 80, 0);
            triggerHaptic([100, 100, 100]);
            break;
        case 'mega_shock':
            // Sirena nuclear ascendente / descendente
            playTone(1400, 120, 0);
            playTone(900, 140, 150);
            playTone(1400, 120, 320);
            playTone(900, 140, 470);
            playTone(1500, 240, 640);
            triggerHaptic([200, 100, 200, 100, 400]);
            break;
    }
}

function triggerHaptic(pattern) {
    if (!state.vibrationEnabled) return;
    if (navigator.vibrate) {
        try {
            navigator.vibrate(pattern);
        } catch (e) {
            console.log('Vibration error:', e);
        }
    }
}

// ==========================================
// 3. CÁLCULO DE INDICADORES PINE SCRIPT
// ==========================================
function calculateIndicators(candles) {
    const n = candles.length;
    if (n < 25) {
        return {
            v_active: false,
            f_active: false,
            e_active: false,
            ema_color: 'inactive',
            vf_confluence: false,
            v_desc: "Datos insuficientes",
            f_desc: "Datos insuficientes",
            e_desc: "Datos insuficientes"
        };
    }

    const closes = candles.map(c => c.close);
    const highs = candles.map(c => c.high);
    const lows = candles.map(c => c.low);
    const volumes = candles.map(c => c.vol);

    const currClose = closes[n - 1];
    const currHigh = highs[n - 1];
    const currLow = lows[n - 1];

    // 1. VIX FIX VISUAL (V)
    const pd = 22, bbl = 20, mult = 2.0, lb = 50, ph = 0.85;
    const wvfList = [];
    for (let i = 0; i < n; i++) {
        const start = Math.max(0, i - pd + 1);
        const lookback = closes.slice(start, i + 1);
        const highestC = Math.max(...lookback);
        const wvf = highestC > 0 ? ((highestC - lows[i]) / highestC) * 100.0 : 0.0;
        wvfList.push(wvf);
    }

    const curWvf = wvfList[wvfList.length - 1];
    const subWvfBbl = wvfList.slice(-bbl);
    const midLine = subWvfBbl.reduce((a, b) => a + b, 0) / subWvfBbl.length;
    const variance = subWvfBbl.reduce((a, b) => a + Math.pow(b - midLine, 2), 0) / subWvfBbl.length;
    const sDev = Math.sqrt(variance) * mult;
    const upperBand = midLine + sDev;

    const subWvfLb = wvfList.slice(-Math.min(lb, wvfList.length));
    const rangeHigh = Math.max(...subWvfLb) * ph;

    const v_active = (curWvf >= upperBand) || (curWvf >= rangeHigh);
    const v_desc = `[V] VIX Fix: ${v_active ? 'ACTIVO' : 'Normal'} (WVF: ${curWvf.toFixed(2)}, Banda: ${upperBand.toFixed(2)})`;

    // 2. FRANJA DINÁMICA (F) - VWMA + ATR (1.8)
    const volLen = 20, volMult = 1.8;
    const recentCloses = closes.slice(-volLen);
    const recentVols = volumes.slice(-volLen);
    const sumVol = recentVols.reduce((a, b) => a + b, 0);
    let baseVwma = 0;
    if (sumVol > 0) {
        let sumProd = 0;
        for (let i = 0; i < recentCloses.length; i++) {
            sumProd += recentCloses[i] * recentVols[i];
        }
        baseVwma = sumProd / sumVol;
    } else {
        baseVwma = recentCloses.reduce((a, b) => a + b, 0) / recentCloses.length;
    }

    const trList = [];
    const startIndex = Math.max(1, n - volLen);
    for (let i = startIndex; i < n; i++) {
        const tr = Math.max(
            highs[i] - lows[i],
            Math.abs(highs[i] - closes[i - 1]),
            Math.abs(lows[i] - closes[i - 1])
        );
        trList.push(tr);
    }
    const atr = trList.length > 0 ? (trList.reduce((a, b) => a + b, 0) / trList.length) : (currHigh - currLow);
    const rangoAtr = atr * volMult;
    const franjaSup = baseVwma + rangoAtr;
    const franjaInf = baseVwma - rangoAtr;

    const f_active = (currClose > franjaSup) || (currClose < franjaInf);
    const f_desc = `[F] Franja Dinámica: ${f_active ? 'FUERA DE BANDA' : 'Dentro'} (P: ${currClose}, Sup: ${franjaSup.toFixed(2)}, Inf: ${franjaInf.toFixed(2)})`;

    // 3. EMA 100 (E)
    const emaLen = 100;
    const k = 2.0 / (emaLen + 1.0);
    let ema = closes[0];
    for (let i = 1; i < closes.length; i++) {
        ema = (closes[i] * k) + (ema * (1.0 - k));
    }

    const vf_confluence = v_active && f_active;
    let ema_color = 'inactive';
    let e_active = false;
    let e_desc = '';

    if (vf_confluence) {
        if (ema > currClose) {
            ema_color = 'blue';
            e_desc = `[E] EMA 100: AZUL (EMA ${ema.toFixed(2)} > Precio ${currClose})`;
        } else {
            ema_color = 'green';
            e_desc = `[E] EMA 100: VERDE (Precio ${currClose} >= EMA ${ema.toFixed(2)})`;
        }
        e_active = true;
    } else {
        e_active = (currClose >= ema) || (currLow <= ema && ema <= currHigh);
        ema_color = e_active ? 'green' : 'inactive';
        e_desc = `[E] EMA 100: ${e_active ? 'SOBRE/CRUZANDO' : 'Por debajo'} (EMA: ${ema.toFixed(2)})`;
    }

    return {
        v_active,
        f_active,
        e_active,
        ema_color,
        vf_confluence,
        v_desc,
        f_desc,
        e_desc
    };
}

// ==========================================
// 4. WEBSOCKET & REST API DE BINANCE
// ==========================================
async function fetchInitialKlines() {
    for (const t of state.tickers) {
        const pair = t.pair.toLowerCase();
        const symbolUpper = pair.toUpperCase();
        
        try {
            // Fetch timeframe candles
            const res = await fetch(`https://api.binance.com/api/v3/klines?symbol=${symbolUpper}&interval=${state.timeframe}&limit=120`);
            if (res.ok) {
                const raw = await res.json();
                const candles = raw.map(c => ({
                    open: parseFloat(c[1]),
                    high: parseFloat(c[2]),
                    low: parseFloat(c[3]),
                    close: parseFloat(c[4]),
                    vol: parseFloat(c[5])
                }));
                if (!state.tickerData[pair]) state.tickerData[pair] = {};
                state.tickerData[pair].candleHistory = candles;
                state.tickerData[pair].indicators = calculateIndicators(candles);
            }
        } catch (e) {
            console.warn(`REST error for ${pair}:`, e);
        }

        try {
            // Fetch 1m candles for shock detector
            const res1m = await fetch(`https://api.binance.com/api/v3/klines?symbol=${symbolUpper}&interval=1m&limit=25`);
            if (res1m.ok) {
                const raw1m = await res1m.json();
                const c1m = raw1m.map(c => ({
                    open: parseFloat(c[1]),
                    high: parseFloat(c[2]),
                    low: parseFloat(c[3]),
                    close: parseFloat(c[4]),
                    vol: parseFloat(c[5])
                }));
                if (!state.tickerData[pair]) state.tickerData[pair] = {};
                state.tickerData[pair].candle1mHistory = c1m;
            }
        } catch (e) {
            console.warn(`REST 1m error for ${pair}:`, e);
        }
    }
    renderTickers();
}

function initBinanceWebSocket() {
    if (state.ws) {
        try { state.ws.close(); } catch(e) {}
    }

    const streams = [];
    for (const t of state.tickers) {
        const p = t.pair.toLowerCase();
        if (p) {
            streams.push(`${p}@ticker`);
            streams.push(`${p}@kline_${state.timeframe}`);
            if (state.timeframe !== '1m') {
                streams.push(`${p}@kline_1m`);
            }
        }
    }

    if (streams.length === 0) return;

    updateWsStatus('connecting', 'Conectando');
    const wsUrl = `wss://stream.binance.com:9443/stream?streams=${streams.join('/')}`;
    
    state.ws = new WebSocket(wsUrl);

    state.ws.onopen = () => {
        updateWsStatus('connected', 'En Vivo');
    };

    state.ws.onmessage = (event) => {
        try {
            const payload = JSON.parse(event.data);
            const stream = payload.stream || '';
            const data = payload.data || {};

            if (stream.includes('@ticker')) {
                const pair = (data.s || '').toLowerCase();
                const price = parseFloat(data.c);
                const change = parseFloat(data.P);
                if (pair && !isNaN(price)) {
                    if (!state.tickerData[pair]) state.tickerData[pair] = {};
                    state.tickerData[pair].price = price;
                    state.tickerData[pair].change = change;
                    updateSingleTickerUI(pair);
                }
            } else if (stream.includes('@kline')) {
                const k = data.k || {};
                const pair = (k.s || '').toLowerCase();
                if (!pair) return;

                const candle = {
                    open: parseFloat(k.o),
                    high: parseFloat(k.h),
                    low: parseFloat(k.l),
                    close: parseFloat(k.c),
                    vol: parseFloat(k.v)
                };
                const isClosed = k.x;
                const interval = k.i;

                if (!state.tickerData[pair]) state.tickerData[pair] = {};

                // Main Timeframe Kline
                if (interval === state.timeframe) {
                    let history = state.tickerData[pair].candleHistory || [];
                    if (history.length > 0) {
                        if (isClosed) {
                            history[history.length - 1] = candle;
                            history.push(candle);
                            if (history.length > 150) history.shift();
                        } else {
                            history[history.length - 1] = candle;
                        }
                    } else {
                        history.push(candle);
                    }
                    state.tickerData[pair].candleHistory = history;
                    const prevConfluence = state.tickerData[pair].indicators ? state.tickerData[pair].indicators.vf_confluence : false;
                    const newInd = calculateIndicators(history);
                    state.tickerData[pair].indicators = newInd;

                    if (!prevConfluence && newInd.vf_confluence) {
                        playSoundPattern('confluence_vf');
                    }
                    updateSingleTickerUI(pair);
                }

                // 1m Kline for Flash Shock Detector
                if (interval === '1m') {
                    let h1m = state.tickerData[pair].candle1mHistory || [];
                    if (h1m.length > 0) {
                        if (isClosed) {
                            h1m[h1m.length - 1] = candle;
                            h1m.push(candle);
                            if (h1m.length > 30) h1m.shift();
                        } else {
                            h1m[h1m.length - 1] = candle;
                        }
                    } else {
                        h1m.push(candle);
                    }
                    state.tickerData[pair].candle1mHistory = h1m;

                    // Shock detection
                    if (h1m.length >= 10) {
                        const cOpen = candle.open;
                        const cClose = candle.close;
                        const cVol = candle.vol;

                        if (cOpen > 0) {
                            const pctChange1m = ((cClose - cOpen) / cOpen) * 100.0;
                            const prevVols = h1m.slice(0, -1).map(x => x.vol);
                            const meanVol = prevVols.reduce((a, b) => a + b, 0) / (prevVols.length || 1);
                            const volRatio = meanVol > 0 ? (cVol / meanVol) : 1.0;

                            if (Math.abs(pctChange1m) >= state.shockThreshold && volRatio >= state.shockVolMult) {
                                const now = Date.now();
                                const lastShockTime = state.tickerData[pair].lastShockTime || 0;
                                if (now - lastShockTime > 30000) { // Cooldown 30s
                                    state.tickerData[pair].lastShockTime = now;
                                    const shockType = pctChange1m < 0 ? 'DUMP' : 'PUMP';
                                    handleFlashShockDetected(pair, shockType, pctChange1m, volRatio);
                                }
                            }
                        }
                    }
                }
            }
        } catch (e) {
            console.error('WS parse error:', e);
        }
    };

    state.ws.onerror = (e) => {
        console.warn('WS error:', e);
        updateWsStatus('disconnected', 'Error');
    };

    state.ws.onclose = () => {
        updateWsStatus('disconnected', 'Reconectando');
        setTimeout(initBinanceWebSocket, 3000);
    };
}

function updateWsStatus(status, text) {
    const dot = document.getElementById('wsStatusDot');
    const label = document.getElementById('wsStatusText');
    if (!dot || !label) return;

    label.textContent = text;
    if (status === 'connected') {
        dot.className = 'w-2.5 h-2.5 rounded-full bg-emerald-500 shadow-[0_0_8px_rgba(16,185,129,0.8)]';
    } else if (status === 'connecting') {
        dot.className = 'w-2.5 h-2.5 rounded-full bg-amber-500 animate-ping';
    } else {
        dot.className = 'w-2.5 h-2.5 rounded-full bg-red-500';
    }
}

// ==========================================
// 5. DETECCIÓN FLASH SHOCK & MEGA SHOCK
// ==========================================
function handleFlashShockDetected(pair, type, pct, volRatio) {
    const tickerObj = state.tickers.find(t => t.pair.toLowerCase() === pair);
    const sym = tickerObj ? tickerObj.symbol : pair.replace('usdt', '').toUpperCase();

    // Guardar estado del shock en el ticker
    if (state.tickerData[pair]) {
        state.tickerData[pair].shock = {
            type,
            pct,
            volRatio,
            timestamp: Date.now()
        };
    }

    // Comprobar si hay una NOTICIA URGENTE RECIENTE (<45 min) -> MEGA SHOCK
    const hasRecentUrgentNews = state.recentUrgentNews && (Date.now() - state.recentUrgentNews.timestamp < 45 * 60 * 1000);

    if (hasRecentUrgentNews) {
        // 🚨 TRIGGER MEGA SHOCK
        triggerMegaShock(sym, type, pct, volRatio, state.recentUrgentNews);
    } else {
        // Alerta de Flash Shock regular
        if (type === 'DUMP') {
            playSoundPattern('flash_dump');
        } else {
            playSoundPattern('flash_pump');
        }
    }

    updateSingleTickerUI(pair);
}

function triggerMegaShock(symbol, type, pct, volRatio, newsItem) {
    playSoundPattern('mega_shock');

    const toast = document.getElementById('megaShockToast');
    const toastType = document.getElementById('megaShockType');
    const toastSymbol = document.getElementById('megaShockSymbol');
    const toastTitle = document.getElementById('megaShockNewsTitle');
    const toastMetrics = document.getElementById('megaShockMetrics');
    const toastLink = document.getElementById('megaShockLink');

    if (!toast) return;

    toastType.textContent = type === 'DUMP' ? '💥 MEGA DUMP' : '🚀 MEGA PUMP';
    toastType.className = type === 'DUMP' 
        ? 'bg-red-600 text-white text-[11px] font-mono font-extrabold px-2 py-0.5 rounded-full uppercase tracking-wider'
        : 'bg-emerald-600 text-white text-[11px] font-mono font-extrabold px-2 py-0.5 rounded-full uppercase tracking-wider';

    toastSymbol.textContent = symbol;
    toastTitle.textContent = newsItem ? newsItem.title : 'Noticia Macro / Geopolítica Urgente';
    toastMetrics.textContent = `${pct > 0 ? '+' : ''}${pct.toFixed(2)}% (1m) | Vol: ${volRatio.toFixed(1)}x`;
    toastLink.href = newsItem ? newsItem.link : '#';

    toast.classList.remove('hidden');

    // Auto ocultar tras 14 segundos
    clearTimeout(toast.hideTimeout);
    toast.hideTimeout = setTimeout(() => {
        toast.classList.add('hidden');
    }, 14000);
}

// ==========================================
// 6. MOTOR DE NOTICIAS FLASH (<2H) AUTÓNOMO
// ==========================================
async function fetchNewsFeed() {
    try {
        let items = [];
        
        // 1. Intentar descargar Google News RSS vía proxies CORS redundantes
        const rssQueryUrl = "https://news.google.com/rss/search?q=(crypto+OR+bitcoin+OR+fed+OR+guerra+OR+inflacion+OR+economy)+when:2h&hl=es-419&gl=US&ceid=US:es-419";
        const proxyUrls = [
            `https://api.allorigins.win/get?url=${encodeURIComponent(rssQueryUrl)}`,
            `https://api.codetabs.com/v1/proxy?quest=${encodeURIComponent(rssQueryUrl)}`,
            `/api/news`
        ];

        for (const pUrl of proxyUrls) {
            try {
                const res = await fetch(pUrl, { cache: 'no-store' });
                if (!res.ok) continue;

                let rawXml = '';
                if (pUrl.includes('allorigins')) {
                    const data = await res.json();
                    rawXml = data.contents || '';
                } else if (pUrl === '/api/news') {
                    const data = await res.json();
                    if (Array.isArray(data.items) && data.items.length > 0) {
                        items = data.items;
                        break;
                    }
                } else {
                    rawXml = await res.text();
                }

                if (rawXml && rawXml.includes('<rss') || rawXml.includes('<item')) {
                    const parser = new DOMParser();
                    const xmlDoc = parser.parseFromString(rawXml, "text/xml");
                    const xmlItems = xmlDoc.querySelectorAll("item");
                    
                    const now = Date.now();
                    xmlItems.forEach(it => {
                        const rawTitle = it.querySelector("title")?.textContent || "";
                        const link = it.querySelector("link")?.textContent || "";
                        const pubDateStr = it.querySelector("pubDate")?.textContent || "";

                        let minsAgo = 0;
                        let pubTs = now;
                        if (pubDateStr) {
                            pubTs = new Date(pubDateStr).getTime();
                            minsAgo = Math.max(0, Math.floor((now - pubTs) / 60000));
                        }

                        if (minsAgo <= 180) { // < 3 horas
                            let source = "Noticias";
                            let cleanTitle = rawTitle;
                            if (rawTitle.includes(" - ")) {
                                const parts = rawTitle.split(" - ");
                                source = parts.pop();
                                cleanTitle = parts.join(" - ");
                            }

                            const sourceLower = source.toLowerCase();
                            const titleLower = cleanTitle.toLowerCase();
                            const isTrusted = TRUSTED_SOURCES.some(s => sourceLower.includes(s) || titleLower.includes(s));
                            const isUrgent = URGENT_KEYWORDS.some(k => titleLower.includes(k));

                            let timeStr = minsAgo < 60 ? `Hace ${minsAgo}m` : `Hace ${Math.floor(minsAgo / 60)}h ${minsAgo % 60}m`;

                            items.push({
                                title: cleanTitle,
                                full_title: rawTitle,
                                link,
                                source,
                                pub_ts: pubTs,
                                mins_ago: minsAgo,
                                time_str: timeStr,
                                is_urgent: isUrgent,
                                is_trusted: isTrusted
                            });
                        }
                    });

                    if (items.length > 0) break;
                }
            } catch (e) {
                // Siguiente proxy
            }
        }

        // 2. Fallback de emergencia a CryptoCompare News API si Google News RSS está inaccesible
        if (items.length === 0) {
            try {
                const res = await fetch('https://min-api.cryptocompare.com/data/v2/news/?lang=ES');
                if (res.ok) {
                    const ccData = await res.json();
                    const rawList = ccData.Data || [];
                    const now = Math.floor(Date.now() / 1000);

                    rawList.slice(0, 30).forEach(n => {
                        const minsAgo = Math.max(0, Math.floor((now - n.published_on) / 60));
                        if (minsAgo <= 180) {
                            const isUrgent = URGENT_KEYWORDS.some(k => n.title.toLowerCase().includes(k) || n.body.toLowerCase().includes(k));
                            const timeStr = minsAgo < 60 ? `Hace ${minsAgo}m` : `Hace ${Math.floor(minsAgo / 60)}h ${minsAgo % 60}m`;

                            items.push({
                                title: n.title,
                                full_title: n.title,
                                link: n.url,
                                source: n.source_info?.name || 'CryptoCompare',
                                pub_ts: n.published_on * 1000,
                                mins_ago: minsAgo,
                                time_str: timeStr,
                                is_urgent: isUrgent,
                                is_trusted: true
                            });
                        }
                    });
                }
            } catch (e) {
                console.warn('CryptoCompare fallback error:', e);
            }
        }

        // Ordenar cronológicamente (más reciente arriba)
        items.sort((a, b) => a.mins_ago - b.mins_ago);
        state.latestNews = items;

        // Comprobar si hay noticias urgentes recientes
        const urgentItems = items.filter(it => it.is_urgent && it.mins_ago <= 45);
        if (urgentItems.length > 0) {
            const topUrgent = urgentItems[0];
            if (!state.recentUrgentNews || state.recentUrgentNews.title !== topUrgent.title) {
                state.recentUrgentNews = { ...topUrgent, timestamp: Date.now() };
                state.newsAlertEndTime = Date.now() + 180000; // 3 minutos de alerta visual
                playSoundPattern('urgent_news');
            }
        }

        updateNewsButtonUI();
        renderNewsModal();
    } catch (e) {
        console.warn('News feed fetch error:', e);
    }
}

function updateNewsButtonUI() {
    const btnNews = document.getElementById('btnNews');
    const countdown = document.getElementById('newsCountdown');
    if (!btnNews || !countdown) return;

    const now = Date.now();
    if (state.newsAlertEndTime > now) {
        const remainingSecs = Math.ceil((state.newsAlertEndTime - now) / 1000);
        const mins = Math.floor(remainingSecs / 60);
        const secs = remainingSecs % 60;
        
        btnNews.classList.add('urgent-active');
        countdown.classList.remove('hidden');
        countdown.textContent = `${mins}:${secs < 10 ? '0' : ''}${secs}`;
    } else {
        btnNews.classList.remove('urgent-active');
        countdown.classList.add('hidden');
    }
}

// ==========================================
// 7. RENDERIZADO DE INTERFAZ (DASHBOARD & COMPACT)
// ==========================================
function renderTickers() {
    const container = document.getElementById('tickersContainer');
    const compactRow = document.getElementById('compactTickersRow');
    if (!container || !compactRow) return;

    let dashboardHtml = '';
    let compactHtml = '';

    for (const t of state.tickers) {
        const pair = t.pair.toLowerCase();
        const data = state.tickerData[pair] || {};
        const price = data.price !== undefined ? data.price.toFixed(t.decimals) : '---';
        const change = data.change !== undefined ? data.change : 0;
        const changeSign = change > 0 ? '+' : '';
        const changeColor = change >= 0 ? 'text-emerald-400' : 'text-red-400';
        const changeBg = change >= 0 ? 'bg-emerald-950/60 border-emerald-800/60' : 'bg-red-950/60 border-red-800/60';

        const ind = data.indicators || {};
        const vActive = ind.v_active;
        const fActive = ind.f_active;
        const eColor = ind.ema_color || 'inactive';

        // Estilos de badges V, F, E
        const vClass = vActive ? 'bg-emerald-900/90 text-emerald-300 border-emerald-600' : 'bg-zinc-800/60 text-zinc-500 border-zinc-700/60';
        const fClass = fActive ? 'bg-emerald-900/90 text-emerald-300 border-emerald-600' : 'bg-zinc-800/60 text-zinc-500 border-zinc-700/60';
        
        let eClass = 'bg-zinc-800/60 text-zinc-500 border-zinc-700/60';
        if (eColor === 'blue') eClass = 'bg-blue-900/90 text-blue-300 border-blue-600 font-bold';
        else if (eColor === 'green') eClass = 'bg-emerald-900/90 text-emerald-300 border-emerald-600 font-bold';

        // Badge Shock si existe y fue en los últimos 30s
        let shockBadgeHtml = '';
        if (data.shock && (Date.now() - data.shock.timestamp < 30000)) {
            const s = data.shock;
            const sBg = s.type === 'DUMP' ? 'bg-red-900/90 text-red-200 border-red-600' : 'bg-emerald-900/90 text-emerald-200 border-emerald-600';
            shockBadgeHtml = `<span class="text-[10px] font-mono px-1.5 py-0.5 rounded border ${sBg} animate-pulse font-bold">${s.type === 'DUMP' ? '📉 DUMP' : '🚀 PUMP'} ${s.pct.toFixed(1)}%</span>`;
        }

        // 1. Tarjeta Dashboard
        dashboardHtml += `
            <div id="card_${pair}" class="glass-card rounded-2xl p-3.5 border border-zinc-800/80 transition-all hover:border-zinc-700">
                <div class="flex items-center justify-between">
                    <!-- Símbolo y Badges -->
                    <div class="flex items-center gap-2">
                        <span class="text-base font-extrabold tracking-wide text-zinc-100">${t.symbol}</span>
                        <div class="flex items-center gap-1">
                            <span title="${ind.v_desc || 'VIX Fix'}" class="w-6 h-6 flex items-center justify-center text-xs font-mono rounded border ${vClass}">V</span>
                            <span title="${ind.f_desc || 'Franja Dinámica'}" class="w-6 h-6 flex items-center justify-center text-xs font-mono rounded border ${fClass}">F</span>
                            <span title="${ind.e_desc || 'EMA 100'}" class="w-6 h-6 flex items-center justify-center text-xs font-mono rounded border ${eClass}">E</span>
                        </div>
                        ${shockBadgeHtml}
                    </div>

                    <!-- Precio y Cambio % -->
                    <div class="text-right">
                        <div class="text-base font-mono-num font-bold text-zinc-100" id="price_${pair}">${price}</div>
                        <div class="inline-block text-[11px] font-mono font-semibold px-1.5 py-0.5 rounded border ${changeBg} ${changeColor}" id="change_${pair}">
                            ${changeSign}${change.toFixed(2)}%
                        </div>
                    </div>
                </div>
            </div>
        `;

        // 2. Elemento Compacto (Barra horizontal)
        compactHtml += `
            <div id="compact_${pair}" class="inline-flex items-center gap-1.5 bg-zinc-900/90 border border-zinc-800 px-2.5 py-1.5 rounded-xl">
                <span class="text-xs font-extrabold text-zinc-200">${t.symbol}</span>
                <span class="text-xs font-mono-num font-bold text-zinc-100" id="cprice_${pair}">${price}</span>
                <span class="text-[10px] font-mono font-semibold ${changeColor}" id="cchange_${pair}">${changeSign}${change.toFixed(2)}%</span>
                <div class="flex items-center gap-0.5">
                    <span class="text-[9px] font-mono w-4 h-4 flex items-center justify-center rounded border ${vClass}">V</span>
                    <span class="text-[9px] font-mono w-4 h-4 flex items-center justify-center rounded border ${fClass}">F</span>
                    <span class="text-[9px] font-mono w-4 h-4 flex items-center justify-center rounded border ${eClass}">E</span>
                </div>
            </div>
        `;
    }

    container.innerHTML = dashboardHtml;
    compactRow.innerHTML = compactHtml;
}

function updateSingleTickerUI(pair) {
    const data = state.tickerData[pair];
    if (!data) return;

    const tickerObj = state.tickers.find(t => t.pair.toLowerCase() === pair);
    if (!tickerObj) return;

    const priceEl = document.getElementById(`price_${pair}`);
    const changeEl = document.getElementById(`change_${pair}`);
    const cpriceEl = document.getElementById(`cprice_${pair}`);
    const cchangeEl = document.getElementById(`cchange_${pair}`);

    if (priceEl && data.price !== undefined) {
        priceEl.textContent = data.price.toFixed(tickerObj.decimals);
    }
    if (cpriceEl && data.price !== undefined) {
        cpriceEl.textContent = data.price.toFixed(tickerObj.decimals);
    }

    if (data.change !== undefined) {
        const changeSign = data.change > 0 ? '+' : '';
        const changeStr = `${changeSign}${data.change.toFixed(2)}%`;
        const changeColor = data.change >= 0 ? 'text-emerald-400' : 'text-red-400';
        const changeBg = data.change >= 0 ? 'bg-emerald-950/60 border-emerald-800/60' : 'bg-red-950/60 border-red-800/60';

        if (changeEl) {
            changeEl.textContent = changeStr;
            changeEl.className = `inline-block text-[11px] font-mono font-semibold px-1.5 py-0.5 rounded border ${changeBg} ${changeColor}`;
        }
        if (cchangeEl) {
            cchangeEl.textContent = changeStr;
            cchangeEl.className = `text-[10px] font-mono font-semibold ${changeColor}`;
        }
    }

    // Actualizar badges de indicadores si han cambiado
    const cardEl = document.getElementById(`card_${pair}`);
    const compactEl = document.getElementById(`compact_${pair}`);
    if (cardEl && data.indicators) {
        // Redibujamos la fila para mantener los colores de V, F, E y Badges de Shock
        renderTickers();
    }
}

function renderNewsModal() {
    const listContainer = document.getElementById('newsListContainer');
    const totalCountEl = document.getElementById('newsTotalCount');
    if (!listContainer) return;

    if (state.latestNews.length === 0) {
        listContainer.innerHTML = '<div class="text-center py-8 text-zinc-500 text-xs">No hay noticias en las últimas 2 horas.</div>';
        if (totalCountEl) totalCountEl.textContent = '0 noticias';
        return;
    }

    if (totalCountEl) totalCountEl.textContent = `${state.latestNews.length} noticias (<2h)`;

    let html = '';
    state.latestNews.forEach(item => {
        const urgentBadge = item.is_urgent 
            ? '<span class="bg-red-950 text-red-300 border border-red-700 text-[9px] font-bold px-1.5 py-0.5 rounded uppercase">Urgente</span>' 
            : '';
        const trustedBadge = item.is_trusted 
            ? '<span class="bg-blue-950 text-blue-300 border border-blue-800 text-[9px] font-semibold px-1.5 py-0.5 rounded">Verificada</span>' 
            : '';

        html += `
            <a href="${item.link}" target="_blank" class="block bg-zinc-900/80 hover:bg-zinc-800/90 border border-zinc-800 rounded-xl p-3 transition-all active:scale-[0.98]">
                <div class="flex items-center gap-1.5 mb-1">
                    ${urgentBadge}
                    ${trustedBadge}
                    <span class="text-[10px] font-mono text-zinc-400">${item.source}</span>
                    <span class="text-[10px] text-zinc-500">&bull;</span>
                    <span class="text-[10px] font-mono text-amber-400 font-semibold">${item.time_str}</span>
                </div>
                <h3 class="text-xs font-semibold text-zinc-100 leading-snug">${item.title}</h3>
            </a>
        `;
    });

    listContainer.innerHTML = html;
}

// ==========================================
// 8. SCREEN WAKE LOCK (MANTENER PANTALLA ACTIVA)
// ==========================================
async function toggleWakeLock() {
    const btn = document.getElementById('btnWakeLock');
    const icon = document.getElementById('wakeLockIcon');

    if (!('wakeLock' in navigator)) {
        alert('Tu navegador no soporta Screen Wake Lock directamente.');
        return;
    }

    try {
        if (state.wakeLock !== null) {
            await state.wakeLock.release();
            state.wakeLock = null;
            if (btn) btn.classList.remove('bg-yellow-950/80', 'border-yellow-600');
            if (icon) icon.textContent = '💡';
        } else {
            state.wakeLock = await navigator.wakeLock.request('screen');
            if (btn) btn.classList.add('bg-yellow-950/80', 'border-yellow-600');
            if (icon) icon.textContent = '⚡';
            
            state.wakeLock.addEventListener('release', () => {
                state.wakeLock = null;
                if (btn) btn.classList.remove('bg-yellow-950/80', 'border-yellow-600');
                if (icon) icon.textContent = '💡';
            });
        }
    } catch (err) {
        console.warn('Wake Lock error:', err);
    }
}

// ==========================================
// 9. EVENT LISTENERS & INICIALIZACIÓN
// ==========================================
document.addEventListener('DOMContentLoaded', () => {
    // 1. Selector de Timeframe
    const selTf = document.getElementById('selectTimeframe');
    if (selTf) {
        selTf.value = state.timeframe;
        selTf.addEventListener('change', (e) => {
            state.timeframe = e.target.value;
            localStorage.setItem('crypto_timeframe', state.timeframe);
            fetchInitialKlines().then(() => initBinanceWebSocket());
        });
    }

    // 2. Wake Lock
    const btnWake = document.getElementById('btnWakeLock');
    if (btnWake) btnWake.addEventListener('click', toggleWakeLock);

    // 3. Audio Mute Toggle
    const btnMute = document.getElementById('btnSoundMute');
    const soundIcon = document.getElementById('soundIcon');
    if (btnMute && soundIcon) {
        soundIcon.textContent = state.soundMuted ? '🔇' : '🔊';
        btnMute.addEventListener('click', () => {
            state.soundMuted = !state.soundMuted;
            localStorage.setItem('crypto_sound_muted', state.soundMuted);
            soundIcon.textContent = state.soundMuted ? '🔇' : '🔊';
            getAudioContext(); // desbloquear audio
        });
    }

    // Prompt de habilitar audio
    const btnPrompt = document.getElementById('btnAudioTestPrompt');
    if (btnPrompt) {
        btnPrompt.addEventListener('click', () => {
            getAudioContext();
            playSoundPattern('soft_beep');
            btnPrompt.textContent = '✓ Audio Activado';
            setTimeout(() => { btnPrompt.style.display = 'none'; }, 2000);
        });
    }

    // 4. Toggle Modo Compacto / Dashboard
    const btnCompact = document.getElementById('btnToggleCompact');
    const viewDash = document.getElementById('viewDashboard');
    const viewComp = document.getElementById('viewCompact');
    if (btnCompact && viewDash && viewComp) {
        const updateView = () => {
            if (state.compactView) {
                viewDash.classList.add('hidden');
                viewComp.classList.remove('hidden');
                btnCompact.classList.add('bg-blue-950', 'border-blue-700');
            } else {
                viewDash.classList.remove('hidden');
                viewComp.classList.add('hidden');
                btnCompact.classList.remove('bg-blue-950', 'border-blue-700');
            }
        };
        updateView();

        btnCompact.addEventListener('click', () => {
            state.compactView = !state.compactView;
            localStorage.setItem('crypto_compact_view', state.compactView);
            updateView();
        });
    }

    // 5. Modales (Noticias y Ajustes)
    const btnNews = document.getElementById('btnNews');
    const modalNews = document.getElementById('modalNews');
    const btnCloseNews = document.getElementById('btnCloseNewsModal');
    const btnRefreshNews = document.getElementById('btnRefreshNews');

    if (btnNews && modalNews && btnCloseNews) {
        btnNews.addEventListener('click', () => {
            modalNews.classList.remove('hidden');
            renderNewsModal();
        });
        btnCloseNews.addEventListener('click', () => modalNews.classList.add('hidden'));
        if (btnRefreshNews) btnRefreshNews.addEventListener('click', fetchNewsFeed);
    }

    const btnSettings = document.getElementById('btnSettings');
    const modalSettings = document.getElementById('modalSettings');
    const btnCloseSettings = document.getElementById('btnCloseSettingsModal');

    if (btnSettings && modalSettings && btnCloseSettings) {
        btnSettings.addEventListener('click', () => {
            modalSettings.classList.remove('hidden');
            renderSettingsList();
        });
        btnCloseSettings.addEventListener('click', () => modalSettings.classList.add('hidden'));
    }

    // Toast Mega Shock Close
    const btnCloseMega = document.getElementById('btnCloseMegaShock');
    const megaToast = document.getElementById('megaShockToast');
    if (btnCloseMega && megaToast) {
        btnCloseMega.addEventListener('click', () => megaToast.classList.add('hidden'));
    }

    // 6. CENTRO DE SIMULACIÓN EN AJUSTES
    document.getElementById('simMegaShock')?.addEventListener('click', () => {
        getAudioContext();
        triggerMegaShock('BTC', 'DUMP', -3.2, 4.8, {
            title: 'Simulación: Conflicto Geopolítico / Decisión Sorpresa de Tasas',
            link: '#'
        });
    });

    document.getElementById('simUrgentNews')?.addEventListener('click', () => {
        getAudioContext();
        playSoundPattern('urgent_news');
        state.newsAlertEndTime = Date.now() + 180000;
        updateNewsButtonUI();
    });

    document.getElementById('simFlashDump')?.addEventListener('click', () => {
        getAudioContext();
        playSoundPattern('flash_dump');
    });

    document.getElementById('simFlashPump')?.addEventListener('click', () => {
        getAudioContext();
        playSoundPattern('flash_pump');
    });

    document.getElementById('simConfluence')?.addEventListener('click', () => {
        getAudioContext();
        playSoundPattern('confluence_vf');
    });

    // 7. Configuración & Guardar
    const btnSaveCfg = document.getElementById('btnSaveSettings');
    if (btnSaveCfg) {
        btnSaveCfg.addEventListener('click', () => {
            const thresh = parseFloat(document.getElementById('cfgShockThreshold')?.value || '1.5');
            const volMult = parseFloat(document.getElementById('cfgShockVolMult')?.value || '2.5');
            const vib = document.getElementById('cfgVibration')?.checked;

            state.shockThreshold = thresh;
            state.shockVolMult = volMult;
            state.vibrationEnabled = vib;

            localStorage.setItem('crypto_shock_thresh', thresh);
            localStorage.setItem('crypto_shock_vol_mult', volMult);
            localStorage.setItem('crypto_vibration', vib);
            localStorage.setItem('crypto_tickers', JSON.stringify(state.tickers));

            modalSettings.classList.add('hidden');
            fetchInitialKlines().then(() => initBinanceWebSocket());
        });
    }

    // Restablecer Tickers
    document.getElementById('btnResetTickers')?.addEventListener('click', () => {
        state.tickers = JSON.parse(JSON.stringify(DEFAULT_TICKERS));
        renderSettingsList();
    });

    // Agregar Ticker
    document.getElementById('btnAddTicker')?.addEventListener('click', () => {
        const sym = document.getElementById('inputNewSymbol')?.value.trim().toUpperCase();
        const pair = document.getElementById('inputNewPair')?.value.trim().toLowerCase();
        const dec = parseInt(document.getElementById('inputNewDecimals')?.value || '2');

        if (sym && pair) {
            state.tickers.push({ symbol: sym, pair: pair, decimals: dec });
            document.getElementById('inputNewSymbol').value = '';
            document.getElementById('inputNewPair').value = '';
            renderSettingsList();
        }
    });

    // Iniciar
    renderTickers();
    fetchInitialKlines().then(() => initBinanceWebSocket());
    fetchNewsFeed();

    // Loops de actualización
    setInterval(fetchNewsFeed, 60000); // Feed de noticias cada 60s
    setInterval(updateNewsButtonUI, 1000); // Contador regresivo 3 min
    setInterval(() => {
        const d = new Date();
        const timeEl = document.getElementById('lastUpdated');
        if (timeEl) timeEl.textContent = `Actualizado ${d.toLocaleTimeString()}`;
    }, 1000);
});

function renderSettingsList() {
    const list = document.getElementById('tickersConfigList');
    if (!list) return;

    let html = '';
    state.tickers.forEach((t, index) => {
        html += `
            <div class="flex items-center justify-between bg-zinc-800/80 px-2.5 py-1.5 rounded-lg border border-zinc-700/60">
                <div class="flex items-center gap-2">
                    <span class="font-bold text-zinc-100">${t.symbol}</span>
                    <span class="text-[10px] font-mono text-zinc-400">(${t.pair})</span>
                    <span class="text-[10px] text-zinc-500">${t.decimals} dec</span>
                </div>
                <button onclick="removeTicker(${index})" class="text-red-400 hover:text-red-300 px-1 font-bold text-xs">✕</button>
            </div>
        `;
    });
    list.innerHTML = html;
}

window.removeTicker = function(index) {
    state.tickers.splice(index, 1);
    renderSettingsList();
};
