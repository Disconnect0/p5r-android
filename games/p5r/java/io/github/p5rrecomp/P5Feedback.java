package io.github.p5rrecomp;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Tactile haptics (Vibrator API 29+) and synthesized Persona 5-style UI sound effects
 * (zero external audio assets, instant AudioTrack MODE_STATIC playback).
 */
final class P5Feedback {
    private static final int SAMPLE_RATE = 22050;
    private static final int SFX_TICK = 0;
    private static final int SFX_TAB = 1;
    private static final int SFX_CONFIRM = 2;
    private static final int SFX_CANCEL = 3;
    private static final int SFX_COUNT = 4;

    private static boolean initialized = false;
    private static boolean sfxEnabled = true;
    private static boolean hapticsEnabled = true;
    private static final AudioTrack[] tracks = new AudioTrack[SFX_COUNT];
    private static Vibrator cachedVibrator;

    static void init(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (cachedVibrator == null) {
            cachedVibrator = resolveVibrator(app);
        }
        if (initialized) return;
        initialized = true;
        try {
            tracks[SFX_TICK] = buildStaticTrack(synthTick());
            tracks[SFX_TAB] = buildStaticTrack(synthTab());
            tracks[SFX_CONFIRM] = buildStaticTrack(synthConfirm());
            tracks[SFX_CANCEL] = buildStaticTrack(synthCancel());
        } catch (Throwable ignored) {
        }
    }

    static void setSfxEnabled(boolean enabled) {
        sfxEnabled = enabled;
    }

    static boolean isSfxEnabled() {
        return sfxEnabled;
    }

    static void setHapticsEnabled(boolean enabled) {
        hapticsEnabled = enabled;
    }

    static boolean isHapticsEnabled() {
        return hapticsEnabled;
    }

    static void release() {
        for (int i = 0; i < SFX_COUNT; i++) {
            if (tracks[i] != null) {
                try {
                    tracks[i].release();
                } catch (Throwable ignored) {
                }
                tracks[i] = null;
            }
        }
        initialized = false;
    }

    static void tick(Context ctx) {
        init(ctx);
        vibrate(24, 190);
        playSfx(SFX_TICK);
    }

    static void click(Context ctx) {
        init(ctx);
        vibrate(30, 225);
        playSfx(SFX_TICK);
    }

    static void tab(Context ctx) {
        init(ctx);
        vibrate(28, 215);
        playSfx(SFX_TAB);
    }

    static void confirm(Context ctx) {
        init(ctx);
        vibrate(45, 255);
        playSfx(SFX_CONFIRM);
    }

    static void cancel(Context ctx) {
        init(ctx);
        vibrate(22, 180);
        playSfx(SFX_CANCEL);
    }

