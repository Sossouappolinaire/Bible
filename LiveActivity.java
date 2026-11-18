package com.tvkouame.studio;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Jouer en direct : clavier (piano, solo, basse) ou pads (batterie). On joue pendant N mesures avec métronome,
 * l'application enregistre puis répète la phrase sur tout le morceau.
 */
public class LiveActivity extends Activity {

    private static final int[] TCOL = {0xFFE67E22, 0xFF9B59B6, 0xFF2E86DE, 0xFF2ECC71};
    private static final int[] BASE = {0, 36, 48, 60};
    private static final int[] LENS = {1, 2, 4, 8};
    private static final String[] ALN = {"Aucun", "1/8", "1/16", "1/32"};
    private static final int[] ALT = {0, 12, 6, 3};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private BeatModel m;
    private int inst, startBar, lenIdx = 1, alnIdx = 2, base, recN = 2;
    private boolean metro = true, repeat = true, recording = false, previewing = false;
    private final LiveMixer mixer = new LiveMixer();
    private final HashMap<Integer, float[]> cache = new HashMap<>();
    private final HashMap<Integer, LiveMixer.Voice> held = new HashMap<>();
    private final HashMap<Integer, Long> heldAt = new HashMap<>();
    private final List<long[]> events = new ArrayList<>();
    private List<BeatModel.Note> undoNotes;
    private boolean[][] undoCustom;

