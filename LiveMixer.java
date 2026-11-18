package com.tvkouame.studio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;

import java.util.concurrent.CopyOnWriteArrayList;

/** Petit mélangeur à faible latence : voix jouées au doigt + piste d'accompagnement avec horloge précise. */
public class LiveMixer {

    public static class Voice {
        final float[] d;
        final float gain;
        int pos = 0;
        float env = 1f;
        volatile boolean released = false;
        Voice(float[] d, float gain) { this.d = d; this.gain = gain; }
    }

    private static final int CHUNK = 256;

    private final CopyOnWriteArrayList<Voice> voices = new CopyOnWriteArrayList<>();
    private volatile float[] back;
    private volatile boolean backOn = false, backLoop = false, backStarted = false, backFresh = false;
    private volatile Runnable onBackEnd;
    private int backPos = 0;
    private volatile long backStartClock = 0;
    private long clock = 0;
    private AudioTrack track;
    private Thread th;
    private volatile boolean run = false;

    public synchronized boolean start() {
        if (run) return true;
        try {
            int min = AudioTrack.getMinBufferSize(Synth.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack.Builder b = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(Synth.RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, CHUNK * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            track = b.build();
            track.play();
        } catch (Exception e) {
            track = null;
            return false;
        }
        clock = 0;
        run = true;
        th = new Thread(this::loop, "live-mixer");
        th.start();
        return true;
    }

    public synchronized void stop() {
        run = false;
        backOn = false;
        voices.clear();
        try {
            if (th != null) th.join(300);
        } catch (InterruptedException ignored) {}
        try {
            if (track != null) { track.stop(); track.release(); }
        } catch (Exception ignored) {}
        track = null;
        th = null;
    }

    public Voice play(float[] d, float gain) {
        Voice v = new Voice(d, gain);
        voices.add(v);
        return v;
    }

    public void release(Voice v) { if (v != null) v.released = true; }

    /** Lance l'accompagnement depuis son début ; onEnd est appelé (thread audio) à la fin s'il ne boucle pas. */
    public void startBacking(float[] data, boolean loop, Runnable onEnd) {
        backOn = false;
        backStarted = false;
        back = data;
        backLoop = loop;
        onBackEnd = onEnd;
        backPos = 0;
        backFresh = true;
        backOn = true;
    }

    public void stopBacking() { backOn = false; backStarted = false; }

    public boolean isBacking() { return backOn; }

    /** Position (en samples) dans l'accompagnement, telle qu'on l'entend à cet instant ; -1 si pas démarré. */
    public long relFrames() {
        try {
            if (!backStarted || track == null) return -1;
            long head = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            return head - backStartClock;
        } catch (Exception e) {
            return -1;
        }
    }

    private void loop() {
        float[] mix = new float[CHUNK];
        short[] out = new short[CHUNK];
        while (run) {
            java.util.Arrays.fill(mix, 0f);
            boolean ended = false;
            float[] b = back;
            if (backOn && b != null) {
                if (backFresh) { backFresh = false; backPos = 0; backStartClock = clock; backStarted = true; }
                for (int i = 0; i < CHUNK; i++) {
                    if (backPos >= b.length) {
                        if (backLoop) backPos = 0;
                        else { backOn = false; ended = true; break; }
                    }
                    mix[i] += b[backPos++];
                }
            }
            for (Voice v : voices) {
                for (int i = 0; i < CHUNK; i++) {
                    if (v.pos >= v.d.length) break;
                    if (v.released) {
                        v.env -= 1f / 1300f;
                        if (v.env <= 0f) { v.pos = v.d.length; break; }
                    }
                    mix[i] += v.d[v.pos++] * v.gain * v.env;
                }
                if (v.pos >= v.d.length) voices.remove(v);
            }
            for (int i = 0; i < CHUNK; i++) {
                float x = mix[i] * 0.8f;
                if (x > 1f) x = 1f; else if (x < -1f) x = -1f;
                out[i] = (short) (x * 32767f);
            }
            AudioTrack t = track;
            if (t == null) break;
            int w = t.write(out, 0, CHUNK);
            if (w < 0) break;
            clock += CHUNK;
            if (ended) {
                Runnable r = onBackEnd;
                if (r != null) r.run();
            }
        }
    }
}