    private static Vibrator resolveVibrator(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) return vm.getDefaultVibrator();
            }
            @SuppressWarnings("deprecation")
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            return v;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void vibrate(long ms, int amplitude) {
        if (!hapticsEnabled) return;
        Vibrator v = cachedVibrator;
        if (v == null) return;
        try {
            if (!v.hasVibrator()) return;
            int amp = v.hasAmplitudeControl() ? amplitude : VibrationEffect.DEFAULT_AMPLITUDE;
            VibrationEffect eff = VibrationEffect.createOneShot(ms, amp);
            if (Build.VERSION.SDK_INT >= 33) {
                // USAGE_MEDIA ensures HyperOS/MIUI does not suppress game UI haptics when system Touch Feedback is off
                VibrationAttributes attrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA);
                v.vibrate(eff, attrs);
            } else {
                @SuppressWarnings("deprecation")
                AudioAttributes aa = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                @SuppressWarnings("deprecation")
                boolean unused = true;
                v.vibrate(eff, aa);
            }
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void playSfx(int id) {
        if (!sfxEnabled || id < 0 || id >= SFX_COUNT) return;
        AudioTrack t = tracks[id];
        if (t == null || t.getState() != AudioTrack.STATE_INITIALIZED) return;
        try {
            if (t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                t.stop();
            }
            t.reloadStaticData();
            t.play();
        } catch (Throwable ignored) {
        }
    }

    private static AudioTrack buildStaticTrack(short[] pcm) {
        int byteLen = pcm.length * 2;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        AudioTrack track = new AudioTrack(attrs, fmt, byteLen, AudioTrack.MODE_STATIC,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE);
        track.write(pcm, 0, pcm.length);
        return track;
    }

    // Crisp percussive paper-click for toggles and buttons (~38ms)
    private static short[] synthTick() {
        int n = (int) (SAMPLE_RATE * 0.038f);
        short[] out = new short[n];
        long seed = 0x51A37B1DL;
        double phase = 0.0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) SAMPLE_RATE;
            float env = (float) Math.exp(-t * 95.0);
            float attack = Math.min(1f, i / 28f);
            float freq = 1380f - 760f * Math.min(1f, t / 0.025f);
            phase += 2.0 * Math.PI * freq / SAMPLE_RATE;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float noise = ((seed >> 33) / (float) Integer.MAX_VALUE) * (float) Math.exp(-t * 280.0);
            float sample = (0.68f * (float) Math.sin(phase) + 0.35f * noise) * env * attack;
            out[i] = clampSample(sample * 0.32f);
        }
        return out;
    }

    // Snappy P5 cut-paper blade swish + harmonic fifth tap for tab switches (~78ms)
    private static short[] synthTab() {
        int n = (int) (SAMPLE_RATE * 0.078f);
        short[] out = new short[n];
        long seed = 0x2F91C4A7L;
        double p1 = 0.0, p2 = 0.0;
        float prevNoise = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) SAMPLE_RATE;
            float attack = Math.min(1f, i / 36f);
            float toneEnv = (float) Math.exp(-t * 50.0);
            float swishEnv = (float) Math.exp(-t * 82.0);
            float f1 = 940f + 120f * Math.min(1f, t / 0.03f);
            float f2 = f1 * 1.5f;
            p1 += 2.0 * Math.PI * f1 / SAMPLE_RATE;
            p2 += 2.0 * Math.PI * f2 / SAMPLE_RATE;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float rawNoise = ((seed >> 33) / (float) Integer.MAX_VALUE);
            // High-pass differentiated noise for crisp paper/blade air
            float hpNoise = (rawNoise - prevNoise) * 0.65f;
            prevNoise = rawNoise;
            float tone = (0.55f * (float) Math.sin(p1) + 0.28f * (float) Math.sin(p2)) * toneEnv;
            float sample = (tone + 0.45f * hpNoise * swishEnv) * attack;
            out[i] = clampSample(sample * 0.34f);
        }
        return out;
    }

    // Bold P5 confirm slash chord for PLAY launch (~195ms)
    private static short[] synthConfirm() {
        int n = (int) (SAMPLE_RATE * 0.195f);
        short[] out = new short[n];
        long seed = 0x7A4B91E3L;
        float prevNoise = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) SAMPLE_RATE;
            float attack = Math.min(1f, i / 70f);
            float chordEnv = (float) Math.exp(-t * 14.5);
            float slashEnv = (float) Math.exp(-Math.pow((t - 0.022f) / 0.020f, 2.0));
            double w = 2.0 * Math.PI * t;
            float chord = (float) (
                    0.42 * Math.sin(w * 440.0)
                            + 0.32 * Math.sin(w * 659.25)
                            + 0.24 * Math.sin(w * 880.0)
                            + 0.14 * Math.sin(w * 1318.5));
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            float rawNoise = ((seed >> 33) / (float) Integer.MAX_VALUE);
            float hpNoise = (rawNoise - prevNoise) * 0.55f;
            prevNoise = rawNoise;
            float sample = (chord * chordEnv + 0.40f * hpNoise * slashEnv) * attack;
            out[i] = clampSample(sample * 0.36f);
        }
        return out;
    }

    // Subtle downward pop for cancel / dismiss (~48ms)
    private static short[] synthCancel() {
        int n = (int) (SAMPLE_RATE * 0.048f);
        short[] out = new short[n];
        double phase = 0.0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) SAMPLE_RATE;
            float env = (float) Math.exp(-t * 72.0);
            float attack = Math.min(1f, i / 32f);
            float freq = 540f - 240f * Math.min(1f, t / 0.04f);
            phase += 2.0 * Math.PI * freq / SAMPLE_RATE;
            float sample = (float) Math.sin(phase) * env * attack;
            out[i] = clampSample(sample * 0.28f);
        }
        return out;
    }

    private static short clampSample(float v) {
        int s = Math.round(v * 32767f);
        if (s > 32767) return 32767;
        if (s < -32768) return -32768;
        return (short) s;
    }
}
