/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
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

import static app.morphe.extension.shared.settings.BaseSettings.DEBUG;
import static app.morphe.extension.youtube.patches.voiceovertranslation.TranscriptTranslator.TRANSLATION_SERVICE_MY_MEMORY;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.AppLanguage;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.VideoState;

@SuppressWarnings({"unused", "deprecation", "RedundantSuppression"})
public final class VoiceOverTranslationPatch {

    private static final long SEEK_JUMP_THRESHOLD_MS = 3_000;
    private static final long TTS_LOOKAHEAD_MS = 400;

    // Rough estimate of natural speech duration (~15 chars per second) used to
    // decide how much the TTS rate must be raised to fit the segment time slot.
    private static final long ESTIMATED_MS_PER_CHAR = 65;
    private static final float MIN_SPEECH_RATE = 1.0f;
    // Max rate increase between consecutive utterances. One delayed segment then
    // raises the pace gradually over a few sentences instead of jumping straight
    // from normal speed to maximum. Slowing back down is applied immediately.
    private static final float MAX_RATE_STEP_UP = 0.25f;
    private static volatile float lastSpeechRate = MIN_SPEECH_RATE;
    private static volatile long lastVideoTimeMs;

    private static volatile TextToSpeech tts;
    private static volatile boolean ttsReady;

    private static volatile List<TranscriptSegment> segments = new ArrayList<>();
    private static volatile int lastSpokenIndex = -1;
    private static volatile String currentVideoId = "";
    private static volatile boolean isLoading;
    private static volatile boolean sessionEnabled = Settings.VOT_ENABLED.get();

    private static volatile Runnable onStateChangeCallback;

    private static volatile AudioManager audioManager;
    // Kept as a field so abandonAudioFocus() uses the same listener instance as requestAudioFocus().
    private static final AudioManager.OnAudioFocusChangeListener focusChangeListener = focusChange -> { };
    private static volatile AudioFocusRequest focusRequest;
    private static volatile boolean isDucking;
    private static volatile boolean isTestSpeaking;

    private static final TtsEngine edgeTtsEngine = TtsEngine.INSTANCE;

    static final String TTS_ENGINE_SYSTEM = "system";

    private static final String TEST_SPEAK_PHRASE = "It's a morphing time";

    static {
        PlayerType.getOnChange().addObserver(playerType -> {
            if (!playerType.isMaximizedOrFullscreen()
                    && playerType != PlayerType.WATCH_WHILE_MINIMIZED
                    && playerType != PlayerType.WATCH_WHILE_PICTURE_IN_PICTURE
                    && playerType != PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED) {
                Logger.printDebug(() -> "Stopping TTS for player type: " + playerType);
                stopTts();
            }
            return kotlin.Unit.INSTANCE;
        });

        VideoState.getOnChange().addObserver(state -> {
            if (state == VideoState.PAUSED) {
                Logger.printDebug(() -> "Stopping TTS for video state: " + state);
                stopTts();
            }
            return kotlin.Unit.INSTANCE;
        });
    }

