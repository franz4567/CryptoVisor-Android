package com.cryptovisor.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FloatingOverlayService extends Service implements BinanceManager.BinanceListener, NewsManager.NewsListener {
    private static final String CHANNEL_ID = "CryptoVisorServiceChannel";
    private static final int NOTIFICATION_ID = 101;

    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams params;

    private BinanceManager binanceManager;
    private NewsManager newsManager;
    private PowerManager.WakeLock wakeLock;
    private Vibrator vibrator;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, View> tickerItemViews = new HashMap<>();
    private final Map<String, Double> lastPrices = new HashMap<>();
    private final Map<String, Double> lastChanges = new HashMap<>();
    private final Map<String, IndicatorCalculator.IndicatorResult> lastIndicators = new HashMap<>();

    private TextView tvNewsBtn;
    private View megaShockToast;
    private TextView tvMegaShockType, tvMegaShockSymbol, tvMegaShockTitle, tvMegaShockMetrics;
    private long newsUrgentEndTime = 0;

    private final List<BinanceManager.TickerConfig> defaultTickers = Arrays.asList(
            new BinanceManager.TickerConfig("BTC", "btcusdt", 2),
            new BinanceManager.TickerConfig("ETH", "ethusdt", 2),
            new BinanceManager.TickerConfig("SOL", "solusdt", 3),
            new BinanceManager.TickerConfig("ZEC", "zecusdt", 2),
            new BinanceManager.TickerConfig("SUI", "suiusdt", 2),
            new BinanceManager.TickerConfig("UNI", "uniusdt", 2),
            new BinanceManager.TickerConfig("ENA", "enausdt", 2),
            new BinanceManager.TickerConfig("WLD", "wldusdt", 2)
    );

    @Override
    public void onCreate() {
        super.onCreate();
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        // 1. Iniciar Foreground Notification
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Iniciando Visor de Criptomonedas..."));

        // 2. Adquirir WakeLock para que MagicOS de Honor no duerma el CPU
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CryptoVisor::24hMonitoringLock");
            wakeLock.acquire(12 * 60 * 60 * 1000L); // 12 horas renovables
        }

        // 3. Crear Overlay Flotante
        initFloatingOverlay();

        // 4. Iniciar Binance y News Manager
        binanceManager = new BinanceManager(defaultTickers, "3m", this);
        newsManager = new NewsManager(this);

        binanceManager.start();
        newsManager.start();

        // Loop de actualización visual de noticias
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateNewsButtonUI();
                mainHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    @SuppressLint({"ClickableViewAccessibility", "InflateParams"})
    private void initFloatingOverlay() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        overlayView = inflater.inflate(R.layout.overlay_ticker_bar, null);

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 120; // Debajo del notch / cámara frontal

        // Referencias de UI
        LinearLayout llTickers = overlayView.findViewById(R.id.llTickersContainer);
        tvNewsBtn = overlayView.findViewById(R.id.tvOverlayNews);
        View btnClose = overlayView.findViewById(R.id.btnOverlayClose);
        View btnDragHandle = overlayView.findViewById(R.id.dragHandle);

        // Mega Shock Toast UI
        megaShockToast = overlayView.findViewById(R.id.megaShockToastLayout);
        tvMegaShockType = overlayView.findViewById(R.id.tvMegaShockType);
        tvMegaShockSymbol = overlayView.findViewById(R.id.tvMegaShockSymbol);
        tvMegaShockTitle = overlayView.findViewById(R.id.tvMegaShockTitle);
        tvMegaShockMetrics = overlayView.findViewById(R.id.tvMegaShockMetrics);
        View btnCloseToast = overlayView.findViewById(R.id.btnCloseMegaToast);

        if (btnCloseToast != null) {
            btnCloseToast.setOnClickListener(v -> megaShockToast.setVisibility(View.GONE));
        }

        if (btnClose != null) {
            btnClose.setOnClickListener(v -> stopSelf());
        }

        // Crear vistas de cada uno de los 8 tickers
        for (BinanceManager.TickerConfig tc : defaultTickers) {
            View itemView = inflater.inflate(R.layout.item_compact_ticker, llTickers, false);
            TextView tvSym = itemView.findViewById(R.id.tvTickerSymbol);
            TextView tvPrice = itemView.findViewById(R.id.tvTickerPrice);
            TextView tvChange = itemView.findViewById(R.id.tvTickerChange);

            tvSym.setText(tc.symbol);
            tvPrice.setText("---");
            tvChange.setText("0.00%");

            llTickers.addView(itemView);
            tickerItemViews.put(tc.pair, itemView);
        }

        // Arrastrar la barra con el dedo
        if (btnDragHandle != null) {
            btnDragHandle.setOnTouchListener(new View.OnTouchListener() {
                private int initialY;
                private float initialTouchY;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initialY = params.y;
                            initialTouchY = event.getRawY();
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            params.y = initialY + (int) (event.getRawY() - initialTouchY);
                            windowManager.updateViewLayout(overlayView, params);
                            return true;
                    }
                    return false;
                }
            });
        }

        windowManager.addView(overlayView, params);
    }

    @Override
    public void onTickerUpdated(String pair, double price, double changePct) {
        lastPrices.put(pair, price);
        lastChanges.put(pair, changePct);

        View itemView = tickerItemViews.get(pair);
        if (itemView != null) {
            TextView tvPrice = itemView.findViewById(R.id.tvTickerPrice);
            TextView tvChange = itemView.findViewById(R.id.tvTickerChange);

            BinanceManager.TickerConfig config = null;
            for (BinanceManager.TickerConfig tc : defaultTickers) {
                if (tc.pair.equals(pair)) {
                    config = tc;
                    break;
                }
            }
            int decimals = config != null ? config.decimals : 2;

            tvPrice.setText(String.format("%." + decimals + "f", price));
            String sign = changePct > 0 ? "+" : "";
            tvChange.setText(String.format("%s%.2f%%", sign, changePct));
            tvChange.setTextColor(changePct >= 0 ? Color.parseColor("#4ade80") : Color.parseColor("#f87171"));
        }

        // Actualizar notificación de bloqueo para BTC/ETH
        if ("btcusdt".equals(pair)) {
            updateNotificationStatus(String.format("BTC: $%.2f (%s%.2f%%)", price, changePct > 0 ? "+" : "", changePct));
        }
    }

    @Override
    public void onIndicatorsUpdated(String pair, IndicatorCalculator.IndicatorResult ind) {
        IndicatorCalculator.IndicatorResult prev = lastIndicators.put(pair, ind);
        View itemView = tickerItemViews.get(pair);
        if (itemView != null) {
            TextView tvV = itemView.findViewById(R.id.tvBadgeV);
            TextView tvF = itemView.findViewById(R.id.tvBadgeF);
            TextView tvE = itemView.findViewById(R.id.tvBadgeE);

            if (ind.vActive) {
                tvV.setBackgroundColor(Color.parseColor("#064e3b"));
                tvV.setTextColor(Color.parseColor("#4ade80"));
            } else {
                tvV.setBackgroundColor(Color.parseColor("#27272a"));
                tvV.setTextColor(Color.parseColor("#71717a"));
            }

            if (ind.fActive) {
                tvF.setBackgroundColor(Color.parseColor("#064e3b"));
                tvF.setTextColor(Color.parseColor("#4ade80"));
            } else {
                tvF.setBackgroundColor(Color.parseColor("#27272a"));
                tvF.setTextColor(Color.parseColor("#71717a"));
            }

            if ("blue".equals(ind.emaColor)) {
                tvE.setBackgroundColor(Color.parseColor("#1e3a8a"));
                tvE.setTextColor(Color.parseColor("#60a5fa"));
            } else if ("green".equals(ind.emaColor)) {
                tvE.setBackgroundColor(Color.parseColor("#064e3b"));
                tvE.setTextColor(Color.parseColor("#4ade80"));
            } else {
                tvE.setBackgroundColor(Color.parseColor("#27272a"));
                tvE.setTextColor(Color.parseColor("#71717a"));
            }
        }

        if (prev != null && !prev.vfConfluence && ind.vfConfluence) {
            playToneAsync(880, 140);
        }
    }

    @Override
    public void onFlashShockDetected(String symbol, String type, double pctChange, double volRatio) {
        NewsManager.NewsItem urgent = newsManager.latestUrgentNews;
        boolean hasRecentUrgentNews = urgent != null && (System.currentTimeMillis() - urgent.pubTs < 45 * 60 * 1000L);

        if (hasRecentUrgentNews) {
            // 🚨 TRIGGER MEGA SHOCK
            triggerMegaShock(symbol, type, pctChange, volRatio, urgent.title);
        } else {
            // Flash Shock Regular
            if ("DUMP".equals(type)) {
                playDumpTone();
                vibrate(new long[]{0, 120, 60, 200});
            } else {
                playPumpTone();
                vibrate(new long[]{0, 80, 50, 150});
            }
        }
    }

    private void triggerMegaShock(String symbol, String type, double pct, double volRatio, String newsTitle) {
        playMegaShockSirens();
        vibrate(new long[]{0, 200, 100, 200, 100, 400});

        if (megaShockToast != null) {
            megaShockToast.setVisibility(View.VISIBLE);
            tvMegaShockType.setText("DUMP".equals(type) ? "💥 MEGA DUMP" : "🚀 MEGA PUMP");
            tvMegaShockSymbol.setText(symbol);
            tvMegaShockTitle.setText(newsTitle);
            tvMegaShockMetrics.setText(String.format("%s%.2f%% (1m) | Vol: %.1fx", pct > 0 ? "+" : "", pct, volRatio));

            mainHandler.postDelayed(() -> megaShockToast.setVisibility(View.GONE), 14000);
        }
    }

    @Override
    public void onNewsUpdated(List<NewsManager.NewsItem> items) {}

    @Override
    public void onUrgentNewsAlert(NewsManager.NewsItem item) {
        newsUrgentEndTime = System.currentTimeMillis() + 180000; // 3 minutos
        playToneAsync(1000, 80);
        vibrate(new long[]{0, 100, 100, 100});
        updateNewsButtonUI();
    }

    @Override
    public void onStatusChanged(String status) {}

    private void updateNewsButtonUI() {
        if (tvNewsBtn == null) return;
        long now = System.currentTimeMillis();
        if (newsUrgentEndTime > now) {
            long remaining = (newsUrgentEndTime - now) / 1000;
            tvNewsBtn.setText(String.format("📰 %dm", (remaining / 60) + 1));
            tvNewsBtn.setBackgroundColor(Color.parseColor("#7f1d1d"));
            tvNewsBtn.setTextColor(Color.parseColor("#fecaca"));
        } else {
            tvNewsBtn.setText("📰");
            tvNewsBtn.setBackgroundColor(Color.parseColor("#18181b"));
            tvNewsBtn.setTextColor(Color.parseColor("#94a3b8"));
        }
    }

    // ==========================================
    // SINTETIZADOR DE AUDIO NATIVO
    // ==========================================
    private void playToneAsync(int freq, int durationMs) {
        new Thread(() -> {
            try {
                int sampleRate = 44100;
                int numSamples = (durationMs * sampleRate) / 1000;
                double[] sample = new double[numSamples];
                byte[] generatedSnd = new byte[2 * numSamples];

                for (int i = 0; i < numSamples; ++i) {
                    sample[i] = Math.sin(2 * Math.PI * i / (sampleRate / (double) freq));
                }

                int idx = 0;
                for (final double dVal : sample) {
                    final short val = (short) ((dVal * 32767));
                    generatedSnd[idx++] = (byte) (val & 0x00ff);
                    generatedSnd[idx++] = (byte) ((val & 0xff00) >>> 8);
                }

                AudioTrack audioTrack = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(generatedSnd.length)
                        .build();

                audioTrack.write(generatedSnd, 0, generatedSnd.length);
                audioTrack.play();
                Thread.sleep(durationMs + 20);
                audioTrack.release();
            } catch (Exception ignored) {}
        }).start();
    }

    private void playDumpTone() {
        playToneAsync(1200, 110);
        mainHandler.postDelayed(() -> playToneAsync(700, 180), 140);
    }

    private void playPumpTone() {
        playToneAsync(750, 90);
        mainHandler.postDelayed(() -> playToneAsync(1350, 160), 120);
    }

    private void playMegaShockSirens() {
        playToneAsync(1400, 120);
        mainHandler.postDelayed(() -> playToneAsync(900, 140), 150);
        mainHandler.postDelayed(() -> playToneAsync(1400, 120), 320);
        mainHandler.postDelayed(() -> playToneAsync(900, 140), 470);
        mainHandler.postDelayed(() -> playToneAsync(1500, 240), 640);
    }

    private void vibrate(long[] pattern) {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        }
    }

    // ==========================================
    // NOTIFICACIÓN EN PRIMER PLANO & PANTALLA DE BLOQUEO
    // ==========================================
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Crypto Visor 24/7 Monitor",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Monitoreo continuo de criptomonedas y alertas Mega Shock");
            serviceChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    private Notification buildNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Crypto Visor PRO (En Vivo)")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Visible en pantalla de bloqueo
                .setOngoing(true)
                .build();
    }

    private void updateNotificationStatus(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (binanceManager != null) binanceManager.stop();
        if (newsManager != null) newsManager.stop();
        if (overlayView != null && windowManager != null) {
            windowManager.removeView(overlayView);
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
