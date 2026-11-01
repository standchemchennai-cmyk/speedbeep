package com.utk.speedbeep;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/** Local, selectable overspeed warnings. No audio downloads or network API are required. */
public final class AlarmSoundPlayer {
    public static final String KEY_SOUND = "alarm_sound";
    public static final String BEEP = "beep";
    public static final String SIREN = "siren";
    public static final String VOICE = "voice";

    private static final int SAMPLE_RATE = 22050;
    private static final int SIREN_MS = 1200;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ToneGenerator tones;
    private TextToSpeech speech;
    private AudioTrack activeSiren;
    private boolean speechReady;
    private boolean closed;
    private String pendingVoicePreview;

    public AlarmSoundPlayer(Context context) {
        this.context = context.getApplicationContext();
        try {
            tones = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
        } catch (RuntimeException ignored) {
            // A missing audio output must not crash location monitoring.
        }
        if (VOICE.equals(getSelectedMode(context))) prepareSpeech();
    }

    public static String getSelectedMode(Context context) {
        String mode = context.getSharedPreferences(SpeedMonitorService.SETTINGS, Context.MODE_PRIVATE)
                .getString(KEY_SOUND, BEEP);
        return SIREN.equals(mode) || VOICE.equals(mode) ? mode : BEEP;
    }

    public static String[] labels() {
        return new String[]{"Beep beep", "Siren", "Voice: Speed limit XX"};
    }

    public static String[] modes() {
        return new String[]{BEEP, SIREN, VOICE};
    }

    public static int selectedIndex(String mode) {
        if (SIREN.equals(mode)) return 1;
        if (VOICE.equals(mode)) return 2;
        return 0;
    }

    /** Uses the actual configured limit, not the driver's current GPS speed. */
    public void play(String mode, double speedLimitKph, boolean userDefault) {
        playWithPrefix(mode, speedLimitKph,
                userDefault ? "User set limit " : "Speed limit ");
    }

    /** Announce the separate personal ceiling in voice mode. */
    public void playUniversal(String mode, double speedLimitKph) {
        playWithPrefix(mode, speedLimitKph, "Universal speed limit ");
    }

    private void playWithPrefix(String mode, double speedLimitKph, String prefix) {
        if (closed) return;
        stopSiren();
        if (VOICE.equals(mode)) {
            String words = prefix + Math.round(speedLimitKph);
            if (speechReady && speech != null) {
                speak(words);
            } else {
                prepareSpeech();
                doubleBeep(); // Never silently lose an alarm while TTS initializes.
            }
        } else if (SIREN.equals(mode)) {
            playSiren();
        } else {
            doubleBeep();
        }
    }

    /** Preview is only offered in the app's settings dialog; use it while parked. */
    public void preview(String mode) {
        if (closed) return;
        if (VOICE.equals(mode) && !speechReady) {
            pendingVoicePreview = "Speed limit 60";
            prepareSpeech();
            return;
        }
        pendingVoicePreview = null;
        play(mode, 60, false);
    }

    private void prepareSpeech() {
        if (speech != null || closed) return;
        try {
            speech = new TextToSpeech(context, status -> handler.post(() -> {
                if (closed) return;
                if (status == TextToSpeech.SUCCESS && speech != null) {
                    int language = speech.setLanguage(Locale.US);
                    if (language != TextToSpeech.LANG_MISSING_DATA
                            && language != TextToSpeech.LANG_NOT_SUPPORTED) {
                        speech.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                        speechReady = true;
                        if (pendingVoicePreview != null) speak(pendingVoicePreview);
                        pendingVoicePreview = null;
                        return;
                    }
                }
                // An unavailable or uninstalled system TTS voice falls back to a beep.
                if (pendingVoicePreview != null) doubleBeep();
                pendingVoicePreview = null;
            }));
        } catch (RuntimeException ignored) {
            if (pendingVoicePreview != null) doubleBeep();
            pendingVoicePreview = null;
        }
    }

    private void speak(String words) {
        if (speech == null || !speechReady) return;
        speech.speak(words, TextToSpeech.QUEUE_FLUSH, null, "speedbeep-limit");
    }

    private void doubleBeep() {
        if (tones == null || closed) return;
        if (speech != null && speechReady) speech.stop();
        tones.startTone(ToneGenerator.TONE_PROP_BEEP, 300);
        handler.postDelayed(() -> {
            if (!closed && tones != null) tones.startTone(ToneGenerator.TONE_PROP_BEEP, 220);
        }, 420L);
    }

    private void playSiren() {
        if (speech != null && speechReady) speech.stop();
        short[] pcm = new short[SAMPLE_RATE * SIREN_MS / 1000];
        double phase = 0;
        double duration = pcm.length / (double) SAMPLE_RATE;
        for (int i = 0; i < pcm.length; i++) {
            double t = i / (double) SAMPLE_RATE;
            double fraction = (t % 0.6) / 0.6;
            double triangle = 1.0 - Math.abs(2.0 * fraction - 1.0);
            double hz = 540.0 + 670.0 * triangle;
            phase += 2.0 * Math.PI * hz / SAMPLE_RATE;
            double edge = Math.min(1.0, Math.min(t / 0.025, (duration - t) / 0.04));
            pcm[i] = (short) (Math.sin(phase) * Math.max(0.0, edge) * 14500.0);
        }

        try {
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length * 2)
                    .build();
            activeSiren = track;
            if (track.getState() != AudioTrack.STATE_INITIALIZED
                    || track.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING) != pcm.length) {
                throw new IllegalStateException("Siren audio output unavailable");
            }
            track.play();
            handler.postDelayed(() -> {
                if (activeSiren == track) stopSiren();
            }, SIREN_MS + 250L);
        } catch (RuntimeException ex) {
            stopSiren();
            doubleBeep();
        }
    }

    private void stopSiren() {
        AudioTrack track = activeSiren;
        activeSiren = null;
        if (track != null) {
            try { track.stop(); } catch (IllegalStateException ignored) { }
            track.release();
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        pendingVoicePreview = null;
        handler.removeCallbacksAndMessages(null);
        stopSiren();
        if (speech != null) {
            speech.stop();
            speech.shutdown();
            speech = null;
        }
        if (tones != null) {
            tones.release();
            tones = null;
        }
    }
}
