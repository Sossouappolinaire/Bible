package com.tvkouame.studio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Placer, couper, redimensionner : tous les sons ajoutés (voix enregistrée ou fichier importé) sont posés sur la durée
 * du morceau. On peut les déplacer n'importe où, raccourcir ou rallonger leurs bords, les couper en deux et écouter le résultat.
 */
public class ClipEditorActivity extends Activity {

    private static final int RATE = WavIO.RATE;
    private static final int VOICE_COL = 0xFFE74C3C;
    private static final int OFF = 0xFF2C3A4A;
    private static final int PEAK_RES = 441;               // un repère de forme d'onde tous les 10 ms
    private static final int MIN_LEN = RATE / 20;          // un son ne descend pas sous 50 ms

    private final Handler ui = new Handler(Looper.getMainLooper());
    private BeatModel m;
    private int perBar, beatLen;
    private float[] beat = new float[0];                   // la composition telle qu'elle sera mixée
    private float[] beatPeaks = new float[0];
    private final ConcurrentHashMap<String, short[]> pcmByFile = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, float[]> peaksByFile = new ConcurrentHashMap<>();
    private final List<List<BeatModel.Clip>> undo = new ArrayList<>();
    private BeatModel.Clip sel;
    private final long startedAt = System.currentTimeMillis();
    private int snapMode = 0;                              // 0 libre, 1 temps, 2 mesure
    private boolean loaded = false;

    private TimelineView tv;
    private TextView info, status, timeText;
    private Button playBtn, snapBtn, splitBtn, delBtn, resetBtn, undoBtn;
    private LinearLayout panel;

    // lecture
    private volatile int pos = 0;
    private volatile boolean playing = false, stopFlag = false;
    private volatile BeatModel.Clip[] liveClips = new BeatModel.Clip[0];
    private final Runnable saveRun = this::saveNow;

