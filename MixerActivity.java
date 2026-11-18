package com.tvkouame.studio;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Studio de mixage : après la composition, chaque instrument et chaque voix a sa propre piste
 * (niveau, coupure, écoute seule, indicateur de niveau). Les réglages s'entendent en direct.
 */
public class MixerActivity extends Activity {

    private static final int[] TCOL = {0xFFE67E22, 0xFF9B59B6, 0xFF2E86DE, 0xFF2ECC71};
    private static final int VOICE_COL = 0xFFE74C3C;
    private static final int OFF = 0xFF2C3A4A;

    /** Une piste de la table : un instrument (kind 0) ou un son enregistré / importé (kind 1). */
    private static final class Chan {
        String name;
        int color;
        int inst = -1;                 // instrument (0..3) ou -1
        BeatModel.Clip clip;           // son ajouté, sinon null
        short[] pcm;                   // échantillons 16 bits
        float[] fpcm;                  // ou flottants (instruments)
        int start;                     // début en samples
        int cutIn;                     // samples retirés au début du son (coupe)
        int len;                       // samples joués
        volatile float gain = 1f;
        volatile boolean mute = false;
        volatile boolean solo = false;
        volatile float peak = 0f;
        MeterView meter;
        Button muteBtn, soloBtn;
        TextView valLbl;
        SeekBar seek;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private BeatModel m;
    private final List<Chan> chans = new ArrayList<>();
    private volatile float master = 1f;
    private volatile int pos = 0;
    private int total = 0;
    private volatile boolean playing = false, stopFlag = false;
    private Thread th;
    private Button playBtn;
    private TextView timeText, status, masterLbl;
    private SeekBar posSeek;
    private LinearLayout list;
    private boolean userSeeking = false;
    private final Runnable saveRun = this::saveNow;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        m = StudioStore.load(this, getIntent().getStringExtra("id"));
        if (m == null) { finish(); return; }
        buildUi();
        loadSounds();
    }

    /** Sauvegarde dès la pause : le Studio se recharge juste après et doit voir les niveaux réglés. */
    @Override protected void onPause() {
        super.onPause();
        stopPlay();
        saveNow();
    }

    private void saveNow() {
        ui.removeCallbacks(saveRun);
        if (m == null) return;
        m.masterGain = master;
        for (Chan c : chans) {
            if (c.inst >= 0) { m.mixGain[c.inst] = c.gain; m.mixMute[c.inst] = c.mute; }
            else if (c.clip != null) { c.clip.vol = c.gain; c.clip.mute = c.mute; }
        }
        StudioStore.save(this, m);
    }

    private void saveSoon() {
        ui.removeCallbacks(saveRun);
        ui.postDelayed(saveRun, 500);
    }

    // ------------------------------------------------------------------
    private void buildUi() {
        int pad = Ui.dp(this, 14);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad * 2);
        sv.addView(root);

        TextView back = Ui.text(this, "‹ Retour au Studio", 15, Ui.ACCENT, true);
        back.setPadding(0, 0, 0, Ui.dp(this, 8));
        back.setOnClickListener(v -> finish());
        root.addView(back);
        root.addView(Ui.text(this, "Studio de mixage", 24, Ui.TEXT, true));
        TextView sub = Ui.text(this, "Une piste par instrument et par voix. Baisse ce qui couvre, monte ce qu'on n'entend pas : "
                + "tu entends le changement tout de suite.", 13, Ui.MUTED, false);
        sub.setPadding(0, 0, 0, Ui.dp(this, 10));
        root.addView(sub);