    private TextView status, barInfo;
    private Button rhyBtn, recBtn, playBtn, lenBtn, alnBtn, metroBtn, repBtn;
    private LinearLayout padHost;
    private View inputView;
    private final Runnable saveRun = () -> StudioStore.save(this, m);
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            long rel = mixer.relFrames();
            int bar = BeatRenderer.samplesPerBar(m);
            if (rel >= 0) {
                if (rel < bar) {
                    int beatS = bar / m.beatsPerBar;
                    status.setText("Décompte… " + (m.beatsPerBar - (int) (rel / beatS)));
                } else {
                    int mes = (int) Math.min(recN, (rel - bar) / bar + 1);
                    status.setText("● Enregistrement : mesure " + mes + " / " + recN);
                }
            }
            ui.postDelayed(this, 80);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        m = StudioStore.load(this, getIntent().getStringExtra("id"));
        if (m == null) { finish(); return; }
        inst = Math.max(0, Math.min(3, getIntent().getIntExtra("track", 0)));
        startBar = Math.max(0, Math.min(m.bars - 1, getIntent().getIntExtra("bar", 0)));
        base = BASE[inst];
        build();
        prerender();
    }

    @Override protected void onResume() { super.onResume(); mixer.start(); }

    @Override protected void onPause() {
        super.onPause();
        recording = false;
        ui.removeCallbacks(poll);
        mixer.stopBacking();
        previewing = false;
        mixer.stop();
        ui.removeCallbacks(saveRun);
        if (m != null) StudioStore.save(this, m);
    }

    private Button btn(String t, int color) {
        Button x = Ui.button(this, t, color);
        x.setTextSize(12);
        return x;
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this, 10), Ui.dp(this, 28), Ui.dp(this, 10), Ui.dp(this, 10));
        root.addView(Ui.text(this, "Jouer en direct : " + BeatModel.TRACKS[inst], 18, TCOL[inst], true));

        LinearLayout r1 = Ui.row(this);
        lenBtn = btn("", 0xFF2C3A4A);
        alnBtn = btn("", 0xFF2C3A4A);
        lenBtn.setOnClickListener(v -> { lenIdx = (lenIdx + 1) % LENS.length; refresh(); });
        alnBtn.setOnClickListener(v -> { alnIdx = (alnIdx + 1) % ALN.length; refresh(); });
        r1.addView(lenBtn, Ui.weight(this, 2));
        r1.addView(alnBtn, Ui.weight(this, 2));
        root.addView(r1);

        LinearLayout r2 = Ui.row(this);
        metroBtn = btn("", 0xFF2C3A4A);
        repBtn = btn("", 0xFF2C3A4A);
        metroBtn.setOnClickListener(v -> { metro = !metro; refresh(); });
        repBtn.setOnClickListener(v -> { repeat = !repeat; refresh(); });
        r2.addView(metroBtn, Ui.weight(this, 2));
        r2.addView(repBtn, Ui.weight(this, 2));
        root.addView(r2);

        LinearLayout r3 = Ui.row(this);
        Button mm = btn("−", 0xFF2C3A4A), mp = btn("+", 0xFF2C3A4A);
        barInfo = Ui.text(this, "", 14, Ui.TEXT, true);
        barInfo.setGravity(android.view.Gravity.CENTER);
        mm.setOnClickListener(v -> { if (!busy() && startBar > 0) { startBar--; refresh(); } });
        mp.setOnClickListener(v -> { if (!busy() && startBar < m.bars - 1) { startBar++; refresh(); } });
        r3.addView(mm, Ui.weight(this, 2));
        r3.addView(barInfo, new LinearLayout.LayoutParams(0, -2, 3f));
        r3.addView(mp, Ui.weight(this, 2));
        if (inst != BeatModel.DRUMS) {
            Button o1 = btn("Octave −", 0xFF2C3A4A), o2 = btn("Octave +", 0xFF2C3A4A);
            o1.setOnClickListener(v -> shiftOctave(-12));
            o2.setOnClickListener(v -> shiftOctave(12));
            r3.addView(o1, Ui.weight(this, 2));
            r3.addView(o2, Ui.weight(this, 2));
        }
        root.addView(r3);

        status = Ui.text(this, "", 14, Ui.MUTED, true);
        status.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        root.addView(status);

        padHost = new LinearLayout(this);
        padHost.setOrientation(LinearLayout.VERTICAL);
        inputView = inst == BeatModel.DRUMS ? new PadsView() : new KeysView();
        padHost.addView(inputView, new LinearLayout.LayoutParams(-1, -1));
        root.addView(padHost, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout r4 = Ui.row(this);
        recBtn = btn("● Enregistrer", 0xFFD64545);
        playBtn = btn("▶ Écouter la boucle", Ui.GREEN);
        recBtn.setOnClickListener(v -> { if (recording) cancelRecord(); else startRecord(); });
        playBtn.setOnClickListener(v -> { if (previewing) stopPreview(); else startPreview(); });
        r4.addView(recBtn, Ui.weight(this, 2));
        r4.addView(playBtn, Ui.weight(this, 2));
        root.addView(r4);
        if (inst != BeatModel.DRUMS) {
            rhyBtn = btn("🥁 Rythme du style en boucle (pour composer dessus)", TCOL[0]);
            rhyBtn.setOnClickListener(v -> { if (previewing) stopPreview(); else startPreview(BeatModel.DRUMS); });
            root.addView(rhyBtn, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout r5 = Ui.row(this);
        Button undo = btn("↩ Annuler", 0xFF2C3A4A), done = btn("Terminé", Ui.ACCENT);
        undo.setOnClickListener(v -> undo());
        done.setOnClickListener(v -> finish());
        r5.addView(undo, Ui.weight(this, 2));
        r5.addView(done, Ui.weight(this, 2));
        root.addView(r5);
        setContentView(root);
        refresh();
    }

    private boolean busy() { return recording; }

    private void shiftOctave(int d) {
        int nb = base + d;
        if (nb < 24 || nb + 24 > 96) return;
        base = nb;
        prerender();
        inputView.invalidate();
    }

    private void refresh() {
        int n = Math.min(LENS[lenIdx], m.bars - startBar);
        lenBtn.setText("Durée : " + LENS[lenIdx] + (LENS[lenIdx] > 1 ? " mesures" : " mesure"));
        alnBtn.setText("Alignement : " + ALN[alnIdx]);
        metroBtn.setText("Métronome : " + (metro ? "oui" : "non"));
        repBtn.setText("Répéter partout : " + (repeat ? "oui" : "non"));
        barInfo.setText("Début : mesure " + (startBar + 1) + (n < LENS[lenIdx] ? " (" + n + " mes.)" : ""));
        if (!recording) status.setText("Appuie sur Enregistrer : 1 mesure de décompte, puis tu joues " + n + (n > 1 ? " mesures." : " mesure."));
    }

    // ---------------- Sons ----------------
    private float[] sound(int pitch) {
        int key = inst * 1000 + pitch;
        synchronized (cache) {
            float[] f = cache.get(key);
            if (f != null) return f;
        }
        float[] f;
        if (inst == BeatModel.DRUMS) f = Synth.drum(pitch);
        else {
            int len = (int) (Synth.RATE * (inst == BeatModel.PIANO ? 1.2 : 0.9));
            f = inst == BeatModel.BASS ? Synth.bass(pitch, len) : inst == BeatModel.PIANO ? Synth.piano(pitch, len) : Synth.lead(pitch, len);
        }
        synchronized (cache) { cache.put(key, f); }
        return f;
    }

    private void prerender() {
        final int lo = base, ins = inst;
        new Thread(() -> {
            if (ins == BeatModel.DRUMS) for (int p = 0; p < 5; p++) sound(p);
            else for (int p = lo; p <= lo + 24; p++) sound(p);
        }).start();
    }

    // ---------------- Jeu ----------------
    private void onDown(int pitch) {
        if (held.containsKey(pitch)) return;
        held.put(pitch, mixer.play(sound(pitch), 1f));
        if (recording) { long r = mixer.relFrames(); if (r >= 0) heldAt.put(pitch, r); }
    }

    private void onUp(int pitch) {
        LiveMixer.Voice v = held.remove(pitch);
        if (v != null && inst != BeatModel.DRUMS) mixer.release(v);
        Long on = heldAt.remove(pitch);
        if (on != null && recording) events.add(new long[]{pitch, on, mixer.relFrames()});
    }

    // ---------------- Enregistrement ----------------
    private void addClick(float[] out, int pos, boolean accent) {
        float[] c = Synth.click(accent);
        for (int i = 0; i < c.length && pos + i < out.length; i++) out[pos + i] += c[i] * 0.6f;
    }

    private void startRecord() {
        stopPreview();
        events.clear();
        heldAt.clear();
        final int bar = BeatRenderer.samplesPerBar(m), beatS = bar / m.beatsPerBar;
        recN = Math.max(1, Math.min(LENS[lenIdx], m.bars - startBar));
        float[] body = BeatRenderer.renderBars(m, startBar, startBar + recN, -2 - inst);
        float[] back = new float[bar * (recN + 1)];
        System.arraycopy(body, 0, back, bar, Math.min(body.length, bar * recN));
        for (int k = 0; k < m.beatsPerBar; k++) addClick(back, k * beatS, k == 0);
        if (metro) for (int k = 0; k < m.beatsPerBar * recN; k++) addClick(back, bar + k * beatS, k % m.beatsPerBar == 0);
        recording = true;
        recBtn.setText("■ Arrêter");
        mixer.startBacking(back, false, () -> ui.postDelayed(this::finishRecord, 150));
        ui.post(poll);
    }

    private void cancelRecord() {
        recording = false;
        ui.removeCallbacks(poll);
        mixer.stopBacking();
        heldAt.clear();
        recBtn.setText("● Enregistrer");
        status.setText("Enregistrement annulé.");
    }

    private void finishRecord() {
        if (!recording) return;
        recording = false;
        ui.removeCallbacks(poll);
        long total = (long) BeatRenderer.samplesPerBar(m) * (recN + 1);
        for (Map.Entry<Integer, Long> e : heldAt.entrySet()) events.add(new long[]{e.getKey(), e.getValue(), total});
        heldAt.clear();
        recBtn.setText("● Enregistrer");
        int n = applyRecording();
        status.setText(n == 0 ? "Aucune note enregistrée. Réessaie !" : n + " notes enregistrées, la phrase se répète.");
        if (n > 0) startPreview();
    }

    private void saveUndo() {
        undoNotes = new ArrayList<>();
        for (BeatModel.Note x : m.notes) undoNotes.add(new BeatModel.Note(x.inst, x.bar, x.tick, x.len, x.pitch, x.vel));
        undoCustom = new boolean[BeatModel.NT][];
        for (int t = 0; t < BeatModel.NT; t++) undoCustom[t] = m.custom[t].clone();
    }

    private void undo() {
        if (undoNotes == null) { status.setText("Rien à annuler."); return; }
        stopPreview();
        m.notes.clear();
        m.notes.addAll(undoNotes);
        for (int t = 0; t < BeatModel.NT; t++) m.custom[t] = undoCustom[t].clone();
        undoNotes = null;
        ui.removeCallbacks(saveRun);
        StudioStore.save(this, m);
        status.setText("Dernier enregistrement annulé.");
    }

    private int applyRecording() {
        saveUndo();
        final int bar = BeatRenderer.samplesPerBar(m);
        final double tl = Synth.RATE * 60.0 / m.bpm / BeatModel.PPQ;
        final int bt = m.barTicks(), total = recN * bt, g = ALT[alnIdx];
        for (int b = startBar; b < startBar + recN; b++) { m.clearNotes(inst, b); m.custom[inst][b] = true; }
        int count = 0;
        for (long[] ev : events) {
            double onT = (ev[1] - bar) / tl, offT = (ev[2] - bar) / tl;
            if (onT < -6) continue;                        // joué pendant le décompte
            int t0 = (int) Math.round(Math.max(0, onT));
            int len = (int) Math.round(offT - onT);
            if (inst == BeatModel.DRUMS) len = 6;
            if (g > 0) {
                t0 = Math.round(t0 / (float) g) * g;
                len = Math.max(g, Math.round(len / (float) g) * g);
            } else len = Math.max(3, len);
            if (t0 >= total) continue;
            int nb = t0 / bt, tb = t0 % bt;
            len = Math.max(1, Math.min(len, bt - tb));
            m.notes.add(new BeatModel.Note(inst, startBar + nb, tb, len, (int) ev[0], 1f));
            count++;
        }
        if (repeat && count > 0) {
            for (int b = startBar + recN; b < m.bars; b++) {
                m.clearNotes(inst, b);
                for (BeatModel.Note x : m.notesOf(inst, startBar + (b - startBar) % recN))
                    m.notes.add(new BeatModel.Note(inst, b, x.tick, x.len, x.pitch, x.vel));
                m.custom[inst][b] = true;
            }
        }
        ui.removeCallbacks(saveRun);
        StudioStore.save(this, m);
        return count;
    }

    // ---------------- Écoute de la boucle ----------------
    private void startPreview() { startPreview(-1); }

    private void startPreview(int only) {
        if (recording) return;
        final int bar = BeatRenderer.samplesPerBar(m);
        int n = Math.max(1, Math.min(LENS[lenIdx], m.bars - startBar));
        float[] body = BeatRenderer.renderBars(m, startBar, startBar + n, only);
        float[] loop = new float[bar * n];
        System.arraycopy(body, 0, loop, 0, Math.min(body.length, loop.length));
        mixer.startBacking(loop, true, null);
        previewing = true;
        if (only == BeatModel.DRUMS && rhyBtn != null) rhyBtn.setText("■ Stop le rythme");
        else playBtn.setText("■ Stop");
    }

    private void stopPreview() {
        if (previewing) mixer.stopBacking();
        previewing = false;
        if (playBtn != null) playBtn.setText("▶ Écouter la boucle");
        if (rhyBtn != null) rhyBtn.setText("🥁 Rythme du style en boucle (pour composer dessus)");
    }

    // ---------------- Vues tactiles ----------------
    private abstract class HitView extends View {
        protected final HashMap<Integer, Integer> active = new HashMap<>();
        protected final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        HitView() { super(LiveActivity.this); }

        abstract int hit(float x, float y);

        private void press(int id, float x, float y) {
            int h = hit(x, y);
            if (h < 0) return;
            active.put(id, h);
            onDown(h);
            invalidate();
        }

        private void release(int id) {
            Integer h = active.remove(id);
            if (h != null) onUp(h);
            invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    int i = e.getActionIndex();
                    press(e.getPointerId(i), e.getX(i), e.getY(i));
                    break;
                }
                case MotionEvent.ACTION_MOVE:
                    for (int i = 0; i < e.getPointerCount(); i++) {
                        int id = e.getPointerId(i);
                        Integer cur = active.get(id);
                        int h = hit(e.getX(i), e.getY(i));
                        if (h >= 0 && (cur == null || cur != h)) {
                            if (cur != null) release(id);
                            press(id, e.getX(i), e.getY(i));
                        }
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    release(e.getPointerId(e.getActionIndex()));
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    for (Integer id : new ArrayList<>(active.keySet())) release(id);
                    break;
                default:
                    break;
            }
            return true;
        }
    }

    private static boolean isBlack(int pitch) {
        int pc = pitch % 12;
        return pc == 1 || pc == 3 || pc == 6 || pc == 8 || pc == 10;
    }

    /** Clavier de 2 octaves : touches blanches et noires (piano), mêmes touches teintées pour le solo et la basse. */
    private class KeysView extends HitView {
        private static final int NK = 25, NW = 15;

        private float ww() { return getWidth() / (float) NW; }

        private float whiteX(int pitch) {
            int idx = 0;
            for (int q = base; q < pitch; q++) if (!isBlack(q)) idx++;
            return idx * ww();
        }

        @Override int hit(float x, float y) {
            float w = ww(), bw = w * 0.62f, bh = getHeight() * 0.6f;
            if (y < bh)
                for (int q = base; q < base + NK; q++)
                    if (isBlack(q)) {
                        float cx = whiteX(q);   // bord gauche de la blanche suivante
                        if (x >= cx - bw / 2 && x <= cx + bw / 2) return q;
                    }
            int idx = (int) (x / w);
            int k = 0;
            for (int q = base; q < base + NK; q++) {
                if (isBlack(q)) continue;
                if (k == idx) return q;
                k++;
            }
            return -1;
        }

        @Override protected void onDraw(Canvas c) {
            float w = ww(), h = getHeight(), bw = w * 0.62f, bh = h * 0.6f;
            int[] tones = m.chordTones(startBar);
            int root = m.chordRoot[startBar];
            p.setTextSize(Ui.dp(LiveActivity.this, 10));
            for (int q = base; q < base + NK; q++) {
                if (isBlack(q)) continue;
                float x = whiteX(q);
                boolean on = active.containsValue(q);
                boolean ch = false;
                for (int t : tones) if ((q - root - t + 120) % 12 == 0) ch = true;
                p.setColor(on ? TCOL[inst] : inst == BeatModel.SOLO ? 0xFFEAF7EF : inst == BeatModel.BASS ? 0xFFEDE6F5 : 0xFFF4F4F4);
                c.drawRect(x + 1, 0, x + w - 1, h, p);
                if (ch) { p.setColor(TCOL[inst]); c.drawCircle(x + w / 2, h - Ui.dp(LiveActivity.this, 22), w * 0.16f, p); }
                if (q % 12 == 0) {
                    p.setColor(0xFF555555);
                    c.drawText(BeatModel.NOTES[0] + (q / 12 - 1), x + 3, h - Ui.dp(LiveActivity.this, 6), p);
                }
            }
            for (int q = base; q < base + NK; q++) {
                if (!isBlack(q)) continue;
                float cx = whiteX(q);
                p.setColor(active.containsValue(q) ? TCOL[inst] : 0xFF1B1B1B);
                c.drawRoundRect(cx - bw / 2, 0, cx + bw / 2, bh, 6, 6, p);
            }
        }
    }

    /** 5 pads de batterie. */
    private class PadsView extends HitView {
        @Override int hit(float x, float y) {
            int col = (int) (x / (getWidth() / 3f)), row = (int) (y / (getHeight() / 2f));
            int i = row * 3 + col;
            return i >= 0 && i < 5 && col < 3 && row < 2 ? i : -1;
        }

        @Override protected void onDraw(Canvas c) {
            float pw = getWidth() / 3f, ph = getHeight() / 2f;
            p.setTextSize(Ui.dp(LiveActivity.this, 14));
            p.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < 5; i++) {
                float x = (i % 3) * pw, y = (i / 3) * ph;
                boolean on = active.containsValue(i);
                p.setColor(on ? TCOL[0] : 0xFF2C3A4A);
                c.drawRoundRect(x + 6, y + 6, x + pw - 6, y + ph - 6, 18, 18, p);
                p.setColor(0xFFFFFFFF);
                c.drawText(BeatModel.DRUM_ROWS[i], x + pw / 2, y + ph / 2, p);
            }
            p.setTextAlign(Paint.Align.LEFT);
        }
    }
}
