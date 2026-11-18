package com.tvkouame.studio;

import java.util.Arrays;
import java.util.Locale;

/**
 * Analyse et nettoyage d'un son (mono 16 bits) : mesures réelles du signal, puis traitement réel :
 * filtre grave, réduction de bruit spectrale, porte douce, présence, normalisation.
 * Aucune dépendance Android : tout est calculé ici, sur le téléphone. L'IA ne reçoit que les mesures, jamais le son.
 */
public final class AudioCleaner {

    private AudioCleaner() {}

    // ------------------------------------------------------------------
    // Mesures
    // ------------------------------------------------------------------
    public static final class Stats {
        public float seconds, peakDb, rmsDb, noiseFloorDb, loudDb, snrDb, clipPct, dc;
        public float lowPct, midPct, highPct;     // répartition de l'énergie : <250 Hz, 250-4000 Hz, >4000 Hz

        public String toText() {
            return String.format(Locale.US,
                    "durée %.1f s, crête %.1f dBFS, niveau moyen %.1f dBFS, niveau des passages forts %.1f dBFS, "
                            + "bruit de fond %.1f dBFS, rapport signal/bruit %.1f dB, saturation %.2f %% des échantillons, "
                            + "décalage continu %.4f, énergie graves(<250 Hz) %.0f %%, médiums %.0f %%, aigus(>4 kHz) %.0f %%",
                    seconds, peakDb, rmsDb, loudDb, noiseFloorDb, snrDb, clipPct, dc, lowPct, midPct, highPct);
        }
    }

    private static float db(double lin) { return (float) (20.0 * Math.log10(Math.max(lin, 1e-6))); }

    /** Niveaux RMS (linéaires) par fenêtre de 20 ms. */
    private static float[] frameRms(short[] pcm, int len, int rate, int frame) {
        int n = Math.max(1, len / frame);
        float[] r = new float[n];
        for (int f = 0; f < n; f++) {
            double s = 0;
            int a = f * frame, b = Math.min(len, a + frame);
            for (int i = a; i < b; i++) { double v = pcm[i] / 32768.0; s += v * v; }
            r[f] = (float) Math.sqrt(s / Math.max(1, b - a));
        }
        return r;
    }

    private static float percentile(float[] v, float p) {
        float[] c = v.clone();
        Arrays.sort(c);
        int i = Math.max(0, Math.min(c.length - 1, Math.round(p * (c.length - 1))));
        return c[i];
    }

    public static Stats analyze(short[] pcm, int len, int rate) {
        Stats st = new Stats();
        if (len <= 0) return st;
        st.seconds = len / (float) rate;
        double sum = 0, sq = 0;
        int peak = 0, clip = 0;
        for (int i = 0; i < len; i++) {
            int a = Math.abs((int) pcm[i]);
            if (a > peak) peak = a;
            if (a >= 32000) clip++;
            double v = pcm[i] / 32768.0;
            sum += v;
            sq += v * v;
        }
        st.dc = (float) (sum / len);
        st.peakDb = db(peak / 32768.0);
        st.rmsDb = db(Math.sqrt(sq / len));
        st.clipPct = 100f * clip / len;

        float[] fr = frameRms(pcm, len, rate, Math.max(64, rate / 50));
        st.noiseFloorDb = db(percentile(fr, 0.10f));
        st.loudDb = db(percentile(fr, 0.90f));
        st.snrDb = st.loudDb - st.noiseFloorDb;

        // répartition spectrale avec deux filtres à un pôle
        double a1 = 1.0 - Math.exp(-2.0 * Math.PI * 250.0 / rate);
        double a2 = 1.0 - Math.exp(-2.0 * Math.PI * 4000.0 / rate);
        double l1 = 0, l2 = 0, eLow = 0, eMid = 0, eHigh = 0;
        for (int i = 0; i < len; i++) {
            double x = pcm[i] / 32768.0;
            l1 += a1 * (x - l1);
            l2 += a2 * (x - l2);
            double low = l1, mid = l2 - l1, high = x - l2;
            eLow += low * low; eMid += mid * mid; eHigh += high * high;
        }
        double tot = Math.max(1e-12, eLow + eMid + eHigh);
        st.lowPct = (float) (100 * eLow / tot);
        st.midPct = (float) (100 * eMid / tot);
        st.highPct = (float) (100 * eHigh / tot);
        return st;
    }