    /**
     * Injection point.
     */
    public static void newVideoLoaded(String videoId) {
        try {
            // Always reset so seek detection fires correctly on the first videoTimeChanged
            // and so the first segment at the new position is spoken even when the same
            // video is reopened at a different timestamp (e.g. chapter links, continue watching).
            lastVideoTimeMs = 0;
            lastSpokenIndex = -1;
            if (videoId.equals(currentVideoId)) return;
            currentVideoId = videoId;
            stopTts();
            segments = new ArrayList<>();

            if (!Settings.VOT_ENABLED.get()) return;
            if (PlayerType.getCurrent() == PlayerType.INLINE_MINIMAL) return;
            TtsPrefetcher.updateVideo(videoId, segments);
            loadTranscript(videoId);
        } catch (Exception ex) {
            logError(() -> "newVideoLoaded failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void videoTimeChanged(long timeMs) {
        if (!Settings.VOT_ENABLED.get() || !sessionEnabled) return; // Feature or session disabled.

        PlayerType currentPlayerType = PlayerType.getCurrent();
        if (!currentPlayerType.isMaximizedOrFullscreen()
                && currentPlayerType != PlayerType.WATCH_WHILE_MINIMIZED
                && currentPlayerType != PlayerType.WATCH_WHILE_PICTURE_IN_PICTURE) {
            Logger.printDebug(() -> "Ignoring TTS for player type: " + currentPlayerType);
            return;
        }
        VideoState state = VideoState.getCurrent();
        // Video state can be null until the overlay is activated the first time.
        if (state != null && state != VideoState.PLAYING) {
            Logger.printDebug(() -> "Ignoring TTS for video state: " + state);
            return; // paused, ended, or loading
        }

        TtsPrefetcher.updateTime(timeMs);

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
                    boolean busy = edgeTtsEngine.isSpeaking() || (tts != null && tts.isSpeaking());
                    if (!busy) {
                        lastSpokenIndex = i;
                        speak(seg, i);
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

    private static synchronized void loadTranscript(String videoId) {
        if (isLoading) return;
        isLoading = true;

        Utils.runOnBackgroundThread(() -> {
            try {
                // Later translation batches arrive asynchronously; swap the list in only
                // while the same video is still playing. Timings and size are identical
                // across updates, so lastSpokenIndex stays valid.
                List<TranscriptSegment> fetched = TranscriptFetcher.fetch(videoId,
                        updated -> {
                            if (videoId.equals(currentVideoId)) {
                                segments = updated;
                            }
                        },
                        () -> !videoId.equals(currentVideoId));
                if (videoId.equals(currentVideoId)) {
                    segments = fetched;
                    TtsPrefetcher.updateVideo(videoId, fetched);
                    Logger.printDebug(() -> "Loaded: " + fetched.size()
                            + " segments for :" + videoId);
                    notifyStateChanged();
                }
            } catch (Exception ex) {
                logError(() -> "Transcript fetch failed", ex);
            } finally {
                isLoading = false;
                // The video may have changed while this fetch was in flight - the isLoading
                // gate blocked that load, so restart it for the current video.
                String latest = currentVideoId;
                if (!latest.isEmpty() && !latest.equals(videoId) && Settings.VOT_ENABLED.get()) {
                    loadTranscript(latest);
                }
            }
        });
    }

    private static synchronized void ensureTts() {
        if (tts != null) return;
        tts = new TextToSpeech(Utils.getContext(), status -> {
            if (status != TextToSpeech.SUCCESS) {
                Logger.printDebug(() -> "TTS initialization failed");
                return;
            }
            updateTtsLanguage();
            // USAGE_ASSISTANCE_NAVIGATION_GUIDANCE plays TTS on a dedicated audio usage
            // so its volume is controlled independently of YouTube's media stream.
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            // Abandon duck when an utterance finishes naturally (not when flushed by the next segment).
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String utteranceId) { }
                @Override public void onDone(String utteranceId) {
                    if ("vot_test".equals(utteranceId)) abandonDuckAfterTest();
                    else abandonDuck();
                }
                @Override public void onError(String utteranceId) {
                    if ("vot_test".equals(utteranceId)) abandonDuckAfterTest();
                    else abandonDuck();
                }
            });
            ttsReady = true;
        });
    }

    private static void speak(TranscriptSegment seg, int index) {
        String lang = resolveTargetLang();
        final float volume = Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f;

        // Time left until the next segment starts, measured from the current playback
        // position so a late start (busy engine, synthesis latency) raises the rate.
        final long availableMs = seg.endMs() - Math.max(lastVideoTimeMs, seg.startMs());

        if (!Settings.VOT_USE_NATIVE_TTS.get()) {
            final String voice = VoiceCatalog.resolve(lang, Settings.VOT_TTS_VOICE_TYPE.get());

            if (voice != null) {
                byte[] cached = TtsCache.get(currentVideoId, index, voice, seg.text());
                if (cached != null) {
                    // Cached audio was synthesized at 1.0x - apply speed at playback time.
                    final float rate = smoothRate(calculateSpeechRate(seg.text(), availableMs));
                    requestDuck();
                    // markBusy before play so isSpeaking() is true for the full duration.
                    final long playbackId = edgeTtsEngine.markBusy();
                    edgeTtsEngine.play(cached, volume, rate, playbackId, VoiceOverTranslationPatch::abandonDuck);
                    return;
                }

                // Edge TTS synthesizes over the network before playback starts.
                final float rate = smoothRate(calculateSpeechRate(seg.text(), availableMs));
                requestDuck();
                // markBusy before the background thread so isSpeaking() returns true
                // during the network round-trip, preventing a concurrent segment from starting.
                final long playbackId = edgeTtsEngine.markBusy();
                Utils.runOnBackgroundThread(() -> {
                    try {
                        byte[] data = edgeTtsEngine.synthesize(seg.text(), voice, rate, playbackId);
                        if (data.length > 0) {
                            // Only cache 1.0x audio; rate-adjusted audio has the speed baked
                            // into SSML and cannot be reused for segments with different timing.
                            if (rate == 1.0f) {
                                TtsCache.put(currentVideoId, index, voice, seg.text(), data);
                            }
                            // Rate is already encoded in SSML; play at 1.0x.
                            edgeTtsEngine.play(data, volume, 1.0f, playbackId, VoiceOverTranslationPatch::abandonDuck);
                        } else {
                            edgeTtsEngine.clearBusy(playbackId);
                            abandonDuck();
                        }
                    } catch (Exception ex) {
                        logError(() -> "Synthesis failed", ex);
                        edgeTtsEngine.clearBusy(playbackId);
                        abandonDuck();
                    }
                });
                return;
            }
        }

        ensureTts();
        if (!ttsReady) {
            Logger.printDebug(() -> "Native TTS not ready, skipping segment");
            return;
        }
        final float rate = smoothRate(calculateSpeechRate(seg.text(), availableMs));
        requestDuck();
        tts.setSpeechRate(rate);
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
        tts.speak(seg.text(), TextToSpeech.QUEUE_FLUSH, params, "vot");
    }

    /**
     * Returns a speech rate multiplier that fits the estimated natural duration of
     * {@code text} into {@code availableMs}. Never slows below normal speed and is
     * capped by the user-configured max rate so fast videos stay intelligible.
     */
    private static float calculateSpeechRate(String text, long availableMs) {
        final float maxRate = Settings.VOT_MAX_SPEECH_RATE.get() / 10.0f;
        if (availableMs <= 0) return maxRate;
        final float rate = (float) (text.length() * ESTIMATED_MS_PER_CHAR) / availableMs;
        return Math.max(MIN_SPEECH_RATE, Math.min(maxRate, rate));
    }

    /**
     * Limits how much the rate may rise relative to the previous utterance, so a single
     * stall does not produce a jarring jump to maximum speed - the pace catches up
     * gradually instead. Decreases pass through unchanged.
     */
    private static float smoothRate(float targetRate) {
        final float rate = Math.min(targetRate, lastSpeechRate + MAX_RATE_STEP_UP);
        lastSpeechRate = rate;
        return rate;
    }

    /**
     * Called when the video position is programmatically seeked (e.g. SponsorBlock).
     * Stops any in-progress TTS immediately, regardless of how short the jump was,
     * so stale audio never plays over the new video position.
     */
    public static void onVideoSeeked() {
        stopTts();
        lastSpokenIndex = -1;
    }

    /**
     * Synthesizes and plays a short test phrase with the given Edge TTS voice.
     * If the engine is already speaking (e.g. a previous test), stops it instead.
     */
    static void testSpeak(String voiceId) {
        final float volume = Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f;
        if (TTS_ENGINE_SYSTEM.equals(voiceId)) {
            ensureTts();
            if (!ttsReady) return;
            if (isTestSpeaking) {
                isTestSpeaking = false;
                tts.stop();
                abandonDuck();
                return;
            }
            isTestSpeaking = true;
            requestDuck();
            Utils.runOnBackgroundThread(() -> {
                String phrase = translateTestPhrase();
                Bundle params = new Bundle();
                params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
                tts.setSpeechRate(1.0f);
                tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, params, "vot_test");
            });
            return;
        }
        if (isTestSpeaking) {
            isTestSpeaking = false;
            edgeTtsEngine.stop();
            abandonDuck();
            return;
        }
        // Stop any regular segment that may be playing; its onDone will call abandonDuck()
        // which is guarded by isTestSpeaking and will be a no-op.
        isTestSpeaking = true;
        edgeTtsEngine.stop();
        requestDuck();
        final long playbackId = edgeTtsEngine.markBusy();
        Utils.runOnBackgroundThread(() -> {
            try {
                String phrase = translateTestPhrase();
                byte[] data = edgeTtsEngine.synthesize(phrase, voiceId, 1.0f, playbackId);
                if (data.length > 0) {
                    edgeTtsEngine.play(data, volume, 1.0f, playbackId,
                            VoiceOverTranslationPatch::abandonDuckAfterTest);
                } else {
                    edgeTtsEngine.clearBusy(playbackId);
                    abandonDuckAfterTest();
                }
            } catch (Exception ex) {
                logError(() -> "Test speak failed", ex);
                edgeTtsEngine.clearBusy(playbackId);
                abandonDuckAfterTest();
            }
        });
    }

