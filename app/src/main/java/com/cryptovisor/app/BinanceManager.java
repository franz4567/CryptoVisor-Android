package com.cryptovisor.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class BinanceManager {
    private static final String TAG = "BinanceManager";

    public interface BinanceListener {
        void onTickerUpdated(String pair, double price, double changePct);
        void onIndicatorsUpdated(String pair, IndicatorCalculator.IndicatorResult ind);
        void onFlashShockDetected(String symbol, String type, double pctChange, double volRatio);
        void onStatusChanged(String status);
    }

    public static class TickerConfig {
        public String symbol;
        public String pair;
        public int decimals;

        public TickerConfig(String symbol, String pair, int decimals) {
            this.symbol = symbol;
            this.pair = pair.toLowerCase();
            this.decimals = decimals;
        }
    }

    private final List<TickerConfig> tickers;
    private String timeframe;
    private final BinanceListener listener;
    private final OkHttpClient client;
    private WebSocket webSocket;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;

    private final Map<String, List<IndicatorCalculator.Candle>> candleHistory = new ConcurrentHashMap<>();
    private final Map<String, List<IndicatorCalculator.Candle>> candle1mHistory = new ConcurrentHashMap<>();
    private final Map<String, Long> lastShockTime = new ConcurrentHashMap<>();

    private double shockThreshold = 1.5;
    private double shockVolMult = 2.5;

    public BinanceManager(List<TickerConfig> tickers, String timeframe, BinanceListener listener) {
        this.tickers = tickers;
        this.timeframe = timeframe;
        this.listener = listener;
        this.client = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public void setTimeframe(String tf) {
        this.timeframe = tf;
        if (isRunning) {
            start();
        }
    }

    public void setShockParams(double threshold, double volMult) {
        this.shockThreshold = threshold;
        this.shockVolMult = volMult;
    }

    public void start() {
        isRunning = true;
        fetchInitialKlines();
        connectWebSocket();
    }

    public void stop() {
        isRunning = false;
        if (webSocket != null) {
            webSocket.close(1000, "App closed");
        }
    }

    private void fetchInitialKlines() {
        for (TickerConfig tc : tickers) {
            final String pair = tc.pair;
            String url = "https://api.binance.com/api/v3/klines?symbol=" + pair.toUpperCase() + "&interval=" + timeframe + "&limit=120";
            Request request = new Request.Builder().url(url).build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.w(TAG, "Klines fetch failed for " + pair + ": " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful() && response.body() != null) {
                        try {
                            String jsonData = response.body().string();
                            JsonArray raw = JsonParser.parseString(jsonData).getAsJsonArray();
                            List<IndicatorCalculator.Candle> candles = new ArrayList<>();
                            for (JsonElement el : raw) {
                                JsonArray arr = el.getAsJsonArray();
                                candles.add(new IndicatorCalculator.Candle(
                                        arr.get(1).getAsDouble(),
                                        arr.get(2).getAsDouble(),
                                        arr.get(3).getAsDouble(),
                                        arr.get(4).getAsDouble(),
                                        arr.get(5).getAsDouble()
                                ));
                            }
                            candleHistory.put(pair, Collections.synchronizedList(candles));
                            IndicatorCalculator.IndicatorResult ind = IndicatorCalculator.calculate(candles);
                            mainHandler.post(() -> listener.onIndicatorsUpdated(pair, ind));
                        } catch (Exception e) {
                            Log.e(TAG, "Parse klines error: " + e.getMessage());
                        }
                    }
                }
            });

            // Fetch 1m candles for shock detector
            String url1m = "https://api.binance.com/api/v3/klines?symbol=" + pair.toUpperCase() + "&interval=1m&limit=25";
            Request req1m = new Request.Builder().url(url1m).build();
            client.newCall(req1m).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {}

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful() && response.body() != null) {
                        try {
                            String jsonData = response.body().string();
                            JsonArray raw = JsonParser.parseString(jsonData).getAsJsonArray();
                            List<IndicatorCalculator.Candle> c1m = new ArrayList<>();
                            for (JsonElement el : raw) {
                                JsonArray arr = el.getAsJsonArray();
                                c1m.add(new IndicatorCalculator.Candle(
                                        arr.get(1).getAsDouble(),
                                        arr.get(2).getAsDouble(),
                                        arr.get(3).getAsDouble(),
                                        arr.get(4).getAsDouble(),
                                        arr.get(5).getAsDouble()
                                ));
                            }
                            candle1mHistory.put(pair, Collections.synchronizedList(c1m));
                        } catch (Exception ignored) {}
                    }
                }
            });
        }
    }

    private void connectWebSocket() {
        if (!isRunning) return;

        List<String> streams = new ArrayList<>();
        for (TickerConfig tc : tickers) {
            streams.add(tc.pair + "@ticker");
            streams.add(tc.pair + "@kline_" + timeframe);
            if (!"1m".equals(timeframe)) {
                streams.add(tc.pair + "@kline_1m");
            }
        }

        if (streams.isEmpty()) return;

        String wsUrl = "wss://stream.binance.com:9443/stream?streams=" + String.join("/", streams);
        Request request = new Request.Builder().url(wsUrl).build();

        mainHandler.post(() -> listener.onStatusChanged("connecting"));

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                mainHandler.post(() -> listener.onStatusChanged("connected"));
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JsonObject payload = JsonParser.parseString(text).getAsJsonObject();
                    String stream = payload.has("stream") ? payload.get("stream").getAsString() : "";
                    JsonObject data = payload.has("data") ? payload.getAsJsonObject("data") : null;

                    if (data == null) return;

                    if (stream.contains("@ticker")) {
                        String pair = data.has("s") ? data.get("s").getAsString().toLowerCase() : "";
                        double price = data.has("c") ? data.get("c").getAsDouble() : 0.0;
                        double change = data.has("P") ? data.get("P").getAsDouble() : 0.0;

                        mainHandler.post(() -> listener.onTickerUpdated(pair, price, change));
                    } else if (stream.contains("@kline")) {
                        JsonObject k = data.has("k") ? data.getAsJsonObject("k") : null;
                        if (k == null) return;

                        String pair = k.get("s").getAsString().toLowerCase();
                        IndicatorCalculator.Candle candle = new IndicatorCalculator.Candle(
                                k.get("o").getAsDouble(),
                                k.get("h").getAsDouble(),
                                k.get("l").getAsDouble(),
                                k.get("c").getAsDouble(),
                                k.get("v").getAsDouble()
                        );
                        boolean isClosed = k.get("x").getAsBoolean();
                        String interval = k.get("i").getAsString();

                        if (interval.equals(timeframe)) {
                            List<IndicatorCalculator.Candle> history = candleHistory.computeIfAbsent(pair, k1 -> Collections.synchronizedList(new ArrayList<>()));
                            synchronized (history) {
                                if (!history.isEmpty()) {
                                    if (isClosed) {
                                        history.set(history.size() - 1, candle);
                                        history.add(candle);
                                        if (history.size() > 150) history.remove(0);
                                    } else {
                                        history.set(history.size() - 1, candle);
                                    }
                                } else {
                                    history.add(candle);
                                }
                            }
                            IndicatorCalculator.IndicatorResult ind = IndicatorCalculator.calculate(history);
                            mainHandler.post(() -> listener.onIndicatorsUpdated(pair, ind));
                        }

                        if ("1m".equals(interval)) {
                            List<IndicatorCalculator.Candle> h1m = candle1mHistory.computeIfAbsent(pair, k1 -> Collections.synchronizedList(new ArrayList<>()));
                            synchronized (h1m) {
                                if (!h1m.isEmpty()) {
                                    if (isClosed) {
                                        h1m.set(h1m.size() - 1, candle);
                                        h1m.add(candle);
                                        if (h1m.size() > 30) h1m.remove(0);
                                    } else {
                                        h1m.set(h1m.size() - 1, candle);
                                    }
                                } else {
                                    h1m.add(candle);
                                }

                                if (h1m.size() >= 10 && candle.open > 0) {
                                    double pctChange1m = ((candle.close - candle.open) / candle.open) * 100.0;
                                    double sumPrevVol = 0;
                                    for (int i = 0; i < h1m.size() - 1; i++) {
                                        sumPrevVol += h1m.get(i).vol;
                                    }
                                    double meanVol = sumPrevVol / (h1m.size() - 1);
                                    double volRatio = meanVol > 0 ? (candle.vol / meanVol) : 1.0;

                                    if (Math.abs(pctChange1m) >= shockThreshold && volRatio >= shockVolMult) {
                                        long now = System.currentTimeMillis();
                                        long lastTime = lastShockTime.getOrDefault(pair, 0L);
                                        if (now - lastTime > 30000) { // Cooldown 30s
                                            lastShockTime.put(pair, now);
                                            String type = pctChange1m < 0 ? "DUMP" : "PUMP";
                                            String sym = pair.replace("usdt", "").toUpperCase();
                                            for (TickerConfig tc : tickers) {
                                                if (tc.pair.equals(pair)) {
                                                    sym = tc.symbol;
                                                    break;
                                                }
                                            }
                                            final String fSym = sym;
                                            mainHandler.post(() -> listener.onFlashShockDetected(fSym, type, pctChange1m, volRatio));
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "WS parse error: " + e.getMessage());
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                mainHandler.post(() -> {
                    listener.onStatusChanged("disconnected");
                    if (isRunning) {
                        mainHandler.postDelayed(BinanceManager.this::connectWebSocket, 4000);
                    }
                });
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                mainHandler.post(() -> {
                    listener.onStatusChanged("disconnected");
                    if (isRunning) {
                        mainHandler.postDelayed(BinanceManager.this::connectWebSocket, 4000);
                    }
                });
            }
        });
    }
}