    // ------------------------------------------------------------------
    // Réglages du nettoyage (l'IA peut les proposer, ils sont toujours bornés)
    // ------------------------------------------------------------------
    public static final class Params {
        public float hpHz = 80f;            // filtre des graves parasites (30 .. 200)
        public float denoise = 0.6f;        // réduction de bruit spectrale (0 .. 1)
        public float gateDb = 12f;          // atténuation de la porte entre les phrases (0 .. 30 dB)
        public float presenceDb = 2f;       // clarté autour de 3 kHz (-4 .. +6 dB)
        public float targetPeakDb = -1.5f;  // niveau final de la crête (-6 .. -0.5 dBFS)

        public void clamp() {
            hpHz = Math.max(30f, Math.min(200f, hpHz));
            denoise = Math.max(0f, Math.min(1f, denoise));
            gateDb = Math.max(0f, Math.min(30f, gateDb));
            presenceDb = Math.max(-4f, Math.min(6f, presenceDb));
            targetPeakDb = Math.max(-6f, Math.min(-0.5f, targetPeakDb));
        }

        /** Réglages de départ calculés à partir des mesures, sans IA (sert aussi de secours hors ligne). */
        public static Params auto(Stats s) {
            Params p = new Params();
            p.denoise = s.snrDb < 18 ? 0.85f : s.snrDb < 30 ? 0.6f : s.snrDb < 42 ? 0.35f : 0.15f;
            p.gateDb = s.snrDb < 25 ? 18f : s.snrDb < 40 ? 12f : 6f;
            p.hpHz = s.lowPct > 45f ? 110f : 80f;
            p.presenceDb = s.highPct < 5f ? 4f : s.highPct > 25f ? -1f : 2f;
            p.clamp();
            return p;
        }

        public String toText() {
            return String.format(Locale.US, "filtre graves %.0f Hz · réduction de bruit %.0f %% · porte %.0f dB · clarté %+.1f dB · crête finale %.1f dBFS",
                    hpHz, denoise * 100, gateDb, presenceDb, targetPeakDb);
        }
    }

    // ------------------------------------------------------------------
    // Traitement
    // ------------------------------------------------------------------
    public interface Progress { void on(int percent); }

    public static short[] clean(short[] pcm, int len, int rate, Params p, Progress pg) {
        p.clamp();
        float[] x = new float[len];
        double dc = 0;
        for (int i = 0; i < len; i++) dc += pcm[i];
        dc /= Math.max(1, len);
        for (int i = 0; i < len; i++) x[i] = (float) ((pcm[i] - dc) / 32768.0);
        if (pg != null) pg.on(5);

        Biquad hp1 = Biquad.highpass(rate, p.hpHz), hp2 = Biquad.highpass(rate, p.hpHz);
        for (int i = 0; i < len; i++) x[i] = hp2.process(hp1.process(x[i]));
        if (pg != null) pg.on(15);

        if (p.denoise > 0.01f && len > 4096) x = spectralDenoise(x, len, rate, p.denoise, pg);
        if (pg != null) pg.on(75);

        if (p.gateDb > 0.5f) gate(x, len, rate, p.gateDb);
        if (pg != null) pg.on(85);

        if (Math.abs(p.presenceDb) > 0.1f) {
            Biquad pres = Biquad.peaking(rate, 3000f, 1.0f, p.presenceDb);
            for (int i = 0; i < len; i++) x[i] = pres.process(x[i]);
        }

        // normalisation de la crête (gain limité à +18 dB pour ne pas remonter du bruit)
        float peak = 1e-6f;
        for (int i = 0; i < len; i++) peak = Math.max(peak, Math.abs(x[i]));
        float target = (float) Math.pow(10.0, p.targetPeakDb / 20.0);
        float gain = Math.min(target / peak, 8f);
        short[] out = new short[len];
        for (int i = 0; i < len; i++) {
            float v = x[i] * gain;
            if (v > 0.9f) v = 0.9f + (float) Math.tanh((v - 0.9f) * 6f) * 0.1f;
            else if (v < -0.9f) v = -0.9f + (float) Math.tanh((v + 0.9f) * 6f) * 0.1f;
            int s = Math.round(v * 32767f);
            out[i] = (short) Math.max(-32768, Math.min(32767, s));
        }
        if (pg != null) pg.on(100);
        return out;
    }