    // ------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        m = StudioStore.load(this, getIntent().getStringExtra("id"));
        if (m == null) { finish(); return; }
        perBar = BeatRenderer.samplesPerBar(m);
        beatLen = (int) Math.round(RATE * 60.0 / m.bpm);
        snapMode = getSharedPreferences("studio_beat", MODE_PRIVATE).getInt("clip_snap", 0);
        buildUi();
        if (m.clips.isEmpty()) status.setText("Aucun son pour l'instant : enregistre la voix ou importe un fichier audio dans le Studio.");
        else status.setText("Préparation des sons…");
        loadSounds();
    }

    /** Sauvegarde dès la pause : le Studio se recharge juste après et doit voir ces changements. */
    @Override
    protected void onPause() {
        super.onPause();
        stopPlay();
        saveNow();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopFlag = true;
        if (m != null && isFinishing() && loaded) StudioStore.purgeUnused(this, m, startedAt);
    }

    private void saveNow() {
        ui.removeCallbacks(saveRun);
        if (m != null && loaded) StudioStore.save(this, m);
    }

    private void saveSoon() {
        ui.removeCallbacks(saveRun);
        ui.postDelayed(saveRun, 400);
    }

    // ------------------------------------------------------------------
    // Interface
    // ------------------------------------------------------------------
    private Button btn(String t, int color) {
        Button x = Ui.button(this, t, color);
        x.setTextSize(13);
        x.setMinHeight(Ui.dp(this, 42));
        x.setMinimumHeight(Ui.dp(this, 42));
        x.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        return x;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 10), Ui.dp(this, 28), Ui.dp(this, 10), Ui.dp(this, 8));

        TextView back = Ui.text(this, "‹ Retour au Studio", 15, Ui.ACCENT, true);
        back.setPadding(0, 0, 0, Ui.dp(this, 4));
        back.setOnClickListener(v -> finish());
        root.addView(back);
        root.addView(Ui.text(this, "Placer, couper, redimensionner", 18, Ui.TEXT, true));
        TextView hint = Ui.text(this, "Touche un son pour le choisir (le curseur jaune se place où tu touches). Glisse-le pour le placer où tu veux. "
                + "Glisse son bord gauche ou droit pour le raccourcir ou le rallonger. « Couper ici » le coupe au curseur.", 12, Ui.MUTED, false);
        hint.setPadding(0, 0, 0, Ui.dp(this, 6));
        root.addView(hint);

        LinearLayout r1 = Ui.row(this);
        playBtn = btn("▶ Écouter", Ui.GREEN);
        playBtn.setTextColor(0xFF000000);
        playBtn.setOnClickListener(v -> { if (playing) stopPlay(); else startPlay(); });
        Button zo = btn("−", OFF), zi = btn("+", OFF), fit = btn("Tout voir", OFF);
        zo.setOnClickListener(v -> tv.zoomBy(1 / 1.5f));
        zi.setOnClickListener(v -> tv.zoomBy(1.5f));
        fit.setOnClickListener(v -> tv.fitAll());
        snapBtn = btn("", OFF);
        snapBtn.setOnClickListener(v -> {
            snapMode = (snapMode + 1) % 3;
            getSharedPreferences("studio_beat", MODE_PRIVATE).edit().putInt("clip_snap", snapMode).apply();
            updateSnapLabel();
        });
        r1.addView(playBtn, Ui.weight(this, 2));
        r1.addView(zo, Ui.weight(this, 2));
        r1.addView(zi, Ui.weight(this, 2));
        r1.addView(fit, Ui.weight(this, 2));
        root.addView(r1);

        LinearLayout r1b = Ui.row(this);
        undoBtn = btn("↩ Annuler", OFF);
        undoBtn.setOnClickListener(v -> doUndo());
        r1b.addView(snapBtn, Ui.weight(this, 2));
        r1b.addView(undoBtn, Ui.weight(this, 2));
        root.addView(r1b);
        updateSnapLabel();

        timeText = Ui.text(this, "0:00.0", 16, Ui.TEXT, true);
        timeText.setGravity(Gravity.CENTER);
        root.addView(timeText);

        tv = new TimelineView(this);
        ScrollView vs = new ScrollView(this);
        vs.addView(tv, new ScrollView.LayoutParams(-1, -2));
        root.addView(vs, new LinearLayout.LayoutParams(-1, 0, 1f));

        status = Ui.text(this, "", 12, Ui.MUTED, false);
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 14)));
        panel.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        info = Ui.text(this, "", 13, Ui.TEXT, false);
        panel.addView(info);

        LinearLayout r2 = Ui.row(this);
        Button l1 = btn("◀ 0,1 s", OFF), l2 = btn("0,1 s ▶", OFF);
        l1.setOnClickListener(v -> nudge(-RATE / 10));
        l2.setOnClickListener(v -> nudge(RATE / 10));
        splitBtn = btn("✂ Couper ici", Ui.ACCENT);
        splitBtn.setTextColor(0xFF000000);
        splitBtn.setOnClickListener(v -> split());
        r2.addView(l1, Ui.weight(this, 2));
        r2.addView(l2, Ui.weight(this, 2));
        r2.addView(splitBtn, Ui.weight(this, 2));
        panel.addView(r2);

        LinearLayout r3 = Ui.row(this);
        Button start0 = btn("Placer au début", OFF), bar0 = btn("Caler sur la mesure", OFF);
        start0.setOnClickListener(v -> placeAt(0));
        bar0.setOnClickListener(v -> alignToBar());
        resetBtn = btn("Longueur d'origine", OFF);
        resetBtn.setOnClickListener(v -> resetCut());
        r3.addView(start0, Ui.weight(this, 2));
        r3.addView(bar0, Ui.weight(this, 2));
        r3.addView(resetBtn, Ui.weight(this, 2));
        panel.addView(r3);

        LinearLayout r4 = Ui.row(this);
        delBtn = btn("✕ Supprimer ce son", 0xFF7F1D1D);
        delBtn.setOnClickListener(v -> confirmDelete());
        r4.addView(delBtn, Ui.weight(this, 2));
        panel.addView(r4);
        root.addView(panel);

        setContentView(root);
        refreshPanel();
    }

    private void updateSnapLabel() {
        snapBtn.setText("Aimant : " + (snapMode == 0 ? "libre" : snapMode == 1 ? "temps" : "mesure"));
    }

    // ------------------------------------------------------------------
    // Chargement des sons et de la composition
    // ------------------------------------------------------------------
    private void loadSounds() {
        final BeatModel mm = m;
        new Thread(() -> {
            String err = null;
            float[] bt = new float[0];
            try {
                bt = BeatRenderer.renderMixedBeat(mm);
                File dir = StudioStore.dirOf(this, mm.id);
                for (BeatModel.Clip k : mm.clips) {
                    if (pcmByFile.containsKey(k.file)) continue;
                    try {
                        WavIO.Mono w = WavIO.readWav(new File(dir, k.file), 60 * 60 * RATE);
                        short[] p = w.rate == RATE ? w.data : WavIO.resample(w.data, w.len, w.rate, RATE);
                        int n = w.rate == RATE ? w.len : p.length;
                        if (n < p.length) p = java.util.Arrays.copyOf(p, n);
                        pcmByFile.put(k.file, p);
                        peaksByFile.put(k.file, peaksOf(p));
                    } catch (Exception ignored) {
                    }
                }
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
            }
            final float[] fbt = bt;
            final String ferr = err;
            ui.post(() -> {
                if (isFinishing()) return;
                if (ferr != null) { status.setText("Erreur : " + ferr); return; }
                beat = fbt;
                beatPeaks = peaksOfF(fbt);
                // un son dont le fichier n'a pas pu être lu est écarté de l'écran (il n'est pas modifié)
                loaded = true;
                refreshLive();
                tv.fitAll();
                if (!m.clips.isEmpty()) {
                    sel = m.clips.get(m.clips.size() - 1);       // le dernier ajouté
                    status.setText("");
                }
                refreshPanel();
                tv.relayout();
            });
        }, "clip-load").start();
    }

    private static float[] peaksOf(short[] p) {
        int n = p.length / PEAK_RES + 1;
        float[] out = new float[n];
        for (int i = 0; i < p.length; i++) {
            int a = p[i] < 0 ? -p[i] : p[i];
            float v = a / 32768f;
            int k = i / PEAK_RES;
            if (v > out[k]) out[k] = v;
        }
        return out;
    }

    private static float[] peaksOfF(float[] p) {
        int n = p.length / PEAK_RES + 1;
        float[] out = new float[n];
        for (int i = 0; i < p.length; i++) {
            float v = p[i] < 0 ? -p[i] : p[i];
            int k = i / PEAK_RES;
            if (v > out[k]) out[k] = v;
        }
        return out;
    }

    private short[] pcmOf(BeatModel.Clip c) { return pcmByFile.get(c.file); }

    private int lenOf(BeatModel.Clip c) {
        short[] p = pcmOf(c);
        return p == null ? 0 : c.used(p.length);
    }

    private int startOf(BeatModel.Clip c) { return c.start(perBar); }

    /** Les sons que le lecteur peut jouer (seulement ceux dont le fichier est chargé). */
    private void refreshLive() {
        List<BeatModel.Clip> l = new ArrayList<>();
        for (BeatModel.Clip c : m.clips) if (pcmByFile.containsKey(c.file)) l.add(c);
        liveClips = l.toArray(new BeatModel.Clip[0]);
    }

    /** Durée affichée : la composition, ou plus si un son dépasse. */
    private int timelineLen() {
        int mx = Math.max(beat.length > 0 ? BeatRenderer.totalSamples(m) : 0, perBar);
        for (BeatModel.Clip c : m.clips) mx = Math.max(mx, startOf(c) + lenOf(c));
        return mx + perBar / 2;
    }

    // ------------------------------------------------------------------
    // Modifications (avec annulation)
    // ------------------------------------------------------------------
    private void pushUndo() {
        List<BeatModel.Clip> snap = new ArrayList<>();
        for (BeatModel.Clip c : m.clips) snap.add(c.copy());
        undo.add(snap);
        if (undo.size() > 40) undo.remove(0);
        undoBtn.setEnabled(true);
    }

    private void doUndo() {
        if (undo.isEmpty()) { Toast.makeText(this, "Rien à annuler", Toast.LENGTH_SHORT).show(); return; }
        stopPlay();
        List<BeatModel.Clip> snap = undo.remove(undo.size() - 1);
        String selId = sel == null ? "" : sel.id;
        m.clips.clear();
        m.clips.addAll(snap);
        sel = m.findClip(selId);
        afterEdit();
    }

    private void afterEdit() {
        refreshLive();
        refreshPanel();
        tv.relayout();
        saveSoon();
    }

    private int snap(long s) {
        long q = snapMode == 1 ? beatLen : snapMode == 2 ? perBar : 0;
        long v = q > 0 ? Math.round(s / (double) q) * q : s;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE / 2, v));
    }

    private void placeAt(int sample) {
        if (sel == null) return;
        pushUndo();
        sel.offset = Math.max(0, sample);
        afterEdit();
    }

    private void nudge(int d) {
        if (sel == null) return;
        pushUndo();
        sel.offset = Math.max(0, startOf(sel) + d);
        afterEdit();
    }

    private void alignToBar() {
        if (sel == null) return;
        pushUndo();
        sel.offset = (int) (Math.round(startOf(sel) / (double) perBar) * perBar);
        afterEdit();
    }

    private void resetCut() {
        if (sel == null || sel.isWhole()) return;
        pushUndo();
        // la partie qu'on entend ne bouge pas : on rouvre le début (vers la gauche) et la fin
        int s = startOf(sel);
        int back = Math.min(sel.cutIn, s);
        sel.offset = s - back;
        sel.cutIn -= back;          // reste > 0 seulement si le début d'origine sortirait de la frise (avant 0:00)
        sel.cutLen = 0;
        afterEdit();
    }

    /** Coupe le son choisi en deux au curseur de lecture : deux sons, chacun peut être déplacé ou supprimé. */
    private void split() {
        if (sel == null) { Toast.makeText(this, "Choisis d'abord un son", Toast.LENGTH_SHORT).show(); return; }
        int s = startOf(sel), len = lenOf(sel);
        int at = pos;
        if (at <= s + MIN_LEN || at >= s + len - MIN_LEN) {
            Toast.makeText(this, "Pose le curseur (touche la règle) à l'intérieur du son pour le couper", Toast.LENGTH_LONG).show();
            return;
        }
        stopPlay();
        pushUndo();
        int leftLen = at - s;
        BeatModel.Clip right = sel.copy();
        right.id = "c" + System.currentTimeMillis();
        while (m.findClip(right.id) != null) right.id = right.id + "x";
        right.name = sel.name + " (suite)";
        right.offset = at;
        right.cutIn = sel.cutIn + leftLen;
        right.cutLen = len - leftLen;
        sel.offset = s;
        sel.cutLen = leftLen;
        m.clips.add(m.clips.indexOf(sel) + 1, right);
        sel = right;
        afterEdit();
        Toast.makeText(this, "Son coupé en deux", Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete() {
        if (sel == null) return;
        final BeatModel.Clip c = sel;
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Supprimer ce son ?")
                .setMessage("« " + c.name + " » sera retiré du mix. Tu peux annuler juste après.")
                .setPositiveButton("Supprimer", (d, w) -> {
                    stopPlay();
                    pushUndo();
                    m.clips.remove(c);
                    sel = null;
                    afterEdit();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private String fmt(long samples) {
        double s = samples / (double) RATE;
        int mi = (int) (s / 60);
        return String.format(Locale.US, "%d:%04.1f", mi, s - mi * 60);
    }

    private void refreshPanel() {
        if (info == null) return;
        boolean has = sel != null && m.clips.contains(sel);
        if (!has) sel = null;
        if (sel == null) {
            info.setText(m.clips.isEmpty() ? "Aucun son à placer." : "Touche un son pour le choisir.");
        } else {
            int s = startOf(sel), len = lenOf(sel);
            String cut = sel.isWhole() ? "" : "  ·  coupé";
            info.setText(sel.name + "\nDébut " + fmt(s) + "   Fin " + fmt(s + len) + "   Durée " + fmt(len) + cut);
        }
        float a = sel == null ? 0.4f : 1f;
        splitBtn.setAlpha(a); delBtn.setAlpha(a); resetBtn.setAlpha(sel == null || sel.isWhole() ? 0.4f : 1f);
        splitBtn.setEnabled(sel != null); delBtn.setEnabled(sel != null); resetBtn.setEnabled(sel != null && !sel.isWhole());
        undoBtn.setEnabled(!undo.isEmpty());
        timeText.setText(fmt(pos));
    }

    // ------------------------------------------------------------------
    // Lecture : la composition + les sons tels qu'ils sont placés
    // ------------------------------------------------------------------
    private void startPlay() {
        if (playing || !loaded) return;
        int total = timelineLen();
        if (pos >= total - perBar / 2) pos = 0;
        stopFlag = false;
        final AudioTrack tr;
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            tr = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(min, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            tr.play();
        } catch (Exception e) {
            Toast.makeText(this, "Lecture impossible", Toast.LENGTH_SHORT).show();
            return;
        }
        playing = true;
        playBtn.setText("■ Arrêter");
        final int end = total - perBar / 2;
        new Thread(() -> runPlay(tr, end), "clip-play").start();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private void runPlay(AudioTrack tr, int end) {
        final int B = 1024;
        float[] mix = new float[B];
        short[] out = new short[B];
        try {
            while (!stopFlag && pos < end) {
                int p0 = pos;
                int n = Math.min(B, end - p0);
                for (int i = 0; i < n; i++) mix[i] = (p0 + i) < beat.length ? beat[p0 + i] : 0f;
                BeatModel.Clip[] cs = liveClips;
                for (BeatModel.Clip c : cs) {
                    if (c.mute) continue;
                    short[] pcm = pcmByFile.get(c.file);
                    if (pcm == null) continue;
                    int cs0 = c.start(perBar), cl = c.used(pcm.length), cin = Math.max(0, c.cutIn);
                    int from = Math.max(p0, cs0), to = Math.min(p0 + n, cs0 + cl);
                    float g = c.vol / 32768f;
                    for (int i = from; i < to; i++) mix[i - p0] += pcm[cin + (i - cs0)] * g;
                }
                float mg = m.masterGain;
                for (int i = 0; i < n; i++) {
                    float v = mix[i] * mg;
                    if (v > 0.8f || v < -0.8f) v = (float) (Math.tanh((Math.abs(v) - 0.8f) / 0.2f) * 0.2f + 0.8f) * (v < 0 ? -1f : 1f);
                    out[i] = (short) Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
                }
                int w = tr.write(out, 0, n);
                if (w < 0) break;
                pos = p0 + w;
            }
        } catch (Exception ignored) {
        } finally {
            try { tr.stop(); } catch (Exception ignored) {}
            try { tr.release(); } catch (Exception ignored) {}
            final boolean atEnd = pos >= end;
            playing = false;
            ui.post(() -> {
                if (isFinishing()) return;
                playBtn.setText("▶ Écouter");
                if (atEnd) pos = 0;
                timeText.setText(fmt(pos));
                tv.invalidate();
            });
        }
    }

    private void stopPlay() {
        stopFlag = true;
        playing = false;
        if (playBtn != null) playBtn.setText("▶ Écouter");
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            timeText.setText(fmt(pos));
            tv.followPlayhead();
            tv.invalidate();
            if (playing) ui.postDelayed(this, 50);
        }
    };

    // ------------------------------------------------------------------
    // La frise : règle, composition, un couloir par son
    // ------------------------------------------------------------------
    private static final int NONE = 0, SEEK = 1, MOVE = 2, TRIM_L = 3, TRIM_R = 4, PAN = 5;

    private final class TimelineView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tri = new Path();
        private final RectF rf = new RectF();
        private final float dpx = getResources().getDisplayMetrics().density;
        private final int rulerH = Math.round(30 * dpx), laneH = Math.round(66 * dpx), gap = Math.round(4 * dpx);
        private final int edgeW = Math.round(30 * dpx);
        private double pps = 40 * dpx;                      // pixels par seconde
        private double scrollPx = 0;

        // geste en cours
        private int mode = NONE;
        private float downX, downY, lastX;
        private boolean moved = false;
        private int o0, in0, len0;                          // état du son au début du geste
        private long dragEnd0;

        TimelineView(Context c) {
            super(c);
        }

        private int lanes() { return 1 + m.clips.size(); }

        @Override protected void onMeasure(int w, int h) {
            int hh = rulerH + lanes() * (laneH + gap) + gap;
            setMeasuredDimension(MeasureSpec.getSize(w), Math.max(hh, Math.round(220 * dpx)));
        }

        void relayout() { clampScroll(); requestLayout(); invalidate(); }

        private double sx(long samples) { return samples / (double) RATE * pps - scrollPx; }
        private long sampleAt(float x) { return Math.round((x + scrollPx) / pps * RATE); }

        private void clampScroll() {
            double total = timelineLen() / (double) RATE * pps;
            double max = Math.max(0, total - getWidth() + 20 * dpx);
            if (scrollPx > max) scrollPx = max;
            if (scrollPx < 0) scrollPx = 0;
        }

        void zoomBy(float f) {
            double center = (getWidth() / 2.0 + scrollPx) / pps;
            double minPps = 4 * dpx, maxPps = 600 * dpx;
            pps = Math.max(minPps, Math.min(maxPps, pps * f));
            scrollPx = center * pps - getWidth() / 2.0;
            clampScroll();
            invalidate();
        }

        void fitAll() {
            int w = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
            pps = Math.max(4 * dpx, (w - 20 * dpx) / (timelineLen() / (double) RATE));
            scrollPx = 0;
            invalidate();
        }

        void followPlayhead() {
            if (!playing) return;
            double x = pos / (double) RATE * pps - scrollPx;
            if (x > getWidth() - 40 * dpx || x < 0) {
                scrollPx = pos / (double) RATE * pps - 40 * dpx;
                clampScroll();
            }
        }

        // ---------------- dessin ----------------
        @Override protected void onDraw(Canvas c) {
            int W = getWidth();
            c.drawColor(Ui.BG);
            double secBar = perBar / (double) RATE;
            double barPx = secBar * pps;

            // couloirs
            for (int i = 0; i < lanes(); i++) {
                int top = rulerH + gap + i * (laneH + gap);
                p.setColor(i % 2 == 0 ? 0xFF111820 : 0xFF0E141B);
                c.drawRect(0, top, W, top + laneH, p);
            }

            // lignes de mesure
            int firstBar = (int) Math.max(0, Math.floor(scrollPx / barPx));
            int step = barPx < 28 * dpx ? (int) Math.ceil(28 * dpx / barPx) : 1;
            p.setTextSize(11 * dpx);
            for (int b = firstBar - firstBar % step; ; b += step) {
                double x = b * barPx - scrollPx;
                if (x > W) break;
                if (x < -1) continue;
                p.setColor(0xFF2C3A4A);
                c.drawLine((float) x, rulerH - 8 * dpx, (float) x, getHeight(), p);
                p.setColor(Ui.MUTED);
                c.drawText(String.valueOf(b + 1), (float) x + 3 * dpx, rulerH - 12 * dpx, p);
                // temps
                double beatPx = barPx / m.beatsPerBar;
                if (beatPx > 14 * dpx && step == 1) {
                    p.setColor(0xFF1B2733);
                    for (int k = 1; k < m.beatsPerBar; k++) {
                        float bx = (float) (x + k * beatPx);
                        c.drawLine(bx, rulerH, bx, getHeight(), p);
                    }
                }
            }
            p.setColor(0xFF2C3A4A);
            c.drawLine(0, rulerH, W, rulerH, p);

            // composition
            int top0 = rulerH + gap;
            double bx0 = sx(0), bx1 = sx(BeatRenderer.totalSamples(m));
            p.setColor(0xFF1B2A3C);
            rf.set((float) bx0, top0 + 2, (float) bx1, top0 + laneH - 2);
            c.drawRoundRect(rf, 8, 8, p);
            drawWave(c, beatPeaks, 0, beat.length, (float) bx0, (float) bx1, top0, 0xFF4F7CA8, 0);
            p.setColor(Ui.MUTED);
            p.setTextSize(11 * dpx);
            c.drawText("Composition (instruments)", Math.max(6 * dpx, (float) bx0 + 6 * dpx), top0 + 14 * dpx, p);

            // les sons
            for (int i = 0; i < m.clips.size(); i++) {
                BeatModel.Clip k = m.clips.get(i);
                int top = rulerH + gap + (i + 1) * (laneH + gap);
                int s = startOf(k), len = lenOf(k);
                double x0 = sx(s), x1 = sx((long) s + len);
                boolean isSel = k == sel;
                p.setColor(k.mute ? 0xFF4A3B3B : VOICE_COL);
                p.setAlpha(isSel ? 255 : 190);
                rf.set((float) x0, top + 2, (float) x1, top + laneH - 2);
                c.drawRoundRect(rf, 8, 8, p);
                p.setAlpha(255);
                short[] pcm = pcmOf(k);
                float[] pk = peaksByFile.get(k.file);
                if (pk != null && pcm != null) drawWave(c, pk, Math.max(0, k.cutIn), len, (float) x0, (float) x1, top, 0xFFFFFFFF, 160);
                if (isSel) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2 * dpx);
                    p.setColor(0xFFFFFFFF);
                    c.drawRoundRect(rf, 8, 8, p);
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(0xFFFFFFFF);
                    float hw = 5 * dpx;
                    c.drawRoundRect((float) x0, top + 8 * dpx, (float) x0 + hw, top + laneH - 8 * dpx, 3, 3, p);
                    c.drawRoundRect((float) x1 - hw, top + 8 * dpx, (float) x1, top + laneH - 8 * dpx, 3, 3, p);
                }
                c.save();
                c.clipRect((float) Math.max(0, x0), top, (float) Math.min(W, x1), top + laneH);
                p.setColor(0xFFFFFFFF);
                p.setTextSize(11 * dpx);
                c.drawText(k.name + (k.mute ? " (coupé)" : ""), (float) Math.max(x0, 0) + 8 * dpx, top + 15 * dpx, p);
                c.restore();
            }

            // curseur de lecture
            float px = (float) sx(pos);
            if (px >= -2 && px <= W + 2) {
                p.setColor(0xFFFFD54F);
                p.setStrokeWidth(2 * dpx);
                c.drawLine(px, rulerH - 4 * dpx, px, getHeight(), p);
                tri.reset();
                tri.moveTo(px - 7 * dpx, 2 * dpx);
                tri.lineTo(px + 7 * dpx, 2 * dpx);
                tri.lineTo(px, 12 * dpx);
                tri.close();
                c.drawPath(tri, p);
            }
        }

        /** Forme d'onde d'une portion du son (srcFrom, srcLen en samples) dans le rectangle [x0, x1]. */
        private void drawWave(Canvas c, float[] pk, int srcFrom, int srcLen, float x0, float x1, int top, int color, int alpha) {
            if (pk.length == 0 || srcLen <= 0) return;
            int W = getWidth();
            int a = (int) Math.max(0, Math.floor(x0)), b = (int) Math.min(W, Math.ceil(x1));
            float mid = top + laneH / 2f, amp = laneH / 2f - 8 * dpx;
            p.setColor(color);
            if (alpha > 0) p.setAlpha(alpha);
            p.setStrokeWidth(Math.max(1f, dpx));
            double sPerPx = RATE / pps;
            for (int x = a; x < b; x += 2) {
                double s0 = srcFrom + (x - x0) * sPerPx;
                int i0 = (int) Math.max(0, s0 / PEAK_RES);
                int i1 = (int) Math.min(pk.length - 1, (s0 + 2 * sPerPx) / PEAK_RES);
                float v = 0f;
                for (int i = i0; i <= i1 && i < pk.length; i++) if (pk[i] > v) v = pk[i];
                float h = Math.max(1f, v * amp);
                c.drawLine(x, mid - h, x, mid + h, p);
            }
            p.setAlpha(255);
        }

        // ---------------- toucher ----------------
        private int laneOf(float y) {
            if (y < rulerH) return -2;
            int i = (int) ((y - rulerH - gap) / (laneH + gap));
            return i < 0 || i >= lanes() ? -1 : i;       // 0 = composition, i >= 1 = son i-1
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    downX = lastX = x;
                    downY = y;
                    moved = false;
                    mode = NONE;
                    int lane = laneOf(y);
                    if (lane == -2) {
                        mode = SEEK;
                        seekTo(x);
                        getParent().requestDisallowInterceptTouchEvent(true);
                    } else if (lane >= 1) {
                        BeatModel.Clip k = m.clips.get(lane - 1);
                        int s = startOf(k), len = lenOf(k);
                        float xs = (float) sx(s), xe = (float) sx((long) s + len);
                        float slop = 10 * dpx;
                        if (x >= xs - slop && x <= xe + slop) {
                            boolean changedSel = sel != k;
                            sel = k;
                            float edge = Math.min(edgeW, Math.max(14 * dpx, (xe - xs) / 3f));
                            mode = x <= xs + edge ? TRIM_L : x >= xe - edge ? TRIM_R : MOVE;
                            o0 = s; in0 = k.cutIn; len0 = len;
                            dragEnd0 = (long) s + len;
                            pushUndo();
                            stopPlayForEdit();
                            getParent().requestDisallowInterceptTouchEvent(true);
                            if (changedSel) refreshPanel();
                            invalidate();
                        }
                    }
                    if (mode == NONE) mode = PAN;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (Math.abs(x - downX) > 6 * dpx || Math.abs(y - downY) > 6 * dpx) moved = true;
                    if (mode == SEEK) { seekTo(x); }
                    else if (mode == PAN) {
                        scrollPx -= (x - lastX);
                        clampScroll();
                        invalidate();
                    } else if (sel != null && moved) {
                        drag((long) ((x - downX) / pps * RATE));
                    }
                    lastX = x;
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if ((mode == MOVE || mode == TRIM_L || mode == TRIM_R)) {
                        if (!moved) {                                                   // simple touche : le curseur se place ici
                            if (!undo.isEmpty()) undo.remove(undo.size() - 1);
                            seekTo(x);
                        } else afterEdit();
                        undoBtn.setEnabled(!undo.isEmpty());
                    } else if (mode == PAN && !moved && e.getActionMasked() == MotionEvent.ACTION_UP) {
                        if (laneOf(y) == -1 || laneOf(y) == 0) seekTo(x);              // touche dans le vide : curseur ici
                    }
                    mode = NONE;
                    return true;
                }
            }
            return true;
        }

        private void seekTo(float x) {
            pos = (int) Math.max(0, Math.min(timelineLen(), sampleAt(x)));
            timeText.setText(fmt(pos));
            invalidate();
        }

        private void stopPlayForEdit() { if (playing) stopPlay(); }

        /** d = déplacement du doigt en samples depuis le début du geste. */
        private void drag(long d) {
            BeatModel.Clip k = sel;
            short[] pcm = pcmOf(k);
            if (pcm == null) return;
            long limitEnd = Math.max(BeatRenderer.totalSamples(m), dragEnd0);       // on ne rallonge pas la frise en glissant
            if (mode == MOVE) {
                long ns = snap(o0 + d);
                ns = Math.max(0, Math.min(ns, limitEnd - len0));
                if (ns < 0) ns = 0;
                k.offset = (int) ns;
            } else if (mode == TRIM_L) {
                long edge = snap(o0 + d);
                long dd = edge - o0;
                long lo = -Math.min(in0, o0), hi = len0 - MIN_LEN;       // on peut rouvrir le début jusqu'au son d'origine
                dd = Math.max(lo, Math.min(hi, dd));
                k.offset = (int) (o0 + dd);
                k.cutIn = (int) (in0 + dd);
                k.cutLen = (int) (len0 - dd);
            } else if (mode == TRIM_R) {
                long end = snap(o0 + len0 + d);
                long nl = end - o0;
                long max = pcm.length - in0;
                nl = Math.max(MIN_LEN, Math.min(max, nl));
                k.cutIn = in0;
                k.cutLen = (int) nl;
                k.offset = o0;
            }
            refreshPanel();
            refreshLiveSoon();
            invalidate();
        }

        private void refreshLiveSoon() { refreshLive(); saveSoon(); }
    }
}
