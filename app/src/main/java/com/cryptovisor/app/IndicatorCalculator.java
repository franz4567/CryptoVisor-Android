package com.cryptovisor.app;

import java.util.List;

public class IndicatorCalculator {

    public static class Candle {
        public double open;
        public double high;
        public double low;
        public double close;
        public double vol;

        public Candle(double o, double h, double l, double c, double v) {
            this.open = o;
            this.high = h;
            this.low = l;
            this.close = c;
            this.vol = v;
        }
    }

    public static class IndicatorResult {
        public boolean vActive;
        public boolean fActive;
        public boolean eActive;
        public String emaColor; // "inactive", "green", "blue"
        public boolean vfConfluence;
        public String vDesc;
        public String fDesc;
        public String eDesc;

        public IndicatorResult() {
            vActive = false;
            fActive = false;
            eActive = false;
            emaColor = "inactive";
            vfConfluence = false;
            vDesc = "Datos insuficientes";
            fDesc = "Datos insuficientes";
            eDesc = "Datos insuficientes";
        }
    }

    public static IndicatorResult calculate(List<Candle> candles) {
        IndicatorResult res = new IndicatorResult();
        int n = candles.size();
        if (n < 25) return res;

        double[] closes = new double[n];
        double[] highs = new double[n];
        double[] lows = new double[n];
        double[] vols = new double[n];

        for (int i = 0; i < n; i++) {
            Candle c = candles.get(i);
            closes[i] = c.close;
            highs[i] = c.high;
            lows[i] = c.low;
            vols[i] = c.vol;
        }

        double currClose = closes[n - 1];
        double currHigh = highs[n - 1];
        double currLow = lows[n - 1];

        // 1. VIX FIX VISUAL (V)
        int pd = 22, bbl = 20, lb = 50;
        double mult = 2.0, ph = 0.85;

        double[] wvf = new double[n];
        for (int i = 0; i < n; i++) {
            int start = Math.max(0, i - pd + 1);
            double highestC = closes[i];
            for (int j = start; j <= i; j++) {
                if (closes[j] > highestC) highestC = closes[j];
            }
            wvf[i] = highestC > 0 ? ((highestC - lows[i]) / highestC) * 100.0 : 0.0;
        }

        double curWvf = wvf[n - 1];
        double sumBbl = 0;
        for (int i = n - bbl; i < n; i++) sumBbl += wvf[i];
        double midLine = sumBbl / bbl;

        double sumSq = 0;
        for (int i = n - bbl; i < n; i++) sumSq += Math.pow(wvf[i] - midLine, 2);
        double sDev = Math.sqrt(sumSq / bbl) * mult;
        double upperBand = midLine + sDev;

        int lbStart = Math.max(0, n - lb);
        double maxLb = wvf[lbStart];
        for (int i = lbStart; i < n; i++) {
            if (wvf[i] > maxLb) maxLb = wvf[i];
        }
        double rangeHigh = maxLb * ph;

        res.vActive = (curWvf >= upperBand) || (curWvf >= rangeHigh);
        res.vDesc = String.format("[V] VIX Fix: %s (WVF: %.2f, Banda: %.2f)", res.vActive ? "ACTIVO" : "Normal", curWvf, upperBand);

        // 2. FRANJA DINÁMICA (F) - VWMA + ATR (1.8)
        int volLen = 20;
        double volMult = 1.8;
        double sumVol = 0, sumCloseVol = 0;
        for (int i = n - volLen; i < n; i++) {
            sumVol += vols[i];
            sumCloseVol += (closes[i] * vols[i]);
        }
        double baseVwma = sumVol > 0 ? (sumCloseVol / sumVol) : (sumCloseVol / volLen);

        double sumTr = 0;
        int trCount = 0;
        for (int i = Math.max(1, n - volLen); i < n; i++) {
            double tr = Math.max(highs[i] - lows[i], Math.max(Math.abs(highs[i] - closes[i - 1]), Math.abs(lows[i] - closes[i - 1])));
            sumTr += tr;
            trCount++;
        }
        double atr = trCount > 0 ? (sumTr / trCount) : (currHigh - currLow);
        double rangoAtr = atr * volMult;
        double franjaSup = baseVwma + rangoAtr;
        double franjaInf = baseVwma - rangoAtr;

        res.fActive = (currClose > franjaSup) || (currClose < franjaInf);
        res.fDesc = String.format("[F] Franja Dinámica: %s (P: %.2f, Sup: %.2f, Inf: %.2f)", res.fActive ? "FUERA" : "Dentro", currClose, franjaSup, franjaInf);

        // 3. EMA 100 (E)
        int emaLen = 100;
        double k = 2.0 / (emaLen + 1.0);
        double ema = closes[0];
        for (int i = 1; i < n; i++) {
            ema = (closes[i] * k) + (ema * (1.0 - k));
        }

        res.vfConfluence = res.vActive && res.fActive;

        if (res.vfConfluence) {
            if (ema > currClose) {
                res.emaColor = "blue";
                res.eDesc = String.format("[E] EMA 100: AZUL (EMA %.2f > Precio %.2f | Confluencia)", ema, currClose);
            } else {
                res.emaColor = "green";
                res.eDesc = String.format("[E] EMA 100: VERDE (Precio %.2f >= EMA %.2f | Confluencia)", currClose, ema);
            }
            res.eActive = true;
        } else {
            res.eActive = (currClose >= ema) || (currLow <= ema && ema <= currHigh);
            res.emaColor = res.eActive ? "green" : "inactive";
            res.eDesc = String.format("[E] EMA 100: %s (EMA: %.2f)", res.eActive ? "SOBRE/CRUZANDO" : "Por debajo", ema);
        }

        return res;
    }
}