    /** Porte douce : atténue ce qui est près du bruit de fond, avec attaque rapide, maintien et retour lent. */
    private static void gate(float[] x, int len, int rate, float reductionDb) {
        int frame = Math.max(64, rate / 100);                 // 10 ms
        int nf = Math.max(1, len / frame);
        float[] env = new float[nf];
        for (int f = 0; f < nf; f++) {
            double s = 0; int a = f * frame, b = Math.min(len, a + frame);
            for (int i = a; i < b; i++) s += x[i] * x[i];
            env[f] = (float) Math.sqrt(s / Math.max(1, b - a));
        }
        float floor = percentile(env, 0.10f);
        float loud = percentile(env, 0.90f);
        float thr = Math.max(floor * 2.2f, loud * 0.06f);
        if (loud < floor * 2f) return;                          // rien à séparer : on ne touche pas
        float low = (float) Math.pow(10.0, -reductionDb / 20.0);
        int hold = 8;                                           // 80 ms
        float g = 1f;
        int left = 0;
        float atk = 1f - (float) Math.exp(-1.0 / (0.003 * rate)), rel = 1f - (float) Math.exp(-1.0 / (0.15 * rate));
        // gain cible par fenêtre, avec anticipation de 2 fenêtres pour ne pas couper les attaques
        float[] tg = new float[nf];
        for (int f = 0; f < nf; f++) tg[f] = env[f] > thr ? 1f : 0f;
        for (int f = 0; f < nf; f++) {
            boolean open = tg[f] > 0 || (f + 1 < nf && tg[f + 1] > 0) || (f + 2 < nf && tg[f + 2] > 0);
            if (open) left = hold; else if (left > 0) left--;
            float t = (open || left > 0) ? 1f : low;
            int a = f * frame, b = (f == nf - 1) ? len : a + frame;
            for (int i = a; i < b; i++) {
                g += (t - g) * (t > g ? atk : rel);
                x[i] *= g;
            }
        }
    }

    // ------------------------------------------------------------------
    // Réduction de bruit par soustraction spectrale (STFT 1024, recouvrement 75 %)
    // ------------------------------------------------------------------
    private static float[] spectralDenoise(float[] x, int len, int rate, float strength, Progress pg) {
        final int N = 1024, H = N / 4, B = N / 2 + 1;
        double[] win = new double[N];
        for (int i = 0; i < N; i++) win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / N);
        int frames = (len + N) / H + 1;
        int padded = (frames + 3) * H + N;
        float[] in = new float[padded];
        System.arraycopy(x, 0, in, N / 2, len);

        double[] re = new double[N], im = new double[N];
        float[][] mag = new float[frames][];
        double[] energy = new double[frames];
        // 1) spectres et énergie de chaque trame
        for (int f = 0; f < frames; f++) {
            int o = f * H;
            for (int i = 0; i < N; i++) { re[i] = in[o + i] * win[i]; im[i] = 0; }
            fft(re, im, false);
            mag[f] = new float[B];
            double e = 0;
            for (int k = 0; k < B; k++) { mag[f][k] = (float) Math.hypot(re[k], im[k]); e += mag[f][k] * mag[f][k]; }
            energy[f] = e;
        }
        if (pg != null) pg.on(30);

