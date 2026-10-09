package com.cryptovisor.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class NewsManager {
    private static final String TAG = "NewsManager";

    public interface NewsListener {
        void onNewsUpdated(List<NewsItem> items);
        void onUrgentNewsAlert(NewsItem item);
    }

    public static class NewsItem {
        public String title;
        public String link;
        public String source;
        public long pubTs;
        public int minsAgo;
        public String timeStr;
        public boolean isUrgent;
        public boolean isTrusted;
    }

    private static final List<String> TRUSTED_SOURCES = Arrays.asList(
            "reuters", "bloomberg", "cnbc", "investing", "marketwatch", "yahoo", "financial times",
            "wall street journal", "wsj", "coindesk", "cointelegraph", "decrypt", "the block",
            "bitcoin magazine", "beincrypto", "el economista", "cinco días", "expansion",
            "cronista", "infobae", "forbes", "morningstar", "ambit", "fxstreet", "dailyfx"
    );

    private static final List<String> URGENT_KEYWORDS = Arrays.asList(
            "guerra", "war", "ataque", "attack", "misil", "missile", "iran", "israel", "rusia", "ucrania", "china", "taiwan",
            "fed", "fomc", "powell", "inflacion", "inflation", "cpi", "ipc", "tasas", "rates", "aranceles", "tariffs",
            "quiebra", "bankrupt", "sec", "etf", "hack", "banco central", "urgente", "breaking", "alerta", "crash", "nuclear"
    );

    private final NewsListener listener;
    private final OkHttpClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isRunning = false;
    private final List<String> seenTitles = Collections.synchronizedList(new ArrayList<>());
    public NewsItem latestUrgentNews = null;

    public NewsManager(NewsListener listener) {
        this.listener = listener;
        this.client = new OkHttpClient();
    }

    public void start() {
        isRunning = true;
        fetchNews();
    }

    public void stop() {
        isRunning = false;
    }

    public void fetchNews() {
        if (!isRunning) return;

        String rssUrl = "https://news.google.com/rss/search?q=(crypto+OR+bitcoin+OR+fed+OR+guerra+OR+inflacion+OR+economy)+when:2h&hl=es-419&gl=US&ceid=US:es-419";
        Request req = new Request.Builder()
                .url(rssUrl)
                .header("User-Agent", "Mozilla/5.0")
                .build();

        client.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                Log.w(TAG, "News fetch failed: " + e.getMessage());
                scheduleNext();
            }

            @Override
            public void onResponse(Call call, Response response) throws java.io.IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String xml = response.body().string();
                        List<NewsItem> items = parseRssXml(xml);
                        mainHandler.post(() -> {
                            listener.onNewsUpdated(items);
                            for (NewsItem it : items) {
                                if (it.isUrgent && it.minsAgo <= 45 && !seenTitles.contains(it.title)) {
                                    seenTitles.add(it.title);
                                    latestUrgentNews = it;
                                    listener.onUrgentNewsAlert(it);
                                    break;
                                }
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Parse RSS error: " + e.getMessage());
                    }
                }
                scheduleNext();
            }
        });
    }

    private void scheduleNext() {
        if (isRunning) {
            mainHandler.postDelayed(this::fetchNews, 60000); // Cada 60s
        }
    }

    private List<NewsItem> parseRssXml(String xml) {
        List<NewsItem> list = new ArrayList<>();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            int eventType = parser.getEventType();
            NewsItem current = null;
            String curTag = "";
            long now = System.currentTimeMillis();
            SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH);

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    curTag = parser.getName();
                    if ("item".equalsIgnoreCase(curTag)) {
                        current = new NewsItem();
                    }
                } else if (eventType == XmlPullParser.TEXT && current != null) {
                    String text = parser.getText().trim();
                    if ("title".equalsIgnoreCase(curTag)) {
                        current.title = text;
                    } else if ("link".equalsIgnoreCase(curTag)) {
                        current.link = text;
                    } else if ("pubDate".equalsIgnoreCase(curTag)) {
                        try {
                            Date d = sdf.parse(text);
                            if (d != null) {
                                current.pubTs = d.getTime();
                                current.minsAgo = (int) Math.max(0, (now - current.pubTs) / 60000);
                            }
                        } catch (Exception ignored) {
                            current.pubTs = now;
                            current.minsAgo = 0;
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    if ("item".equalsIgnoreCase(parser.getName()) && current != null) {
                        if (current.minsAgo <= 180) { // < 3 horas
                            String raw = current.title != null ? current.title : "";
                            String source = "Noticias";
                            String cleanTitle = raw;
                            if (raw.contains(" - ")) {
                                int lastIdx = raw.lastIndexOf(" - ");
                                cleanTitle = raw.substring(0, lastIdx);
                                source = raw.substring(lastIdx + 3);
                            }
                            current.source = source;
                            current.title = cleanTitle;

                            String srcLower = source.toLowerCase();
                            String titleLower = cleanTitle.toLowerCase();
                            current.isTrusted = false;
                            for (String ts : TRUSTED_SOURCES) {
                                if (srcLower.contains(ts) || titleLower.contains(ts)) {
                                    current.isTrusted = true;
                                    break;
                                }
                            }

                            current.isUrgent = false;
                            for (String kw : URGENT_KEYWORDS) {
                                if (titleLower.contains(kw)) {
                                    current.isUrgent = true;
                                    break;
                                }
                            }

                            current.timeStr = current.minsAgo < 60 ? "Hace " + current.minsAgo + "m" : "Hace " + (current.minsAgo / 60) + "h " + (current.minsAgo % 60) + "m";
                            list.add(current);
                        }
                        current = null;
                    }
                }
                eventType = parser.next();
            }

            Collections.sort(list, (a, b) -> Integer.compare(a.minsAgo, b.minsAgo));
        } catch (Exception e) {
            Log.e(TAG, "XML parse error: " + e.getMessage());
        }
        return list;
    }
}