        LinearLayout tc = Ui.card(this);
        playBtn = Ui.button(this, "▶ Écouter le mix", Ui.GREEN);
        playBtn.setTextColor(0xFF000000);
        playBtn.setOnClickListener(v -> { if (playing) stopPlay(); else startPlay(); });
        tc.addView(playBtn);
        timeText = Ui.text(this, "0:00", 26, Ui.TEXT, true);
        timeText.setGravity(Gravity.CENTER);
        timeText.setPadding(0, Ui.dp(this, 8), 0, 0);
        tc.addView(timeText);
        posSeek = new SeekBar(this);
        posSeek.setMax(1000);
        posSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user && total > 0) { pos = (int) ((long) p * total / 1000L); showTime(pos); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { userSeeking = false; }
        });
        tc.addView(posSeek);
        status = Ui.text(this, "Préparation des pistes…", 12, Ui.MUTED, false);
        status.setGravity(Gravity.CENTER);
        tc.addView(status);
        root.addView(tc);

        LinearLayout pc = Ui.card(this);
        pc.addView(Ui.text(this, "Réglage rapide", 15, Ui.TEXT, true));
        LinearLayout pr = Ui.row(this);
        Button voice = Ui.button(this, "Voix devant", OFF);
        voice.setTextSize(13);
        voice.setOnClickListener(v -> preset(0));
        Button equal = Ui.button(this, "Équilibré", OFF);
        equal.setTextSize(13);
        equal.setOnClickListener(v -> preset(1));
        Button reset = Ui.button(this, "Tout à 100 %", OFF);
        reset.setTextSize(13);
        reset.setOnClickListener(v -> preset(2));
        pr.addView(voice, Ui.weight(this, Ui.dp(this, 2)));
        pr.addView(equal, Ui.weight(this, Ui.dp(this, 2)));
        pr.addView(reset, Ui.weight(this, Ui.dp(this, 2)));
        pc.addView(pr);
        pc.addView(Ui.text(this, "« Voix devant » baisse les instruments pour que la voix passe bien au-dessus.", 12, Ui.MUTED, false));
        root.addView(pc);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        LinearLayout mc = Ui.card(this);
        mc.addView(Ui.text(this, "Niveau général", 15, Ui.TEXT, true));
        masterLbl = Ui.text(this, "", 13, Ui.MUTED, false);
        mc.addView(masterLbl);
        SeekBar ms = new SeekBar(this);
        ms.setMax(150);
        master = m.masterGain;
        ms.setProgress(Math.round(master * 100));
        masterLbl.setText("Niveau général : " + Math.round(master * 100) + " %");
        ms.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                master = p / 100f;
                masterLbl.setText("Niveau général : " + p + " %");
                if (user) saveSoon();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        mc.addView(ms);
        mc.addView(Ui.text(this, "Le volume est limité automatiquement : le son ne sature pas.", 12, Ui.MUTED, false));
        root.addView(mc);

        Button done = Ui.button(this, "Terminé (retour pour exporter)", Ui.ACCENT);
        done.setTextColor(0xFF000000);
        done.setOnClickListener(v -> finish());
        root.addView(done);
        setContentView(sv);
    }

    private void preset(int kind) {
        for (Chan c : chans) {
            float g;
            if (kind == 2) g = 1f;
            else if (c.inst >= 0) {
                // voix devant : instruments plus bas ; équilibré : batterie/basse devant, piano/solo derrière
                float[] a = kind == 0 ? new float[]{0.55f, 0.55f, 0.4f, 0.35f} : new float[]{0.8f, 0.8f, 0.6f, 0.5f};
                g = a[c.inst];
            } else g = kind == 0 ? 1.3f : 1f;
            c.gain = g;
            c.mute = false;
            c.solo = false;
        }
        for (Chan c : chans) updateChanUi(c);
        saveSoon();
    }

    private void updateChanUi(Chan c) {
        c.valLbl.setText(Math.round(c.gain * 100) + " %");
        c.muteBtn.setBackground(Ui.rounded(c.mute ? Ui.RED : OFF, Ui.dp(this, 10)));
        c.soloBtn.setBackground(Ui.rounded(c.solo ? Ui.ORANGE : OFF, Ui.dp(this, 10)));
        if (c.seek != null) c.seek.setProgress(Math.round(c.gain * 100));
    }

    // ------------------------------------------------------------------
    private void loadSounds() {
        final BeatModel mm = m;
        new Thread(() -> {
            final List<Chan> out = new ArrayList<>();
            String err = null;
            try {
                float[][] st = BeatRenderer.renderStems(mm);
                int per = BeatRenderer.samplesPerBar(mm);
                for (int t = 0; t < BeatModel.NT; t++) {
                    Chan c = new Chan();
                    c.name = BeatModel.TRACKS[t];
                    c.color = TCOL[t];
                    c.inst = t;
                    c.fpcm = st[t];
                    c.len = st[t].length;
                    c.gain = mm.mixGain[t];
                    c.mute = mm.mixMute[t];
                    out.add(c);
                }
                File dir = StudioStore.dirOf(this, mm.id);
                for (BeatModel.Clip k : mm.clips) {
                    try {
                        WavIO.Mono w = WavIO.readWav(new File(dir, k.file), 60 * 60 * WavIO.RATE);
                        Chan c = new Chan();
                        c.name = k.name;
                        c.color = VOICE_COL;
                        c.clip = k;
                        c.pcm = w.rate == WavIO.RATE ? w.data : WavIO.resample(w.data, w.len, w.rate, WavIO.RATE);
                        int full = w.rate == WavIO.RATE ? w.len : c.pcm.length;
                        c.cutIn = Math.max(0, Math.min(k.cutIn, full));
                        c.len = k.used(full);
                        c.start = k.start(per);
                        c.gain = k.vol;
                        c.mute = k.mute;
                        out.add(c);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
            }
            final String ferr = err;
            ui.post(() -> {
                if (isFinishing()) return;
                if (ferr != null) { status.setText("Erreur : " + ferr); return; }
                chans.addAll(out);
                int mx = 0;
                for (Chan c : chans) mx = Math.max(mx, c.start + c.len);
                total = mx;
                buildChannels();
                status.setText("Appuie sur ▶ puis bouge les curseurs pendant l'écoute.");
                showTime(0);
            });
        }).start();
    }

    private void buildChannels() {
        list.removeAllViews();
        boolean voiceHeader = false;
        list.addView(Ui.text(this, "Instruments", 16, Ui.TEXT, true));
        for (final Chan c : chans) {
            if (c.clip != null && !voiceHeader) {
                voiceHeader = true;
                TextView h = Ui.text(this, "Voix et sons ajoutés", 16, Ui.TEXT, true);
                h.setPadding(0, Ui.dp(this, 14), 0, 0);
                list.addView(h);
            }
            list.addView(channelView(c));
        }
        if (!voiceHeader) {
            TextView h = Ui.text(this, "Aucune voix pour l'instant : enregistre la voix dans le Studio (étape 3), elle apparaîtra ici avec son propre curseur.",
                    12, Ui.MUTED, false);
            h.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 8));
            list.addView(h);
        }
    }

    private View channelView(final Chan c) {
        LinearLayout box = Ui.card(this);
        box.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) box.getLayoutParams();
        lp.setMargins(0, Ui.dp(this, 8), 0, 0);

        LinearLayout r1 = Ui.row(this);
        View dot = new View(this);
        Ui.setDot(dot, c.color);
        r1.addView(dot, new LinearLayout.LayoutParams(Ui.dp(this, 14), Ui.dp(this, 14)));
        TextView nm = Ui.text(this, "  " + c.name, 15, Ui.TEXT, true);
        nm.setSingleLine(true);
        r1.addView(nm, new LinearLayout.LayoutParams(0, -2, 1f));
        c.valLbl = Ui.text(this, "", 13, Ui.MUTED, false);
        r1.addView(c.valLbl);
        box.addView(r1);

        c.meter = new MeterView(this, c);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 12));
        mp.setMargins(0, Ui.dp(this, 8), 0, Ui.dp(this, 2));
        box.addView(c.meter, mp);

        LinearLayout r2 = Ui.row(this);
        c.seek = new SeekBar(this);
        c.seek.setMax(c.clip != null ? 200 : 150);
        c.seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                c.gain = p / 100f;
                c.valLbl.setText(p + " %");
                if (user) saveSoon();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        r2.addView(c.seek, new LinearLayout.LayoutParams(0, -2, 1f));
        c.muteBtn = Ui.button(this, "M", OFF);
        c.muteBtn.setMinWidth(Ui.dp(this, 44));
        c.muteBtn.setMinimumWidth(Ui.dp(this, 44));
        c.muteBtn.setMinHeight(Ui.dp(this, 40));
        c.muteBtn.setMinimumHeight(Ui.dp(this, 40));
        c.muteBtn.setOnClickListener(v -> { c.mute = !c.mute; updateChanUi(c); saveSoon(); });
        c.soloBtn = Ui.button(this, "S", OFF);
        c.soloBtn.setMinWidth(Ui.dp(this, 44));
        c.soloBtn.setMinimumWidth(Ui.dp(this, 44));
        c.soloBtn.setMinHeight(Ui.dp(this, 40));
        c.soloBtn.setMinimumHeight(Ui.dp(this, 40));
        c.soloBtn.setOnClickListener(v -> {
            boolean on = !c.solo;
            for (Chan o : chans) { o.solo = false; updateChanUi(o); }
            c.solo = on;
            updateChanUi(c);
            if (on && !playing) startPlay();
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMargins(Ui.dp(this, 6), 0, 0, 0);
        r2.addView(c.muteBtn, bp);
        r2.addView(c.soloBtn, bp);
        box.addView(r2);
        updateChanUi(c);
        return box;
    }

    // ------------------------------------------------------------------
    private void showTime(int p) {
        int s = p / Synth.RATE;
        timeText.setText(String.format(Locale.US, "%d:%02d", s / 60, s % 60));
    }

    private void startPlay() {
        if (playing || chans.isEmpty()) return;
        if (pos >= total) pos = 0;
        stopFlag = false;
        final AudioTrack tr;
        try {
            int min = AudioTrack.getMinBufferSize(Synth.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            tr = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(Synth.RATE)
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
        th = new Thread(() -> runPlay(tr), "mixer");
        th.start();
        ui.post(tick);
    }

    private void runPlay(AudioTrack tr) {
        final int B = 1024;
        float[] mix = new float[B];
        short[] out = new short[B];
        try {
            while (!stopFlag && pos < total) {
                int p0 = pos;
                int n = Math.min(B, total - p0);
                java.util.Arrays.fill(mix, 0, n, 0f);
                boolean anySolo = false;
                for (Chan c : chans) if (c.solo) anySolo = true;
                for (Chan c : chans) {
                    float pk = 0f;
                    boolean audible = !c.mute && (!anySolo || c.solo);
                    float g = c.gain;
                    int from = Math.max(p0, c.start), to = Math.min(p0 + n, c.start + c.len);
                    for (int i = from; i < to; i++) {
                        int k = i - c.start;
                        float v = c.fpcm != null ? c.fpcm[k] : c.pcm[c.cutIn + k] / 32768f;
                        v *= g;
                        float a = v < 0 ? -v : v;
                        if (a > pk) pk = a;
                        if (audible) mix[i - p0] += v;
                    }
                    c.peak = audible ? pk : 0f;
                }
                float mg = master;
                for (int i = 0; i < n; i++) {
                    float v = mix[i] * mg;
                    // limiteur doux : le son ne sature pas
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
            for (Chan c : chans) c.peak = 0f;
            playing = false;
            ui.post(() -> {
                if (isFinishing()) return;
                playBtn.setText("▶ Écouter le mix");
                if (pos >= total) { pos = 0; showTime(0); posSeek.setProgress(0); }
            });
        }
    }

    private void stopPlay() {
        stopFlag = true;
        playing = false;
        playBtn.setText("▶ Écouter le mix");
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            for (Chan c : chans) if (c.meter != null) c.meter.invalidate();
            if (total > 0 && !userSeeking) {
                showTime(pos);
                posSeek.setProgress((int) ((long) pos * 1000L / total));
            }
            if (playing) ui.postDelayed(this, 60);
            else for (Chan c : chans) if (c.meter != null) c.meter.invalidate();
        }
    };

    /** Indicateur de niveau d'une piste : vert, orange puis rouge près de la saturation. */
    private static final class MeterView extends View {
        private final Chan c;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float shown = 0f;

        MeterView(Context ctx, Chan c) { super(ctx); this.c = c; }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            p.setColor(0xFF0B0F14);
            cv.drawRoundRect(0, 0, w, h, h / 2, h / 2, p);
            float v = Math.min(1.2f, c.peak);
            shown = v > shown ? v : shown * 0.82f;
            float fw = Math.min(1f, shown) * w;
            p.setColor(shown > 0.95f ? 0xFFE74C3C : shown > 0.7f ? 0xFFF39C12 : 0xFF2ECC71);
            if (fw > 0) cv.drawRoundRect(0, 0, fw, h, h / 2, h / 2, p);
        }
    }
}