        // 2) profil de bruit = moyenne des 12 % de trames les plus calmes
        Integer[] idx = new Integer[frames];
        for (int i = 0; i < frames; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(energy[a], energy[b]));
        int nq = Math.max(4, frames * 12 / 100);
        float[] noise = new float[B];
        for (int q = 0; q < nq && q < frames; q++) for (int k = 0; k < B; k++) noise[k] += mag[idx[q]][k] / nq;
        // lissage du profil sur les fréquences
        float[] sm = new float[B];
        for (int k = 0; k < B; k++) {
            float s = 0; int c = 0;
            for (int d = -3; d <= 3; d++) { int j = k + d; if (j >= 0 && j < B) { s += noise[j]; c++; } }
            sm[k] = s / c;
        }
        noise = sm;

        // 3) soustraction + resynthèse
        float alpha = 1.0f + 2.2f * strength;        // sur-soustraction
        float beta = 0.08f + 0.22f * (1f - strength); // plancher spectral (gardé pour éviter le « bruit musical »)
        float[] out = new float[padded];
        float[] prevGain = new float[B];
        Arrays.fill(prevGain, 1f);
        for (int f = 0; f < frames; f++) {
            int o = f * H;
            for (int i = 0; i < N; i++) { re[i] = in[o + i] * win[i]; im[i] = 0; }
            fft(re, im, false);
            for (int k = 0; k < B; k++) {
                float m = mag[f][k];
                float g = m > 1e-9f ? Math.max(beta, (m - alpha * noise[k]) / m) : beta;
                g = Math.min(1f, g);
                g = 0.6f * g + 0.4f * prevGain[k];      // lissage dans le temps
                prevGain[k] = g;
                re[k] *= g; im[k] *= g;
                if (k > 0 && k < N / 2) { re[N - k] = re[k]; im[N - k] = -im[k]; }
            }
            fft(re, im, true);
            for (int i = 0; i < N; i++) out[o + i] += (float) (re[i] * win[i]);
            if (pg != null && (f & 255) == 0) pg.on(30 + (int) (45L * f / frames));
        }
        // somme des fenêtres de Hann² avec recouvrement 75 % = 1,5
        float[] res = new float[len];
        for (int i = 0; i < len; i++) res[i] = out[i + N / 2] / 1.5f;
        return res;
    }

    /** FFT radix-2 en place (taille puissance de 2). inverse = vrai : résultat divisé par N. */
    private static void fft(double[] re, double[] im, boolean inverse) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inverse ? 1 : -1);
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
        if (inverse) for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
    }

    // ------------------------------------------------------------------
    // Filtre biquad (RBJ)
    // ------------------------------------------------------------------
    private static final class Biquad {
        float b0, b1, b2, a1, a2, z1, z2;

        float process(float x) {
            float y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }

        static Biquad highpass(int rate, float f) {
            double w = 2 * Math.PI * f / rate, c = Math.cos(w), al = Math.sin(w) / (2 * 0.7071);
            double a0 = 1 + al;
            Biquad q = new Biquad();
            q.b0 = (float) (((1 + c) / 2) / a0); q.b1 = (float) (-(1 + c) / a0); q.b2 = q.b0;
            q.a1 = (float) (-2 * c / a0); q.a2 = (float) ((1 - al) / a0);
            return q;
        }

        static Biquad peaking(int rate, float f, float qf, float gainDb) {
            double A = Math.pow(10, gainDb / 40.0), w = 2 * Math.PI * f / rate, c = Math.cos(w), al = Math.sin(w) / (2 * qf);
            double a0 = 1 + al / A;
            Biquad q = new Biquad();
            q.b0 = (float) ((1 + al * A) / a0); q.b1 = (float) (-2 * c / a0); q.b2 = (float) ((1 - al * A) / a0);
            q.a1 = q.b1; q.a2 = (float) ((1 - al / A) / a0);
            return q;
        }
    }
}
