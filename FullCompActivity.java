package com.tvkouame.studio;

import android.app.Activity;
import android.content.Intent;
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

import java.util.Locale;

/**
 * Composition instrument général : tous les instruments ensemble, SANS la voix de la chanson.
 * On écoute l'ensemble, on corrige un instrument si besoin, puis on envoie la composition au mixage
 * (c'est là que le Studio y ajoute la voix).
 */
public class FullCompActivity extends Activity {

    private static final int[] TCOL = {0xFFE67E22, 0xFF9B59B6, 0xFF2E86DE, 0xFF2ECC71};
    private static final int OFF = 0xFF2C3A4A;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private BeatModel m;
    private PcmPlayer player;
    private boolean busy = false, playing = false, needReload = false;
    private Button playBtn, doneBtn;
    private TextView status, timeText, stateText;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        m = StudioStore.load(this, getIntent().getStringExtra("id"));
        if (m == null) { finish(); return; }
        buildUi();
        refreshState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (needReload && m != null) {
            needReload = false;
            BeatModel fresh = StudioStore.load(this, m.id);
            if (fresh != null) { m = fresh; buildUi(); refreshState(); }
        }
    }

    /** On sauvegarde dès la pause : l'écran suivant (ou le Studio) recharge le projet juste après. */
    @Override
    protected void onPause() {
        super.onPause();
        stopPlay();
        if (m != null) StudioStore.save(this, m);
    }

    // ------------------------------------------------------------------
    private void buildUi() {
        int pad = Ui.dp(this, 16);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad * 2);
        sv.addView(root);

        TextView back = Ui.text(this, "‹ Retour au Studio", 15, Ui.ACCENT, true);
        back.setPadding(0, 0, 0, Ui.dp(this, 10));
        back.setOnClickListener(v -> finish());
        root.addView(back);
        root.addView(Ui.text(this, "Composition instrument général", 22, Ui.TEXT, true));
        TextView sub = Ui.text(this, "Ici tu entends tous les instruments ensemble, sans la voix de la chanson. "
                + "Corrige ce qui ne va pas, puis envoie la composition au mixage : le Studio y ajoutera ta voix.", 13, Ui.MUTED, false);
        sub.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 14));
        root.addView(sub);

        // --- Écoute
        LinearLayout lc = Ui.card(this);
        playBtn = Ui.button(this, "▶ Écouter la composition", Ui.GREEN);
        playBtn.setTextColor(0xFF000000);
        playBtn.setOnClickListener(v -> { if (playing) stopPlay(); else startPlay(); });
        lc.addView(playBtn);
        timeText = Ui.text(this, "0:00", 26, Ui.TEXT, true);
        timeText.setGravity(Gravity.CENTER);
        timeText.setPadding(0, Ui.dp(this, 8), 0, 0);
        lc.addView(timeText);
        status = Ui.text(this, "", 12, Ui.MUTED, false);
        status.setGravity(Gravity.CENTER);
        lc.addView(status);
        root.addView(lc);

        // --- Les instruments : niveau + correction
        LinearLayout ic = Ui.card(this);
        ic.addView(Ui.text(this, "Instruments", 17, Ui.TEXT, true));
        TextView h = Ui.text(this, "Règle le niveau de chacun, ou touche « Corriger » pour modifier ses notes.", 12, Ui.MUTED, false);
        h.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        ic.addView(h);
        for (int t = 0; t < BeatModel.NT; t++) ic.addView(instrumentRow(t));
        root.addView(ic);

        // --- Envoi au mixage
        LinearLayout dc = Ui.card(this);
        dc.addView(Ui.text(this, "Envoyer au mixage", 17, Ui.TEXT, true));
        stateText = Ui.text(this, "", 13, Ui.TEXT, false);
        stateText.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 8));
        dc.addView(stateText);
        doneBtn = Ui.button(this, "✔ Terminer et envoyer au mixage", Ui.ACCENT);
        doneBtn.setTextColor(0xFF000000);
        doneBtn.setOnClickListener(v -> finishAndSend());
        dc.addView(doneBtn);
        TextView hh = Ui.text(this, "La composition est enregistrée dans le projet. Ensuite, dans le Studio : tu enregistres la voix, puis tu mixes l'ensemble.", 12, Ui.MUTED, false);
        hh.setPadding(0, Ui.dp(this, 8), 0, 0);
        dc.addView(hh);
        root.addView(dc);

        setContentView(sv);
    }

    private View instrumentRow(final int t) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(this, 10), 0, 0);

        LinearLayout r1 = Ui.row(this);
        View dot = new View(this);
        Ui.setDot(dot, TCOL[t]);
        r1.addView(dot, new LinearLayout.LayoutParams(Ui.dp(this, 14), Ui.dp(this, 14)));
        final TextView name = Ui.text(this, "  " + BeatModel.TRACKS[t] + " : " + Math.round(m.vol[t] * 100) + " %", 14, Ui.TEXT, true);
        r1.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        Button fix = Ui.button(this, "Corriger", TCOL[t]);
        fix.setTextSize(13);
        fix.setMinHeight(Ui.dp(this, 40));
        fix.setMinimumHeight(Ui.dp(this, 40));
        fix.setOnClickListener(v -> {
            stopPlay();
            StudioStore.save(this, m);
            needReload = true;
            Intent it = new Intent(this, ComposeActivity.class);
            it.putExtra("id", m.id);
            it.putExtra("track", t);
            it.putExtra("bar", 0);
            startActivity(it);
        });
        r1.addView(fix);
        box.addView(r1);

        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(Math.round(m.vol[t] * 100));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                m.vol[t] = p / 100f;
                name.setText("  " + BeatModel.TRACKS[t] + " : " + p + " %");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                StudioStore.save(FullCompActivity.this, m);
                refreshState();
                if (playing) { stopPlay(); startPlay(); }      // on réentend avec le nouveau niveau
            }
        });
        box.addView(sb);
        return box;
    }

    private boolean ready() {
        java.io.File f = new java.io.File(StudioStore.dirOf(this, m.id), "composition.wav");
        return m.composed && f.exists() && StudioStore.sig(m) == m.compSig;
    }

    private void refreshState() {
        if (stateText == null) return;
        if (ready()) {
            int s = m.compSamples / Synth.RATE;
            stateText.setText("✔ Composition déjà envoyée au mixage (" + s / 60 + ":" + String.format(Locale.US, "%02d", s % 60)
                    + "). Si tu corriges quelque chose, renvoie-la.");
        } else if (m.composed) {
            stateText.setText("⚠ La composition a changé depuis le dernier envoi : renvoie-la au mixage.");
        } else {
            stateText.setText("Quand tout te convient, envoie la composition au mixage.");
        }
    }

    // ------------------------------------------------------------------
    // Écoute (tous les instruments, jamais la voix)
    // ------------------------------------------------------------------
    private void startPlay() {
        if (busy || playing) return;
        busy = true;
        status.setText("Préparation du son…");
        final BeatModel mm = m;
        new Thread(() -> {
            short[] s = null;
            String err = null;
            try {
                s = BeatRenderer.toShorts(BeatRenderer.renderBeat(mm));
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
            }
            final short[] fs = s;
            final String ferr = err;
            ui.post(() -> {
                busy = false;
                if (isFinishing()) return;
                if (fs == null) { status.setText("Erreur : " + ferr); return; }
                final PcmPlayer p = new PcmPlayer(fs, fs.length, true);
                if (!p.start()) { status.setText("Lecture impossible"); return; }
                player = p;
                playing = true;
                playBtn.setText("■ Arrêter");
                status.setText("La composition tourne en boucle (sans la voix).");
                ui.removeCallbacks(tick);
                ui.post(tick);
            });
        }, "full-render").start();
    }

    private void stopPlay() {
        playing = false;
        PcmPlayer p = player;
        player = null;
        if (p != null) p.stop();
        if (playBtn != null) playBtn.setText("▶ Écouter la composition");
        if (timeText != null) timeText.setText("0:00");
        if (status != null && !busy) status.setText("");
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            PcmPlayer p = player;
            if (!playing || p == null) return;
            long pos = p.positionFrames();
            long sec = pos / Synth.RATE;
            int per = Math.max(1, BeatRenderer.samplesPerBar(m));
            int bar = (int) Math.min(m.bars - 1, pos / per);
            timeText.setText(String.format(Locale.US, "%d:%02d  ·  mesure %d / %d", sec / 60, sec % 60, bar + 1, m.bars));
            ui.postDelayed(this, 100);
        }
    };

    // ------------------------------------------------------------------
    // Terminer : enregistre composition.wav et revient au Studio
    // ------------------------------------------------------------------
    private void finishAndSend() {
        if (busy) return;
        stopPlay();
        StudioStore.save(this, m);
        busy = true;
        doneBtn.setEnabled(false);
        status.setText("Envoi de la composition au mixage…");
        final BeatModel mm = m;
        new Thread(() -> {
            String err = null;
            int len = 0;
            try {
                len = BeatRenderer.writeComposition(mm, StudioStore.dirOf(this, mm.id));
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
            }
            final String ferr = err;
            final int flen = len;
            ui.post(() -> {
                busy = false;
                doneBtn.setEnabled(true);
                status.setText("");
                if (ferr != null) { Toast.makeText(this, "Impossible : " + ferr, Toast.LENGTH_LONG).show(); return; }
                mm.composed = true;
                mm.compSamples = flen;
                mm.compSig = StudioStore.sig(mm);
                StudioStore.save(this, mm);
                setResult(RESULT_OK);
                Toast.makeText(this, "Composition envoyée au mixage. Enregistre la voix dans le Studio.", Toast.LENGTH_LONG).show();
                finish();
            });
        }, "full-send").start();
    }
}
