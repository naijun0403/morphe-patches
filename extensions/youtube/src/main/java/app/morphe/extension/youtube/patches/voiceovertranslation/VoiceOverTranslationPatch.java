/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.settings.BaseSettings.DEBUG;
import static app.morphe.extension.youtube.patches.voiceovertranslation.TranscriptTranslator.TRANSLATION_SERVICE_MY_MEMORY;
import static app.morphe.extension.youtube.patches.voiceovertranslation.TranscriptTranslator.TRANSLATION_SERVICE_OPENROUTER;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Pair;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.ui.CustomDialog;
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

    public static final Setting.ImportExportCallback VOT_IMPORT_EXPORT_CALLBACK = new Setting.ImportExportCallback() {
        @Override
        public void settingsImported(@Nullable Activity context) {}

        @Override
        public void settingsExported(@Nullable Activity context) {
            showExportWarningIfNeeded(context);
        }
    };

    private static void showExportWarningIfNeeded(@Nullable Activity activity) {
        Utils.verifyOnMainThread();
        if (activity == null) return;
        if (Settings.VOT_OPENROUTER_API_KEY.get().trim().isEmpty()) return;
        if (Settings.VOT_HIDE_EXPORT_WARNING.get()) return;
        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                activity,
                null,
                str("morphe_vot_export_api_key_warning"),
                null,
                null,
                () -> {},
                null,
                str("morphe_vot_do_not_show_again"),
                () -> Settings.VOT_HIDE_EXPORT_WARNING.save(true),
                true
        );
        Utils.showDialog(activity, dialogPair.first, false, null);
    }

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

    public static class OpenRouterServiceAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.VOT_TRANSLATION_SERVICE.get().equals(TRANSLATION_SERVICE_OPENROUTER);
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

    public static final String TTS_ENGINE_SYSTEM = "system";
    private static final String VOT_ID_PREFIX = "vot_";
    private static final String VOT_TEST_ID_PREFIX = "vot_test_";
    private static final String TEST_VIDEO_ID = "test";
    private static final int TEST_SEGMENT_INDEX = -1;
    private static final long TEST_PREFETCH_WAIT = 500;

    private static float lastSpeechRate = MIN_SPEECH_RATE;
    static volatile long lastVideoTimeMs;
    // Tracks the latest known position even when paused, unlike lastVideoTimeMs which only
    // updates during PLAYING. Used by translate() to pick the right initial batch when a video
    // starts mid-position (PAUSED setVideoTime calls arrive before newVideoLoaded and before
    // lastVideoTimeMs is ever set for the new video).
    static volatile long videoPositionHint;
    // Estimated video timestamp when the currently-playing TTS audio finishes.
    // Duck is held until this time so TTS that extends into the gap before the
    // next segment does not prematurely restore the original audio volume.
    private static long ttsEndVideoTimeMs;

    private static List<TranscriptSegment> segments = new ArrayList<>();
    private static int lastSpokenIndex = -1;
    private static String currentVideoId = "";
    private static boolean isLoading;
    private static boolean sessionEnabled = Settings.VOT_SESSION_ENABLED.get();
    private static boolean wasExplicitSeek;
    private static volatile boolean httpErrorDialogShownThisVideo;

    private static Runnable onStateChangeCallback;

    private static AudioManager audioManager;
    // Kept as a field so abandonAudioFocus() uses the same listener instance as requestAudioFocus().
    private static final AudioManager.OnAudioFocusChangeListener focusChangeListener = focusChange -> {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            Logger.printDebug(() -> "Ducking focus lost: " + focusChange);
            isDucking = false;
            updateDucking();
        }
    };
    private static AudioFocusRequest focusRequest;
    private static boolean isDucking;
    private static boolean duckDesired;

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
                if (playerType == PlayerType.NONE) {
                    currentVideoId = "";
                    segments = new ArrayList<>();
                    TtsPrefetcher.clear();
                }
            }
            return kotlin.Unit.INSTANCE;
        });

        VideoState.getOnChange().addObserver(state -> {
            if (state == VideoState.PAUSED || state == VideoState.ENDED) {
                Logger.printDebug(() -> "Stopping TTS for video state: " + state);
                stopTts();
                if (state == VideoState.ENDED) {
                    TtsPrefetcher.clear();
                }
            }
            return kotlin.Unit.INSTANCE;
        });
    }

    /**
     * Injection point.
     */
    public static void newVideoLoaded(String videoId) {
        // Always reset so seek detection fires correctly on the first videoTimeChanged
        // and so the first segment at the new position is spoken even when the same
        // video is reopened at a different timestamp (e.g. chapter links, continue watching).
        lastVideoTimeMs = 0;
        lastSpokenIndex = -1;
        wasExplicitSeek = false;
        if (videoId.equals(currentVideoId)) return;

        Logger.printDebug(() -> "preloadTranslations newVideoLoaded");
        TranscriptTranslator.requestAbort();
        stopTts();
        currentVideoId = videoId;
        segments = new ArrayList<>();
        httpErrorDialogShownThisVideo = false;

        if (!Settings.VOT_ENABLED.get() || !sessionEnabled) return;
        if (PlayerType.getCurrent() == PlayerType.INLINE_MINIMAL) return;
        TtsPrefetcher.updateVideo(videoId, segments);
        loadTranscript(videoId);
    }

    /**
     * Injection point.
     */
    public static void videoTimeChanged(long timeMs) {
        if (!Settings.VOT_ENABLED.get() || !sessionEnabled) {
            updateDucking();
            return; // Feature or session disabled.
        }
        Utils.verifyOnMainThread();

        PlayerType currentPlayerType = PlayerType.getCurrent();
        if (!currentPlayerType.isMaximizedOrFullscreen()
                && currentPlayerType != PlayerType.WATCH_WHILE_MINIMIZED
                && currentPlayerType != PlayerType.WATCH_WHILE_PICTURE_IN_PICTURE) {
            Logger.printDebug(() -> "Ignoring TTS for player type: " + currentPlayerType);
            return;
        }
        VideoState state = VideoState.getCurrent();
        // Capture position before the PAUSED early return so translate() can pick the right
        // initial batch even when the first setVideoTime ticks arrive before play begins.
        videoPositionHint = timeMs;
        // Video state can be null until the overlay is activated the first time.
        if (state != null && state != VideoState.PLAYING) {
            Logger.printDebug(() -> "Ignoring TTS for video state: " + state);
            return; // paused, ended, or loading
        }

        TtsPrefetcher.updateTime(timeMs);

        final long prevVideoTimeMs = lastVideoTimeMs;
        lastVideoTimeMs = timeMs;

        if (segments.isEmpty()) return;

        if (prevVideoTimeMs > 0) {
            final long timeSinceLastUpdate = Math.abs(timeMs - prevVideoTimeMs);
            if (timeSinceLastUpdate > SEEK_JUMP_THRESHOLD_MS) {
                // Small jumps within the same segment are handled by speak()'s startTime logic.
                Logger.printDebug(() -> "videoTimeChanged jump detected: " + timeSinceLastUpdate + "ms");
                wasExplicitSeek = true;
                stopTts();
                lastSpokenIndex = -1;
                // Re-target translation at the new position so a seek into an untranslated region
                // is translated next instead of waiting for the sequential dispatch to reach it.
                TranscriptTranslator.onSeek(timeMs);
            }
        }

        final long effectiveTimeMs = timeMs + TTS_LOOKAHEAD_MS;
        for (int i = 0, size = segments.size(); i < size; i++) {
            TranscriptSegment seg = segments.get(i);
            if (effectiveTimeMs >= seg.startMs() && timeMs < seg.endMs()) {
                if (i != lastSpokenIndex) {
                    if (TranscriptTranslator.isAwaitingTranslationAt(i, seg.startMs(), seg.text())) {
                        final int segIdx = i;
                        Logger.printDebug(() -> "Waiting for translation at segment: " + segIdx);
                        break;
                    }
                    // isAwaitingTranslationAt returns false once a batch is marked done, even if
                    // translation failed and the segment kept its source-language text. Check lang
                    // so a permanently untranslated segment is never spoken.
                    if (TranscriptFetcher.isSpokenLanguageDifferent(resolveTargetLang(), seg.lang())) {
                        final int segIdx = i;
                        Logger.printDebug(() -> "Skipping untranslated segment: " + segIdx);
                        break;
                    }
                    if (!ttsEngine.isSpeaking() || wasExplicitSeek) {
                        lastSpokenIndex = i;
                        speak(seg, i);
                    }
                }
                break;
            }
        }
        // ttsEndVideoTimeMs keeps the duck alive while TTS speaks into the gap before the next
        // segment, preventing a brief volume flicker mid-utterance.
        duckDesired = ttsEngine.isSpeaking() || isTestSpeaking || timeMs < ttsEndVideoTimeMs;
        updateDucking();
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
        Settings.VOT_SESSION_ENABLED.save(sessionEnabled);
        if (!sessionEnabled) {
            stopTts();
            lastSpokenIndex = -1;
        } else {
            if (!currentVideoId.isEmpty() && segments.isEmpty() && !isLoading) {
                loadTranscript(currentVideoId);
            }
        }
        notifyStateChanged();
    }

    public static void interruptSpeech() {
        Utils.verifyOnMainThread();
        stopTts();
    }

    public static void reloadTranscript() {
        Utils.verifyOnMainThread();
        if (currentVideoId.isEmpty()) return;
        stopTts();
        segments = new ArrayList<>();
        lastSpokenIndex = -1;
        // Without this, in-flight onUpdate callbacks for the old language would restore
        // stale segments after we cleared them.
        TranscriptTranslator.requestAbort();
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
        final String loadLang = resolveTargetLang();
        final String loadService = Settings.VOT_TRANSLATION_SERVICE.get();

        Utils.runOnBackgroundThread(() -> {
            try {
                // Later translation batches arrive asynchronously; swap the list in only
                // while the same video is still playing. Timings and size are identical
                // across updates, so lastSpokenIndex stays valid.
                List<TranscriptSegment> fetched = TranscriptFetcher.fetch(
                        videoId,
                        updated -> {
                            Utils.verifyOnMainThread();
                            if (videoId.equals(currentVideoId) && loadLang.equals(resolveTargetLang())) {
                                // If the segment we last started speaking had its text replaced
                                // by a freshly-arrived translation, stop and let videoTimeChanged
                                // re-speak it with the translated text on the next tick.
                                if (lastSpokenIndex >= 0
                                        && lastSpokenIndex < segments.size()
                                        && lastSpokenIndex < updated.size()
                                        && !segments.get(lastSpokenIndex).text()
                                        .equals(updated.get(lastSpokenIndex).text())) {
                                    stopTts();
                                }
                                segments = updated;
                            }
                        },
                        () -> {
                            Utils.verifyOnMainThread();
                            return !videoId.equals(currentVideoId)
                                    || VideoState.getCurrent() == VideoState.ENDED;
                        });

                Utils.runOnMainThread(() -> {
                    if (videoId.equals(currentVideoId) && loadLang.equals(resolveTargetLang())) {
                        // With sequential batch execution, cancelCheck.get() ensures every
                        // onUpdate fires before translate() returns, so segments is already
                        // fully translated by the time we arrive here. Only fall back to the
                        // batch-0 snapshot (fetched) if onUpdate never ran (single batch or
                        // no translation needed).
                        if (segments.isEmpty()) segments = fetched;
                        TtsPrefetcher.updateVideo(videoId, segments);
                        Logger.printDebug(() -> "Loaded: " + fetched.size() + " segments for :" + videoId);
                        notifyStateChanged();
                    }
                });
            } catch (Exception ex) {
                logError(() -> "Transcript fetch failed", ex);
            } finally {
                Utils.runOnMainThread(() -> {
                    isLoading = false;
                    // Restart if the video, language, or translation provider changed while this fetch was in flight.
                    if (!currentVideoId.isEmpty() && Settings.VOT_ENABLED.get()
                            && (!currentVideoId.equals(videoId)
                            || !loadLang.equals(resolveTargetLang())
                            || !loadService.equals(Settings.VOT_TRANSLATION_SERVICE.get()))) {
                        loadTranscript(currentVideoId);
                    }
                });
            }
        });
    }

    public static void ensureTts() {
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
                            if (tId == currentTestId) isTestSpeaking = false;
                        } catch (Exception ex) {
                            logError(() -> "Utterance listener onDone failure", ex);
                        }
                    } else if (utteranceId.startsWith(VOT_ID_PREFIX)) {
                        try {
                            long id = Long.parseLong(utteranceId.substring(VOT_ID_PREFIX.length()));
                            if (id == ttsEngine.getPlaybackId()) {
                                ttsEngine.clearBusy(id);
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
        Logger.printDebug(() -> "Speak: " + seg);
        String lang = resolveTargetLang();
        final float volume = Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f;

        String voice = resolveVoice(lang);
        if (voice == null) return;

        final long speakFromMs = Math.max(lastVideoTimeMs, seg.startMs());

        final long availableMs = seg.endMs() - speakFromMs;

        // Exact if cached, otherwise estimated from char count.
        final long speechDurationMs = getSpeechDurationMs(seg, index, voice, lang);

        // Calculate if we should seek into the audio (e.g. after a short seek within segment).
        long startTimeMs = 0;
        if (wasExplicitSeek) {
            final long timeIntoSegment = lastVideoTimeMs - seg.startMs();
            if (timeIntoSegment > SEEK_INTO_THRESHOLD_MS) {
                // Approximate audio position. Ideally we'd use the speech rate, but since rate is
                // baked into SSML for Edge, we assume normal speed. The TTS clip is usually shorter
                // than the video segment, so clamp to its length to avoid seeking past the end.
                startTimeMs = Math.min(timeIntoSegment, speechDurationMs);
            }
            final long startTimeMsFinal = startTimeMs;
            Logger.printDebug(() -> "Explicit seek resume. timeIntoSegment: " + timeIntoSegment
                    + "ms, startTimeMs: " + startTimeMsFinal + "ms");
            // Reset the flag so future segments at normal playback start from the beginning.
            wasExplicitSeek = false;
        }

        final float rate = smoothRate(calculateSpeechRate(speechDurationMs, availableMs));
        ttsEndVideoTimeMs = speakFromMs + (long) (speechDurationMs / rate);

        if (TTS_ENGINE_SYSTEM.equals(voice)) {
            ensureTts();
            if (!ttsReady) {
                Logger.printDebug(() -> "Native TTS not ready, skipping segment");
                return;
            }
            updateTtsLanguage();
            requestDuck();
            updateDucking();
            tts.setSpeechRate(rate);
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
            final long id = ttsEngine.markBusy();
            // System TTS doesn't support seekTo, so it will always play from the start.
            tts.speak(seg.text(), TextToSpeech.QUEUE_FLUSH, params, VOT_ID_PREFIX + id);
            return;
        }

        requestDuck();
        updateDucking();
        byte[] cached = TtsCache.get(currentVideoId, index, voice, lang, seg.text());
        if (cached != null) {
            final long playbackId = ttsEngine.markBusy();
            ttsEngine.play(cached, volume, rate, startTimeMs, playbackId, null);
            return;
        }

        // Edge synthesis doesn't support seeking during synthesis; play() will seek the result.
        ttsEngine.speak(seg.text(), voice, lang, volume, rate, startTimeMs, null);
    }

    /**
     * Returns a speech rate multiplier that fits {@code speechDurationMs} into {@code availableMs}.
     * Never slows below normal speed and is capped by the user-configured max rate.
     */
    private static float calculateSpeechRate(long speechDurationMs, long availableMs) {
        final float maxRate = Settings.VOT_MAX_SPEECH_RATE.get() / 10.0f;
        if (availableMs <= 0) return maxRate;
        return Math.max(MIN_SPEECH_RATE, Math.min(maxRate, speechDurationMs / (float) availableMs));
    }

    private static long getSpeechDurationMs(TranscriptSegment seg, int index, String voice, String lang) {
        final long cachedDuration = TtsCache.getDuration(currentVideoId, index, voice, lang, seg.text());
        return cachedDuration > 0 ? cachedDuration : (long) seg.text().length() * ESTIMATED_MS_PER_CHAR;
    }

    /**
     * Estimates natural speech duration from character count and delegates to
     * {@link #calculateSpeechRate(long, long)}. Used when exact duration is not yet known.
     */
    private static float calculateSpeechRate(String text, long availableMs) {
        return calculateSpeechRate((long) text.length() * ESTIMATED_MS_PER_CHAR, availableMs);
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

        if (TTS_ENGINE_SYSTEM.equals(voiceId)) {
            ensureTts();
            if (!ttsReady) {
                isTestSpeaking = false;
                return;
            }
            updateTtsLanguage();
            requestDuck();
            updateDucking();
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
            tts.setSpeechRate(1.0f);
            final long pId = ttsEngine.markBusy();
            tts.speak(getTestString(), TextToSpeech.QUEUE_FLUSH, params,
                    VOT_TEST_ID_PREFIX + testId + "_" + pId);
            return;
        }

        final String lang = resolveTargetLang();
        byte[] cached = TtsCache.get(TEST_VIDEO_ID, TEST_SEGMENT_INDEX, voiceId, lang, getTestString());
        if (cached != null) {
            requestDuck();
            updateDucking();
            final long id = ttsEngine.markBusy();
            ttsEngine.play(cached, volume, id, () -> updateIsTestSpeaking(testId));
            return;
        }

        requestDuck();
        updateDucking();
        ttsEngine.speak(getTestString(), voiceId, resolveTargetLang(), volume, () -> updateIsTestSpeaking(testId));
    }

    private static void updateIsTestSpeaking(long testId) {
        Utils.verifyOnMainThread();
        if (testId == currentTestId) isTestSpeaking = false;
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

                if (TtsCache.get(TEST_VIDEO_ID, TEST_SEGMENT_INDEX, voice.id, lang, testString) != null) {
                    continue;
                }

                byte[] diskData = TtsCache.getTestSampleFromDisk(voice.id, lang);
                if (diskData != null) {
                    TtsCache.put(TEST_VIDEO_ID, TEST_SEGMENT_INDEX, voice.id, lang, testString, diskData);
                    continue;
                }

                try {
                    Logger.printDebug(() -> "Prefetching test phrase for: " + voice.id);
                    byte[] data = ttsEngine.prefetch(testString, voice.id, lang);
                    if (data.length > 0) {
                        TtsCache.put(TEST_VIDEO_ID, TEST_SEGMENT_INDEX, voice.id, lang, testString, data);
                        TtsCache.putTestSampleToDisk(voice.id, lang, data);
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
        ttsEndVideoTimeMs = 0;
        abandonDuck();
    }

    /**
     * Requests transient audio focus with ducking so ExoPlayer (YouTube) receives
     * AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK and reduces its media volume while TTS speaks.
     * No-op if ducking is already active.
     */
    private static void requestDuck() {
        Utils.verifyOnMainThread();
        duckDesired = true;
    }

    /**
     * Releases the transient audio focus so ExoPlayer restores its media volume.
     * No-op if ducking is not active.
     */
    private static void abandonDuck() {
        Utils.verifyOnMainThread();
        duckDesired = false;
    }

    private static void updateDucking() {
        Utils.verifyOnMainThread();
        if (duckDesired == isDucking) return;

        if (duckDesired) {
            Logger.printDebug(() -> "ducking enabled");
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setWillPauseWhenDucked(false)
                        .setOnAudioFocusChangeListener(focusChangeListener)
                        .build();
            }
            int result = getAudioManager().requestAudioFocus(focusRequest);
            isDucking = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (!isDucking) {
                logError(() -> "Failed to request audio focus: " + result, null);
            }
        } else {
            Logger.printDebug(() -> "ducking disabled");
            isDucking = false;
            if (audioManager == null) return;
            if (focusRequest != null) {
                Logger.printDebug(() -> "abandoning focus request");
                audioManager.abandonAudioFocusRequest(focusRequest);
                focusRequest = null;
            }
        }
    }

    private static AudioManager getAudioManager() {
        Utils.verifyOnMainThread();
        if (audioManager == null) {
            audioManager = (AudioManager) Utils.getContext().getSystemService(Context.AUDIO_SERVICE);
        }
        return audioManager;
    }

    /**
     * @return BCP-47 language code(pt-BR, pt-PT, en-US, etc).
     */
    static String resolveTargetLang() {
        return Settings.VOT_CAPTION_LANGUAGE.isSetToDefault()
                ? Locale.getDefault().toLanguageTag()
                : Settings.VOT_CAPTION_LANGUAGE.get();
    }

    /**
     * @return ISO 639 language code (pt, en, es, etc).
     */
    static String resolveTargetLangIso639() {
        String lang = resolveTargetLang();
        final int index = lang.indexOf('-');
        if (index > 0) {
            lang = lang.substring(0, index);
        }
        return lang;
    }


    /**
     * @param lang ISO 639 (pt) or BCP 47 (pt-BR).
     */
    private static String resolveVoice(String lang) {
        return Settings.VOT_USE_NATIVE_TTS.get()
                ? TTS_ENGINE_SYSTEM
                : VoiceCatalog.resolve(lang, Settings.VOT_TTS_VOICE_TYPE.get());
    }

    static void notifyHttpError(int statusCode) {
        if (statusCode < 400 || statusCode >= 500) return;
        if (!Settings.VOT_SHOW_HTTP_ERROR_DIALOG.get()) return;
        if (httpErrorDialogShownThisVideo) return;
        httpErrorDialogShownThisVideo = true;
        Utils.runOnMainThread(() -> {
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            showHttpErrorDialog(activity, statusCode);
        });
    }

    static void notifyOpenRouterError(int httpCode, String errorBody) {
        if (httpErrorDialogShownThisVideo) return;
        httpErrorDialogShownThisVideo = true;
        if (TranscriptTranslator.isOpenRouterCreditsError(httpCode, errorBody)) {
            Utils.runOnMainThread(() -> {
                Activity activity = Utils.getActivity();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                showOpenRouterCreditsDialog(activity);
            });
        } else {
            Utils.showToastLong(str("morphe_vot_openrouter_error", httpCode));
        }
    }

    private static void showOpenRouterCreditsDialog(Activity activity) {
        Utils.verifyOnMainThread();
        try {
            Pair<Dialog, LinearLayout> pair = CustomDialog.create(
                    activity,
                    str("morphe_vot_openrouter_credits_title"),
                    str("morphe_vot_openrouter_credits_message"),
                    null,
                    null,
                    () -> {},
                    null,
                    str("morphe_vot_openrouter_open_website"),
                    () -> {
                        try {
                            activity.startActivity(
                                    new Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/credits"))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Exception ex) {
                            logError(() -> "Failed to open openrouter.ai", ex);
                        }
                    },
                    true
            );
            pair.first.show();
        } catch (Exception ex) {
            logError(() -> "showOpenRouterCreditsDialog failure", ex);
        }
    }

    private static void showHttpErrorDialog(Activity activity, int statusCode) {
        Utils.verifyOnMainThread();
        try {
            Pair<Dialog, LinearLayout> pair = CustomDialog.create(
                    activity,
                    str("morphe_vot_http_error_title"),
                    str("morphe_vot_http_error_message", statusCode),
                    null,
                    null,
                    () -> { },
                    null,
                    str("morphe_vot_do_not_show_again"),
                    () -> Settings.VOT_SHOW_HTTP_ERROR_DIALOG.save(false),
                    true
            );
            pair.first.show();
        } catch (Exception ex) {
            logError(() -> "showHttpErrorDialog failure", ex);
        }
    }

    public static void fetchOpenRouterModelCost(String model, Consumer<Float> onResult) {
        TranscriptTranslator.fetchOpenRouterModelCost(model, onResult);
    }

    public static String formatOpenRouterCostPerHundredHours(float cost) {
        if (cost == 0) {
            return str("morphe_vot_cost_free");
        }

        String costString;
        if (cost < 0.001f) {
            costString = "< $0.001";
        } else {
            String format;
            if (cost < 0.01f) {
                format = "$%.3f";
            } else {
                format = "$%.2f";
            }
            costString = String.format(Locale.US, format, cost);
        }

        return str("morphe_vot_cost_per_hour", costString);
    }

    static void logError(Logger.LogMessage message, @Nullable Exception ex) {
        if (DEBUG.get()) Logger.printException(message, ex);
        else Logger.printInfo(message, ex);
    }
}