    private static String translateTestPhrase() {
        String targetLang = resolveTargetLang().split("-")[0];
        if ("en".equals(targetLang)) return TEST_SPEAK_PHRASE;
        try {
            List<TranscriptSegment> result = TranscriptTranslator.translate(
                    List.of(new TranscriptSegment(0, 5000, TEST_SPEAK_PHRASE)),
                    targetLang, null, null);
            return result.isEmpty() ? TEST_SPEAK_PHRASE : result.get(0).text();
        } catch (Exception ex) {
            return TEST_SPEAK_PHRASE;
        }
    }

    private static void stopTts() {
        isTestSpeaking = false;
        edgeTtsEngine.stop();
        TextToSpeech localTts = tts;
        if (localTts != null) localTts.stop();
        // Speech was interrupted (new video, seek, pause) - no backlog left to catch
        // up on, so the next utterance starts from normal speed again.
        lastSpeechRate = MIN_SPEECH_RATE;
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
     * No-op if ducking is not active or a test is still playing.
     */
    private static void abandonDuck() {
        if (isTestSpeaking) return;
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

    private static void abandonDuckAfterTest() {
        isTestSpeaking = false;
        abandonDuck();
    }

    private static void updateTtsLanguage() {
        TextToSpeech t = tts;
        if (t == null) return;
        Locale locale = Settings.VOT_CAPTION_LANGUAGE.isSetToDefault() // Default is app language.
                ? Locale.getDefault()
                : Locale.forLanguageTag(Settings.VOT_CAPTION_LANGUAGE.get());
        int result = t.setLanguage(locale);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.ENGLISH);
        }
    }

    private static AudioManager getAudioManager() {
        if (audioManager == null) {
            audioManager = (AudioManager) Utils.getContext().getSystemService(Context.AUDIO_SERVICE);
        }
        return audioManager;
    }

    public static final class MyMemoryServiceAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.VOT_TRANSLATION_SERVICE.get().equals(TRANSLATION_SERVICE_MY_MEMORY);
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.VOT_TRANSLATION_SERVICE);
        }
    }

    static String resolveTargetLang() {
        return Settings.VOT_CAPTION_LANGUAGE.isSetToDefault() // Default is app language.
                ? AppLanguage.DEFAULT.getLanguage()
                : Settings.VOT_CAPTION_LANGUAGE.get();
    }

    static void logError(Logger.LogMessage message, Exception ex) {
        if (DEBUG.get()) Logger.printException(message, ex);
        else Logger.printInfo(message, ex);
    }
}
