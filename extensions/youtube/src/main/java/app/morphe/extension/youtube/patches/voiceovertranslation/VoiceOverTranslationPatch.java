/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class VoiceOverTranslationPatch {

    private static volatile TextToSpeech tts;
    private static volatile boolean ttsReady = false;

    private static volatile List<TranscriptSegment> segments = new ArrayList<>();
    private static volatile int lastSpokenIndex = -1;
    private static volatile String currentVideoId = "";
    private static volatile boolean isLoading = false;

    private static volatile boolean sessionEnabled = true;

    private static volatile Runnable onStateChangeCallback = null;

    private static volatile long lastVideoTimeMs = 0;
    private static final long SEEK_JUMP_THRESHOLD_MS = 3_000;
    private static final long TTS_LOOKAHEAD_MS = 400;

    private static volatile AudioManager audioManager;
    // Kept as a field so abandonAudioFocus() uses the same listener instance as requestAudioFocus().
    private static final AudioManager.OnAudioFocusChangeListener focusChangeListener = focusChange -> { };
    private static volatile AudioFocusRequest focusRequest;
    private static volatile boolean isDucking = false;

    /**
     * Injection point.
     */
    public static void newVideoLoaded(String videoId) {
        if (videoId.equals(currentVideoId)) return;
        currentVideoId = videoId;
        lastVideoTimeMs = 0;
        sessionEnabled = true;
        stopTts();
        segments = new ArrayList<>();
        lastSpokenIndex = -1;

        if (!Settings.VOT_ENABLED.get()) return;
        loadTranscript(videoId);
    }

    /**
     * Injection point.
     */
    public static void videoTimeChanged(long timeMs) {
        if (!Settings.VOT_ENABLED.get() || !sessionEnabled) return;

        List<TranscriptSegment> current = segments;
        if (current.isEmpty()) return;

        if (lastVideoTimeMs > 0 && Math.abs(timeMs - lastVideoTimeMs) > SEEK_JUMP_THRESHOLD_MS) {
            stopTts();
            lastSpokenIndex = -1;
        }
        lastVideoTimeMs = timeMs;

        long effectiveTimeMs = timeMs + TTS_LOOKAHEAD_MS;
        for (int i = 0; i < current.size(); i++) {
            TranscriptSegment seg = current.get(i);
            if (effectiveTimeMs >= seg.startMs() && timeMs < seg.endMs()) {
                if (i != lastSpokenIndex) {
                    TextToSpeech localTts = tts;
                    if (localTts == null || !localTts.isSpeaking()) {
                        lastSpokenIndex = i;
                        speak(seg.text());
                    }
                }
                return;
            }
        }
    }

    public static boolean isTranslationActive() {
        return Settings.VOT_ENABLED.get() && sessionEnabled && !segments.isEmpty();
    }

    public static boolean isSessionEnabled() {
        return sessionEnabled;
    }

    public static synchronized void toggleTranslation() {
        sessionEnabled = !sessionEnabled;
        if (!sessionEnabled) {
            stopTts();
            lastSpokenIndex = -1;
        }
        notifyStateChanged();
    }

    public static synchronized void reloadTranscript() {
        if (currentVideoId.isEmpty()) return;
        stopTts();
        segments = new ArrayList<>();
        lastSpokenIndex = -1;
        updateTtsLanguage();
        if (!isLoading) {
            loadTranscript(currentVideoId);
        }
    }

    public static void setOnTranslationStateChangeCallback(Runnable callback) {
        onStateChangeCallback = callback;
    }

    private static void notifyStateChanged() {
        Runnable callback = onStateChangeCallback;
        if (callback != null) Utils.runOnMainThread(callback);
    }

    private static void loadTranscript(String videoId) {
        if (isLoading) return;
        isLoading = true;

        Utils.runOnBackgroundThread(() -> {
            try {
                List<TranscriptSegment> fetched = TranscriptFetcher.fetch(videoId);
                if (videoId.equals(currentVideoId)) {
                    segments = fetched;
                    Logger.printInfo(() -> "VoiceOverTranslation: loaded " + fetched.size()
                            + " segments for " + videoId);
                    notifyStateChanged();
                }
            } catch (Exception ex) {
                Logger.printException(() -> "VoiceOverTranslation: transcript fetch failed", ex);
            } finally {
                isLoading = false;
            }
        });
    }

    private static synchronized void ensureTts() {
        if (tts != null) return;
        tts = new TextToSpeech(Utils.getContext(), status -> {
            if (status != TextToSpeech.SUCCESS) {
                Logger.printDebug(() -> "VoiceOverTranslation: TTS initialization failed");
                return;
            }
            updateTtsLanguage();
            // USAGE_ASSISTANCE_NAVIGATION_GUIDANCE plays TTS on a dedicated audio usage
            // so its volume is controlled independently from YouTube's media stream.
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            // Abandon duck when an utterance finishes naturally (not when flushed by the next segment).
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }
                @Override public void onDone(String utteranceId) { abandonDuck(); }
                @Override public void onError(String utteranceId) { abandonDuck(); }
            });
            ttsReady = true;
        });
    }

    private static void speak(String text) {
        ensureTts();
        if (!ttsReady) return;
        requestDuck();
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,
                Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "vot");
    }

    private static void stopTts() {
        TextToSpeech localTts = tts;
        if (localTts != null) localTts.stop();
        abandonDuck();
    }

    /**
     * Requests transient audio focus with ducking so ExoPlayer (YouTube) receives
     * AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK and reduces its media volume while TTS speaks.
     * No-op if ducking is already active.
     */
    private static void requestDuck() {
        if (isDucking) return;
        isDucking = true;
        AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build();
        focusRequest = req;
        getAudioManager().requestAudioFocus(req);
    }

    /**
     * Releases the transient audio focus so ExoPlayer restores its media volume.
     * No-op if ducking is not active.
     */
    private static void abandonDuck() {
        if (!isDucking) return;
        isDucking = false;
        AudioManager am = audioManager;
        if (am == null) return;
        AudioFocusRequest req = focusRequest;
        if (req != null) {
            am.abandonAudioFocusRequest(req);
            focusRequest = null;
        }
    }

    private static void updateTtsLanguage() {
        TextToSpeech t = tts;
        if (t == null) return;
        Locale locale = Locale.forLanguageTag(Settings.VOT_CAPTION_LANGUAGE.get());
        int result = t.setLanguage(locale);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.getDefault());
        }
    }

    private static AudioManager getAudioManager() {
        if (audioManager == null) {
            audioManager = (AudioManager) Utils.getContext().getSystemService(Context.AUDIO_SERVICE);
        }
        return audioManager;
    }
}
