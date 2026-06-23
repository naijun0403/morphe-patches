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
import android.content.Intent;
import android.media.AudioAttributes;
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
import app.morphe.extension.youtube.patches.VideoInformation;
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

    // Minimum time into a segment to justify seeking within the audio instead of
    // playing from the start. Prevents tiny pops on small adjustments.
    private static final long SEEK_INTO_THRESHOLD_MS = 1_000;

    public static final String TTS_ENGINE_SYSTEM = "system";
    private static final String VOT_ID_PREFIX = "vot_";
    private static final String VOT_TEST_ID_PREFIX = "vot_test_";
    private static final String TEST_VIDEO_ID = "test";
    private static final int TEST_SEGMENT_INDEX = -1;
    private static final long TEST_PREFETCH_WAIT = 500;

    static volatile long lastVideoTimeMs;
    // Tracks the latest known position even when paused, unlike lastVideoTimeMs which only
    // updates during PLAYING. Used by translate() to pick the right initial batch when a video
    // starts mid-position (PAUSED setVideoTime calls arrive before newVideoLoaded and before
    // lastVideoTimeMs is ever set for the new video).
    static volatile long videoPositionHint;

    private static List<TranscriptSegment> segments = new ArrayList<>();

    /** Index of the segment whose audio is mid-playback, or -1. Safe off-main-thread. */
    static int getLastSpokenIndex() {
        return TtsQueue.getPlayingSegmentIndex();
    }
    private static String currentVideoId = "";
    private static boolean isLoading;
    private static boolean sessionEnabled = Settings.VOT_SESSION_ENABLED.get();
    private static boolean wasExplicitSeek;
    private static volatile boolean httpErrorDialogShownThisVideo;

    private static Runnable onStateChangeCallback;

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
                TtsQueue.clear();
                if (playerType == PlayerType.NONE) {
                    currentVideoId = "";
                    segments = new ArrayList<>();
                    TtsPrefetcher.clear();
                }
            }
            return kotlin.Unit.INSTANCE;
        });

        VideoState.getOnChange().addObserver(state -> {
            if (state == VideoState.PAUSED) {
                Logger.printDebug(() -> "Pausing TTS queue for video state: " + state);
                TtsQueue.pauseForVideoState();
            } else if (state == VideoState.PLAYING) {
                TtsQueue.resumeForVideoState();
            } else if (state == VideoState.ENDED) {
                Logger.printDebug(() -> "Stopping TTS prefetch and abandoning ducking: " + state);
                // Do not clear the queue; allow whatever is currently playing to finish.
                VotOriginalVolumePatch.clearAudioMultiplier();
                TtsPrefetcher.clear();
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
        TtsQueue.clear();
        wasExplicitSeek = false;
        if (videoId.equals(currentVideoId)) return;

        Logger.printDebug(() -> "preloadTranslations newVideoLoaded");
        TranscriptTranslator.requestAbort();
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
            VotOriginalVolumePatch.clearAudioMultiplier();
            return; // Feature or session disabled.
        }
        Utils.verifyOnMainThread();
        TtsQueue.recomputeRates();

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
            // Scale by playback speed so at high speeds a normal tick (which spans a
            // larger in-video gap) isn't mistaken for a user seek.
            final long jumpThreshold = (long) (SEEK_JUMP_THRESHOLD_MS
                    * Math.max(1.0f, VideoInformation.getPlaybackSpeed()));
            if (timeSinceLastUpdate > jumpThreshold) {
                // Small jumps within the same segment are handled by enqueue's startTime logic.
                Logger.printDebug(() -> "videoTimeChanged jump detected: " + timeSinceLastUpdate + "ms");
                wasExplicitSeek = true;
                TtsQueue.clear();
                // Re-target translation at the new position so a seek into an untranslated region
                // is translated next instead of waiting for the sequential dispatch to reach it.
                TranscriptTranslator.onSeek(timeMs);
            }
        }

        // Amount of time to look ahead for the next segment to schedule it.
        final long lookaheadMs = 900;
        // Small delay added to scheduled segments to ensure the video time has definitely
        // reached the segment start time before the check runs.
        final long schedulingDelayMs = 10;
        final String lang = resolveTargetLang();
        final String voice = resolveVoice(lang);

        for (int i = 0, size = segments.size(); i < size; i++) {
            if (i <= TtsQueue.getLastEnqueuedIndex()) continue;
            TranscriptSegment seg = segments.get(i);
            final long segPlaybackStartMs = seg.playbackStartMs;

            if (timeMs >= seg.playbackEndMs) continue;

            if (segPlaybackStartMs > timeMs + lookaheadMs) {
                final float speed = Math.max(0.1f, VideoInformation.getPlaybackSpeed());
                final long delayMs = (long) ((segPlaybackStartMs - timeMs + schedulingDelayMs) / speed);
                Logger.printDebug(() -> "Scheduling next segment check in " + delayMs + "ms");
                Utils.runOnMainThreadDelayed(() -> videoTimeChanged(VideoInformation.getVideoTime()), delayMs);
                break;
            }

            if (TranscriptTranslator.isAwaitingTranslationAt(i, seg.startMs, seg.text)) {
                final int segIdx = i;
                Logger.printDebug(() -> "Waiting for translation at segment: " + segIdx);
                break;
            }
            // isAwaitingTranslationAt returns false once a batch is marked done even if
            // translation failed; check lang too so a permanently untranslated segment
            // is never enqueued.
            if (TranscriptFetcher.isSpokenLanguageDifferent(lang, seg.lang)) {
                final int segIdx = i;
                Logger.printDebug(() -> "Skipping untranslated segment: " + segIdx);
                break;
            }
            if (voice == null) break;

            TtsQueue.enqueue(buildItem(seg, i, voice, lang, timeMs));
        }

        if (TtsQueue.isNotEmpty() || isTestSpeaking) {
            VotOriginalVolumePatch.setAudioMultiplier(Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f);
        } else {
            VotOriginalVolumePatch.clearAudioMultiplier();
        }
    }

    private static TtsQueueItem buildItem(TranscriptSegment seg, int index, String voice,
                                          String lang, long timeMs) {
        Utils.verifyOnMainThread();
        final boolean isSystemTts = TTS_ENGINE_SYSTEM.equals(voice);
        final long estimatedDurationMs = getSpeechDurationMs(seg, index, voice, lang);

        long startTimeMs = 0;
        if (wasExplicitSeek && TtsQueue.isEmpty()) {
            final long timeIntoSegment = timeMs - seg.playbackStartMs;
            if (timeIntoSegment > SEEK_INTO_THRESHOLD_MS) {
                // Rate is baked into Edge SSML so we assume natural speed for the offset.
                // Clamp to clip length so we never seek past the end.
                startTimeMs = Math.min(timeIntoSegment, estimatedDurationMs);
            }
            final long startTimeMsFinal = startTimeMs;
            Logger.printDebug(() -> "Explicit seek resume. timeIntoSegment: " + timeIntoSegment
                    + "ms, startTimeMs: " + startTimeMsFinal + "ms");
            wasExplicitSeek = false;
        }

        return new TtsQueueItem(index, seg, voice, lang, isSystemTts, estimatedDurationMs, startTimeMs);
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
            TtsQueue.clear();
        } else {
            if (!currentVideoId.isEmpty() && segments.isEmpty() && !isLoading) {
                loadTranscript(currentVideoId);
            }
        }
        notifyStateChanged();
    }

    public static void interruptSpeech() {
        Utils.verifyOnMainThread();
        TtsQueue.clear();
    }

    public static void resetPlaybackState() {
        Utils.verifyOnMainThread();
        for (TranscriptSegment seg : segments) {
            seg.playbackStartMs = seg.startMs;
            seg.playbackEndMs = seg.endMs;
            seg.durationMs = -1;
        }
        TtsPrefetcher.triggerRescan();
    }

    /** Applies the current voice volume setting to the active playback. */
    public static void updatePlaybackVolume() {
        Utils.verifyOnMainThread();
        ttsEngine.setVolume(Settings.VOT_TRANSLATION_VOLUME.get() / 100.0f);
    }

    /** Re-applies the ducking multiplier so a Settings change takes effect immediately. */
    public static void updateOriginalAudioMultiplier() {
        Utils.verifyOnMainThread();
        if (TtsQueue.isNotEmpty() || isTestSpeaking) {
            VotOriginalVolumePatch.setAudioMultiplier(Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f);
        }
    }

    public static void reloadTranscript() {
        Utils.verifyOnMainThread();
        if (currentVideoId.isEmpty()) return;
        TtsQueue.clear();
        segments = new ArrayList<>();
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
                // across updates, so queued items remain index-stable.
                List<TranscriptSegment> fetched = TranscriptFetcher.fetch(
                        videoId,
                        updated -> {
                            Utils.verifyOnMainThread();
                            if (videoId.equals(currentVideoId) && loadLang.equals(resolveTargetLang())) {
                                TtsQueue.invalidateStale(updated);
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

        tts = new TextToSpeech(Utils.getContext(), status -> Utils.runOnMainThreadNowOrLater(() -> {
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

            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                }

                @Override
                public void onDone(String utteranceId) {
                    Utils.runOnMainThreadNowOrLater(() -> {
                        try {
                            if (utteranceId == null) return;
                            if (utteranceId.startsWith(VOT_TEST_ID_PREFIX)) {
                                final long tId = Long.parseLong(utteranceId.substring(VOT_TEST_ID_PREFIX.length()));
                                if (tId == currentTestId) isTestSpeaking = false;
                            } else if (utteranceId.startsWith(VOT_ID_PREFIX)) {
                                long id = Long.parseLong(utteranceId.substring(VOT_ID_PREFIX.length()));
                                if (id == ttsEngine.getPlaybackId()) {
                                    TtsQueue.onItemFinished();
                                }
                            }
                        } catch (Exception ex) {
                            logError(() -> "Utterance listener onDone failure", ex);
                        }
                    });
                }

                @Override
                public void onError(String utteranceId) {
                    onDone(utteranceId);
                }
            });

            ttsReady = true;
        }));
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

    /** Sends a queued item to the appropriate TTS engine. Invoked by {@link TtsQueue}. */
    static void dispatchItem(TtsQueueItem item, Runnable onDone) {
        Utils.verifyOnMainThread();
        Logger.printDebug(() -> "Dispatch: " + item);
        final float volume = Settings.VOT_TRANSLATION_VOLUME.get() / 100.0f;
        final float rate = item.assignedRate;

        if (item.isSystemTts) {
            ensureTts();
            if (!ttsReady) {
                Logger.printDebug(() -> "Native TTS not ready, skipping item");
                // Post asynchronously so a chain of synchronous failures cannot recurse
                // through the queue and blow the stack.
                Utils.runOnMainThread(onDone);
                return;
            }
            updateTtsLanguage();
            VotOriginalVolumePatch.setAudioMultiplier(Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f);
            tts.setSpeechRate(rate * VideoInformation.getPlaybackSpeed());
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
            final long id = ttsEngine.markBusy();
            // System TTS has no seekTo; completion is routed back via UtteranceProgressListener.
            tts.speak(item.seg.text, TextToSpeech.QUEUE_FLUSH, params, VOT_ID_PREFIX + id);
            return;
        }

        VotOriginalVolumePatch.setAudioMultiplier(Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f);
        byte[] cached = TtsCache.get(currentVideoId, item.segmentIndex, item.voice, item.lang, item.seg.text);
        if (cached != null) {
            final long playbackId = ttsEngine.markBusy();
            ttsEngine.play(cached, volume, rate, item.startTimeMs, playbackId, onDone);
            return;
        }

        // Edge synth ignores startTimeMs; play() will seek the resulting buffer.
        ttsEngine.speak(item.seg.text, item.voice, item.lang, volume, rate, item.startTimeMs, onDone);
    }

    /** Stops both engines without touching queue state. */
    static void stopAllEnginesForQueue() {
        Utils.verifyOnMainThread();
        isTestSpeaking = false;
        ttsEngine.stop();
        if (tts != null) tts.stop();
        VotOriginalVolumePatch.clearAudioMultiplier();
    }

    /** Stops only the System TTS engine (Edge can pause MediaPlayer in place). */
    static void stopSystemTtsForPause() {
        Utils.verifyOnMainThread();
        if (tts != null) tts.stop();
    }

    /** Triggers a re-check so the next due segment is enqueued without waiting for the next tick. */
    static void onQueueEmpty() {
        Utils.verifyOnMainThread();
        if (!isTestSpeaking) VotOriginalVolumePatch.clearAudioMultiplier();
        // Always post so we never re-enter videoTimeChanged from inside a queue callback.
        Utils.runOnMainThread(() -> {
            if (VideoState.getCurrent() == VideoState.PLAYING) {
                videoTimeChanged(VideoInformation.getVideoTime());
            }
        });
    }

    private static long getSpeechDurationMs(TranscriptSegment seg, int index, String voice, String lang) {
        long duration = seg.durationMs;
        if (duration <= 0) {
            duration = TtsCache.getDuration(currentVideoId, index, voice, lang, seg.text);
            if (duration > 0) seg.durationMs = duration;
        }
        return duration > 0 ? duration : (long) seg.text.length() * TtsEngine.ESTIMATED_MS_PER_CHAR;
    }

    /**
     * Called when the video position is programmatically seeked (e.g. SponsorBlock).
     * Drops the queue so stale audio never plays over the new position, except when the
     * seek stays inside the segment whose audio is currently playing.
     */
    public static void onVideoSeeked() {
        Logger.printDebug(() -> "onVideoSeeked");
        Utils.verifyOnMainThread();
        wasExplicitSeek = true;

        TtsQueue.clear();
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
        // Test speak preempts any active utterance.
        TtsQueue.clear();
        if (wasSameVoice) return;

        final long testId = ++currentTestId;
        isTestSpeaking = true;
        lastTestVoiceId = voiceId;

        final float volume = Settings.VOT_TRANSLATION_VOLUME.get() / 100.0f;

        if (TTS_ENGINE_SYSTEM.equals(voiceId)) {
            ensureTts();
            if (!ttsReady) {
                isTestSpeaking = false;
                return;
            }
            updateTtsLanguage();
            VotOriginalVolumePatch.setAudioMultiplier(Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f);
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
            tts.setSpeechRate(1.0f);
            ttsEngine.markBusy();
            tts.speak(getTestString(), TextToSpeech.QUEUE_FLUSH, params,
                    VOT_TEST_ID_PREFIX + testId);
            return;
        }

        final String lang = resolveTargetLang();
        byte[] cached = TtsCache.get(TEST_VIDEO_ID, TEST_SEGMENT_INDEX, voiceId, lang, getTestString());
        if (cached != null) {
            VotOriginalVolumePatch.setAudioMultiplier(Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f);
            final long id = ttsEngine.markBusy();
            ttsEngine.play(cached, volume, id, () -> updateIsTestSpeaking(testId));
            return;
        }

        VotOriginalVolumePatch.setAudioMultiplier(Settings.VOT_ORIGINAL_AUDIO_VOLUME.get() / 100.0f);
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

    /**
     * @return BCP-47 language code(pt-BR, pt-PT, en-US, etc).
     */
    static String resolveTargetLang() {
        return Settings.VOT_CAPTION_LANGUAGE.isSetToDefault()
                ? Locale.getDefault().toLanguageTag()
                : Settings.VOT_CAPTION_LANGUAGE.get();
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
