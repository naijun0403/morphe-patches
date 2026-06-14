/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import static app.morphe.extension.shared.settings.BaseSettings.DEBUG;
import static app.morphe.extension.youtube.patches.voiceovertranslation.TranscriptTranslator.TRANSLATION_SERVICE_MY_MEMORY;

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
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.AppLanguage;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.VideoState;

/**
 * Patch that provides voice-over translation for YouTube videos.
 *
 * <p>State management is performed on the main thread to avoid complex synchronization.
 */
@SuppressWarnings({"unused", "deprecation", "RedundantSuppression"})
public class VoiceOverTranslationPatch {

    public static class MyMemoryServiceAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.VOT_TRANSLATION_SERVICE.get().equals(TRANSLATION_SERVICE_MY_MEMORY);
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.VOT_TRANSLATION_SERVICE);
        }
    }

    private static final long SEEK_JUMP_THRESHOLD_MS = 2_900;
    private static final long TTS_LOOKAHEAD_MS = 400;

    // Minimum time into a segment to justify seeking within the audio instead of
    // playing from the start. Prevents tiny pops on small adjustments.
    private static final long SEEK_INTO_THRESHOLD_MS = 1_000;

    // Rough estimate of natural speech duration (~15 chars per second) used to
    // decide how much the TTS rate must be raised to fit the segment time slot.
    private static final long ESTIMATED_MS_PER_CHAR = 65;
    private static final float MIN_SPEECH_RATE = 1.0f;
    // Max rate increase between consecutive utterances. One delayed segment then
    // raises the pace gradually over a few sentences instead of jumping straight
    // from normal speed to maximum. Slowing back down is applied immediately.
    private static final float MAX_RATE_STEP_UP = 0.25f;

    static final String TTS_ENGINE_SYSTEM = "system";
    static final String TTS_ENGINE_PIPER  = "piper";
    private static final String VOT_ID_PREFIX = "vot_";
    private static final String VOT_TEST_ID_PREFIX = "vot_test_";
    private static final String TEST_VIDEO_ID = "test";
    private static final int TEST_SEGMENT_INDEX = -1;
    private static final long TEST_PREFETCH_WAIT = 500;

    private static float lastSpeechRate = MIN_SPEECH_RATE;
    private static long lastVideoTimeMs;

    private static List<TranscriptSegment> segments = new ArrayList<>();
    private static int lastSpokenIndex = -1;
    private static String currentVideoId = "";
    private static boolean isLoading;
    private static boolean sessionEnabled = Settings.VOT_ENABLED.get();
    private static boolean wasExplicitSeek;

    private static Runnable onStateChangeCallback;

    private static AudioManager audioManager;
    // Kept as a field so abandonAudioFocus() uses the same listener instance as requestAudioFocus().
    private static final AudioManager.OnAudioFocusChangeListener focusChangeListener = focusChange -> { };
    private static AudioFocusRequest focusRequest;
    private static boolean isDucking;

    private static TextToSpeech tts;
    private static boolean ttsReady;

    private static boolean isTestSpeaking;
    private static long currentTestId;
    private static long currentPreloadId;
    private static String lastTestVoiceId = "";

    private static final TtsEngine ttsEngine = TtsEngine.INSTANCE;

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
            Utils.verifyOnMainThread();

            // Always reset so seek detection fires correctly on the first videoTimeChanged
            // and so the first segment at the new position is spoken even when the same
            // video is reopened at a different timestamp (e.g. chapter links, continue watching).
            lastVideoTimeMs = 0;
            lastSpokenIndex = -1;
            wasExplicitSeek = false;
            if (videoId.equals(currentVideoId)) return;

            Logger.printDebug(() -> "newVideoLoaded");
            stopTts();
            currentVideoId = videoId;
            segments = new ArrayList<>();

            if (!Settings.VOT_ENABLED.get()) return;
            if (PlayerType.getCurrent() == PlayerType.INLINE_MINIMAL) return;
            TtsPrefetcher.updateVideo(videoId, segments);
            loadTranscript(videoId);
            if (!Settings.VOT_USE_NATIVE_TTS.get()
                    && TTS_ENGINE_PIPER.equals(Settings.VOT_TTS_VOICE_TYPE.get())) {
                PiperTtsEngine.warmUp(resolveTargetLang());
            }
        } catch (Exception ex) {
            logError(() -> "newVideoLoaded failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void videoTimeChanged(long timeMs) {
        if (!Settings.VOT_ENABLED.get() || !sessionEnabled) return; // Feature or session disabled.
        Utils.verifyOnMainThread();

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

        if (segments.isEmpty()) return;

        if (lastVideoTimeMs > 0) {
            final long timeSinceLastUpdate = Math.abs(timeMs - lastVideoTimeMs);
            if (timeSinceLastUpdate > SEEK_JUMP_THRESHOLD_MS) {
                // Large jump outside current segment area - stop everything.
                // Small jumps within the same segment are handled by speak()'s startTime logic.
                Logger.printDebug(() -> "videoTimeChanged jump detected: " + timeSinceLastUpdate + "ms");
                wasExplicitSeek = true;
                stopTts();
                lastSpokenIndex = -1;
            }
        }
        lastVideoTimeMs = timeMs;

        final long effectiveTimeMs = timeMs + TTS_LOOKAHEAD_MS;
        for (int i = 0, size = segments.size(); i < size; i++) {
            TranscriptSegment seg = segments.get(i);
            if (effectiveTimeMs >= seg.startMs() && timeMs < seg.endMs()) {
                if (i != lastSpokenIndex) {
                    if (!ttsEngine.isSpeaking()) {
                        lastSpokenIndex = i;
                        speak(seg, i);
                    }
                }
                return;
            }
        }
    }

    public static boolean isTranslationActive() {
        Utils.verifyOnMainThread();
        return Settings.VOT_ENABLED.get() && sessionEnabled && !segments.isEmpty();
    }

    public static boolean isSessionEnabled() {
        return sessionEnabled;
    }

    public static void toggleTranslation() {
        Utils.verifyOnMainThread();
        sessionEnabled = !sessionEnabled;
        if (!sessionEnabled) {
            stopTts();
            lastSpokenIndex = -1;
        }
        notifyStateChanged();
    }

    public static void reloadTranscript() {
        Utils.verifyOnMainThread();
        if (currentVideoId.isEmpty()) return;
        stopTts();
        segments = new ArrayList<>();
        lastSpokenIndex = -1;
        if (!isLoading) {
            loadTranscript(currentVideoId);
        }
    }

    public static void setOnTranslationStateChangeCallback(Runnable callback) {
        Utils.verifyOnMainThread();
        onStateChangeCallback = callback;
    }

    private static void notifyStateChanged() {
        Logger.printDebug(() -> "notifyStateChanged");
        Utils.verifyOnMainThread();
        if (onStateChangeCallback != null) onStateChangeCallback.run();
    }

    private static void loadTranscript(String videoId) {
        Logger.printDebug(() -> "loadTranscript: " + videoId);
        Utils.verifyOnMainThread();
        if (isLoading) return;
        isLoading = true;

        Utils.runOnBackgroundThread(() -> {
            try {
                // Later translation batches arrive asynchronously; swap the list in only
                // while the same video is still playing. Timings and size are identical
                // across updates, so lastSpokenIndex stays valid.
                List<TranscriptSegment> fetched = TranscriptFetcher.fetch(
                        videoId,
                        updated -> {
                            Utils.verifyOnMainThread();
                            if (videoId.equals(currentVideoId)) {
                                segments = updated;
                            }
                        },
                        () -> {
                            Utils.verifyOnMainThread();
                            return !videoId.equals(currentVideoId);
                        });

                Utils.runOnMainThread(() -> {
                    if (videoId.equals(currentVideoId)) {
                        segments = fetched;
                        TtsPrefetcher.updateVideo(videoId, fetched);
                        Logger.printDebug(() -> "Loaded: " + fetched.size() + " segments for :" + videoId);
                        notifyStateChanged();
                    }
                });
            } catch (Exception ex) {
                logError(() -> "Transcript fetch failed", ex);
            } finally {
                Utils.runOnMainThread(() -> {
                    isLoading = false;
                    // The video may have changed while this fetch was in flight - the isLoading
                    // gate blocked that load, so restart it for the current video.
                    if (!currentVideoId.isEmpty() && !currentVideoId.equals(videoId)
                            && Settings.VOT_ENABLED.get()) {
                        loadTranscript(currentVideoId);
                    }
                });
            }
        });
    }

    private static void ensureTts() {
        Utils.verifyOnMainThread();
        if (tts != null) return;
        Logger.printDebug(() -> "ensureTts creating tts");

        tts = new TextToSpeech(Utils.getContext(), status -> {
            Utils.verifyOnMainThread();
            if (status != TextToSpeech.SUCCESS) {
                Logger.printDebug(() -> "TTS initialization failed: " + status);
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
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    Utils.verifyOnMainThread();
                    if (utteranceId == null) return;
                    if (utteranceId.startsWith(VOT_TEST_ID_PREFIX)) {
                        try {
                            String suffix = utteranceId.substring(VOT_TEST_ID_PREFIX.length());
                            String[] parts = suffix.split("_");
                            long tId = Long.parseLong(parts[0]);
                            long pId = Long.parseLong(parts[1]);
                            ttsEngine.clearBusy(pId);
                            abandonDuckAfterTest(tId);
                        } catch (Exception ex) {
                            logError(() -> "Utterance listener onDone failure", ex);
                        }
                    } else if (utteranceId.startsWith(VOT_ID_PREFIX)) {
                        try {
                            long id = Long.parseLong(utteranceId.substring(VOT_ID_PREFIX.length()));
                            if (id == ttsEngine.getPlaybackId()) {
                                ttsEngine.clearBusy(id);
                                abandonDuck();
                            }
                        } catch (Exception ex) {
                            logError(() -> "Utterance listener onDone failure", ex);
                        }
                    }
                }

                @Override
                public void onError(String utteranceId) {
                    onDone(utteranceId);
                }
            });
            ttsReady = true;
        });
    }

    private static void updateTtsLanguage() {
        Utils.verifyOnMainThread();
        if (tts == null) return;
        Locale locale = Locale.forLanguageTag(resolveTargetLang());
        final int result = tts.setLanguage(locale);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.ENGLISH);
        }
    }

    private static void speak(TranscriptSegment seg, int index) {
        Utils.verifyOnMainThread();
        Logger.printDebug(() -> "speak: " + seg);
        String lang = resolveTargetLang();
        final float volume = Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f;

        // Time left until the next segment starts, measured from the current playback
        // position so a late start (busy engine, synthesis latency) raises the rate.
        final long availableMs = seg.endMs() - Math.max(lastVideoTimeMs, seg.startMs());

        String voice;
        if (Settings.VOT_USE_NATIVE_TTS.get()) {
            voice = TTS_ENGINE_SYSTEM;
        } else if (TTS_ENGINE_PIPER.equals(Settings.VOT_TTS_VOICE_TYPE.get())) {
            voice = TTS_ENGINE_PIPER;
        } else {
            voice = VoiceCatalog.resolve(lang, Settings.VOT_TTS_VOICE_TYPE.get());
        }
        if (voice == null) return;

        // Calculate if we should seek into the audio (e.g. after a short seek within segment).
        long startTimeMs = 0;
        if (wasExplicitSeek) {
            final long timeIntoSegment = lastVideoTimeMs - seg.startMs();
            if (timeIntoSegment > SEEK_INTO_THRESHOLD_MS) {
                // Approximate audio position. Ideally we'd use the speech rate, but since
                // rate is baked into SSML for Edge, we assume normal speed for simplicity.
                startTimeMs = timeIntoSegment;
            }
            final long startTimeMsFinal = startTimeMs;
            Logger.printDebug(() -> "Explicit seek resume. timeIntoSegment: " + timeIntoSegment
                    + "ms, startTimeMs: " + startTimeMsFinal + "ms");
            // Reset the flag so future segments at normal playback start from the beginning.
            wasExplicitSeek = false;
        }

        if (TTS_ENGINE_SYSTEM.equals(voice)) {
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
            final long id = ttsEngine.markBusy();
            // System TTS doesn't support seekTo, so it will always play from the start.
            tts.speak(seg.text(), TextToSpeech.QUEUE_FLUSH, params, VOT_ID_PREFIX + id);
            return;
        }

        if (TTS_ENGINE_PIPER.equals(voice)) {
            if (!PiperTtsEngine.isDownloaded(lang)) {
                Logger.printDebug(() -> "Piper model not downloaded, skipping segment");
                return;
            }
            byte[] piperCached = TtsCache.get(currentVideoId, index, TTS_ENGINE_PIPER, seg.text());
            final float piperRate = smoothRate(calculateSpeechRate(seg.text(), availableMs));
            final long startTimePiper = startTimeMs;
            requestDuck();
            final long piperPlaybackId = ttsEngine.markBusy();
            if (piperCached != null) {
                ttsEngine.play(piperCached, volume, piperRate, startTimePiper, piperPlaybackId,
                        VoiceOverTranslationPatch::abandonDuck);
                return;
            }
            final String piperText = seg.text();
            final String piperLang = lang;
            Utils.runOnBackgroundThread(() -> {
                byte[] wav = PiperTtsEngine.synthesize(piperText, piperLang);
                if (wav != null && wav.length > 0) {
                    TtsCache.put(currentVideoId, index, TTS_ENGINE_PIPER, piperText, wav);
                }
                Utils.runOnMainThread(() -> {
                    if (wav != null && wav.length > 0) {
                        ttsEngine.play(wav, volume, piperRate, startTimePiper,
                                piperPlaybackId, VoiceOverTranslationPatch::abandonDuck);
                    } else {
                        ttsEngine.clearBusy(piperPlaybackId);
                        abandonDuck();
                    }
                });
            });
            return;
        }

        // Check cache for Edge TTS.
        byte[] cached = TtsCache.get(currentVideoId, index, voice, seg.text());
        if (cached != null) {
            final float rate = smoothRate(calculateSpeechRate(seg.text(), availableMs));
            requestDuck();
            final long playbackId = ttsEngine.markBusy();
            ttsEngine.play(cached, volume, rate, startTimeMs, playbackId, VoiceOverTranslationPatch::abandonDuck);
            return;
        }

        final float rate = smoothRate(calculateSpeechRate(seg.text(), availableMs));
        requestDuck();
        // Use unified Edge synthesis/playback in background.
        // Edge synthesis doesn't support seeking during synthesis, but play() will seek the result.
        ttsEngine.speak(seg.text(), voice, volume, rate, startTimeMs, VoiceOverTranslationPatch::abandonDuck);
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
        Utils.verifyOnMainThread();
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
        Logger.printDebug(() -> "onVideoSeeked");
        Utils.verifyOnMainThread();
        wasExplicitSeek = true;

        // Check if the seek was within the current segment. If so, let videoTimeChanged
        // handle the restart/seek-into logic to avoid a jarring stop and restart.
        boolean insideSameSegment = false;
        if (lastSpokenIndex >= 0 && lastSpokenIndex < segments.size()) {
            TranscriptSegment seg = segments.get(lastSpokenIndex);
            if (lastVideoTimeMs >= seg.startMs() && lastVideoTimeMs < seg.endMs()) {
                insideSameSegment = true;
            }
        }

        if (!insideSameSegment) {
            stopTts();
            lastSpokenIndex = -1;
        }
    }

    public static String getTestString() {
        Locale locale = Locale.forLanguageTag(resolveTargetLang());
        return ResourceUtils.getStringByLocale("morphe_vot_tts_sample", locale);
    }

    /**
     * Synthesizes and plays a short test phrase with the given voice.
     * If the engine is already speaking the same voice, stops it.
     */
    static void testSpeak(String voiceId) {
        Logger.printDebug(() -> "testSpeak: " + voiceId);
        Utils.verifyOnMainThread();

        final boolean wasSameVoice = isTestSpeaking && voiceId.equals(lastTestVoiceId);
        stopTts();
        if (wasSameVoice) return;

        final long testId = ++currentTestId;
        isTestSpeaking = true;
        lastTestVoiceId = voiceId;

        final float volume = Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f;

        if (TTS_ENGINE_PIPER.equals(voiceId)) {
            String lang = resolveTargetLang();
            if (!PiperTtsEngine.isDownloaded(lang)) {
                isTestSpeaking = false;
                return;
            }
            byte[] piperCached = TtsCache.get(TEST_VIDEO_ID, TEST_SEGMENT_INDEX, TTS_ENGINE_PIPER, getTestString());
            if (piperCached != null) {
                requestDuck();
                final long pId = ttsEngine.markBusy();
                ttsEngine.play(piperCached, volume, 1.0f, pId, () -> abandonDuckAfterTest(testId));
                return;
            }
            requestDuck();
            final String testText = getTestString();
            final String testLang = lang;
            Utils.runOnBackgroundThread(() -> {
                byte[] wav = PiperTtsEngine.synthesize(testText, testLang);
                if (wav != null && wav.length > 0) {
                    TtsCache.put(TEST_VIDEO_ID, TEST_SEGMENT_INDEX, TTS_ENGINE_PIPER, testText, wav);
                }
                Utils.runOnMainThread(() -> {
                    if (wav != null && wav.length > 0) {
                        final long pId = ttsEngine.markBusy();
                        ttsEngine.play(wav, volume, 1.0f, pId, () -> abandonDuckAfterTest(testId));
                    } else {
                        isTestSpeaking = false;
                        abandonDuck();
                    }
                });
            });
            return;
        }

        if (TTS_ENGINE_SYSTEM.equals(voiceId)) {
            ensureTts();
            if (!ttsReady) {
                isTestSpeaking = false;
                return;
            }
            requestDuck();
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
            tts.setSpeechRate(1.0f);
            final long pId = ttsEngine.markBusy();
            tts.speak(getTestString(), TextToSpeech.QUEUE_FLUSH, params,
                    VOT_TEST_ID_PREFIX + testId + "_" + pId);
            return;
        }

        byte[] cached = TtsCache.get(TEST_VIDEO_ID, TEST_SEGMENT_INDEX, voiceId, getTestString());
        if (cached != null) {
            requestDuck();
            final long id = ttsEngine.markBusy();
            ttsEngine.play(cached, volume, 1.0f, id, () -> abandonDuckAfterTest(testId));
            return;
        }

        requestDuck();
        ttsEngine.speak(getTestString(), voiceId, volume, 1.0f, () -> abandonDuckAfterTest(testId));
    }

    /**
     * Preloads test phrases for all Edge TTS voices in the current target language.
     */
    static void preloadTestVoices() {
        Utils.verifyOnMainThread();
        final long preloadId = ++currentPreloadId;

        String lang = resolveTargetLang();
        List<VoiceCatalog.Voice> voices = VoiceCatalog.getVoicesForLang(lang);
        if (voices == null || voices.isEmpty()) return;

        Utils.runOnBackgroundThread(() -> {
            String testString = getTestString();
            for (VoiceCatalog.Voice voice : voices) {
                if (preloadId != currentPreloadId) {
                    Logger.printDebug(() -> "Aborting stale preload request: " + preloadId);
                    return;
                }

                if (TtsCache.get(TEST_VIDEO_ID, TEST_SEGMENT_INDEX, voice.id, testString) != null) {
                    continue;
                }

                try {
                    Logger.printDebug(() -> "Prefetching test phrase for: " + voice.id);
                    byte[] data = ttsEngine.prefetch(testString, voice.id);
                    if (data.length > 0) {
                        TtsCache.put(TEST_VIDEO_ID, TEST_SEGMENT_INDEX, voice.id, testString, data);
                    }
                    Thread.sleep(TEST_PREFETCH_WAIT);

                } catch (Exception ex) {
                    logError(() -> "preloadTestVoices failure: " + voice, ex);
                    return;
                }
            }
        });
    }

    private static void stopTts() {
        Utils.verifyOnMainThread();
        Logger.printDebug(() -> "stopTts");
        isTestSpeaking = false;
        ttsEngine.stop();
        if (tts != null) tts.stop();
        // Speech was interrupted (new video, seek, pause) - no backlog left to catch
        // up on, so the next utterance starts from normal speed again.
        lastSpeechRate = MIN_SPEECH_RATE;
        lastSpokenIndex = -1;
        abandonDuck();
    }

    /**
     * Requests transient audio focus with ducking so ExoPlayer (YouTube) receives
     * AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK and reduces its media volume while TTS speaks.
     * No-op if ducking is already active.
     */
    private static void requestDuck() {
        Utils.verifyOnMainThread();
        Logger.printDebug(() -> "requestDuck");
        if (isDucking) return;
        isDucking = true;
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build();
        getAudioManager().requestAudioFocus(focusRequest);
    }

    /**
     * Releases the transient audio focus so ExoPlayer restores its media volume.
     * No-op if ducking is not active or a test is still playing.
     */
    private static void abandonDuck() {
        Utils.verifyOnMainThread();
        Logger.printDebug(() -> "abandonDuck isTestSpeaking: " + isTestSpeaking
                + " isDucking: " + isDucking);
        if (isTestSpeaking) return;
        if (!isDucking) return;

        isDucking = false;
        if (audioManager == null) return;
        if (focusRequest != null) {
            Logger.printDebug(() -> "abandonDuck requesting focus");
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
    }

    private static void abandonDuckAfterTest(long id) {
        Utils.verifyOnMainThread();
        Logger.printDebug(() -> "abandonDuckAfterTest: " + id);
        if (id != currentTestId) return;
        isTestSpeaking = false;
        abandonDuck();
    }

    private static AudioManager getAudioManager() {
        Utils.verifyOnMainThread();
        if (audioManager == null) {
            audioManager = (AudioManager) Utils.getContext().getSystemService(Context.AUDIO_SERVICE);
        }
        return audioManager;
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
