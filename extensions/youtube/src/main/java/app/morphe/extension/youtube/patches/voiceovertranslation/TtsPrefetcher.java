/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * <pre>
 * Background prefetcher for Edge TTS audio segments.
 *
 * Uses an adaptive throttling strategy:
 * - Segments near the current time are fetched quickly (100ms delay).
 * - Segments further out are fetched moderately (500ms delay).
 * - Remaining segments are fetched slowly (1500ms delay) until the video is fully cached.
 * </pre>
 */
final class TtsPrefetcher {

    private static final Object lock = new Object();

    @GuardedBy("lock")
    private static String currentVideoId = "";
    @GuardedBy("lock")
    private static List<TranscriptSegment> currentSegments = Collections.emptyList();
    @GuardedBy("lock")
    private static long currentVideoTimeMs;
    @GuardedBy("lock")
    private static boolean running;
    @GuardedBy("lock")
    private static boolean waiting;

    private static final TtsEngine engine = TtsEngine.INSTANCE;

    // Adaptive delay tiers based on segment distance from playhead.
    private static final int DISTANCE_IMMEDIATE = 10;
    private static final int DISTANCE_NEAR      = 50;

    private static final int DELAY_IMMEDIATE_MS = 200;
    private static final int DELAY_NEAR_MS      = 600;
    private static final int DELAY_BACKGROUND_MS = 1500;
    private static final int DELAY_IDLE_MS       = 2000;

    // Backoff constants for handling server-side rate limits/errors.
    private static final int BACKOFF_MIN_MS      = 5000;
    private static final int BACKOFF_MAX_MS      = 60_000; // Cap at 1 minute.
    private static final float BACKOFF_FACTOR    = 1.5f;

    private static int currentBackoffMs = 0;

    private record NextFetch(int index, int distance) {}

    static void updateVideo(String videoId, List<TranscriptSegment> segments) {
        synchronized (lock) {
            currentVideoId = videoId;
            currentSegments = Collections.unmodifiableList(segments);
            currentVideoTimeMs = 0;
            // Reset backoff when starting a new video.
            currentBackoffMs = 0;
            if (running) {
                lock.notifyAll();
            } else {
                startBackgroundThread();
            }
        }
    }

    static void updateTime(long timeMs) {
        synchronized (lock) {
            currentVideoTimeMs = timeMs;
            if (waiting) {
                lock.notifyAll();
            }
        }
    }

    @GuardedBy("lock")
    private static void startBackgroundThread() {
        running = true;
        Utils.runOnBackgroundThread(() -> {
            try {
                runPrefetchLoop();
            } finally {
                synchronized (lock) {
                    running = false;
                }
            }
        });
    }

    private static void runPrefetchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            String videoId;
            List<TranscriptSegment> segments;
            final long timeMs;
            synchronized (lock) {
                videoId  = currentVideoId;
                segments = currentSegments;
                timeMs   = currentVideoTimeMs;
            }

            if (videoId.isEmpty() || segments.isEmpty()
                    || !Settings.VOT_ENABLED.get()
                    || Settings.VOT_USE_NATIVE_TTS.get()) {
                if (waitOnLock(DELAY_IDLE_MS)) return;
                continue;
            }

            String lang = Settings.VOT_CAPTION_LANGUAGE.get();
            String voiceLang = "app".equals(lang) ? VoiceOverTranslationPatch.resolveTargetLang() : lang;
            String voice = VoiceCatalog.resolve(voiceLang, Settings.VOT_TTS_VOICE_TYPE.get());

            if (voice == null) {
                if (!Settings.VOT_USE_NATIVE_TTS.get()
                        && VoiceOverTranslationPatch.TTS_ENGINE_PIPER.equals(
                                Settings.VOT_TTS_VOICE_TYPE.get())
                        && PiperTtsEngine.isDownloaded(voiceLang)) {
                    NextFetch next = findNextToFetch(videoId, segments, timeMs,
                            VoiceOverTranslationPatch.TTS_ENGINE_PIPER);
                    if (next != null) {
                        boolean ok = fetchPiper(videoId, segments.get(next.index),
                                next.index, voiceLang);
                        if (waitOnLock(ok ? DELAY_BACKGROUND_MS : BACKOFF_MIN_MS)) return;
                        continue;
                    }
                }
                if (waitOnLock(DELAY_IDLE_MS)) return;
                continue;
            }

            NextFetch next = findNextToFetch(videoId, segments, timeMs, voice);
            if (next != null) {
                final boolean success = fetch(videoId, segments.get(next.index),
                        next.index, segments.size(), voice);

                final int delay;
                if (success) {
                    // Success: use tiered delay and gradually reduce backoff.
                    if (next.distance <= DISTANCE_IMMEDIATE) delay = DELAY_IMMEDIATE_MS;
                    else if (next.distance <= DISTANCE_NEAR) delay = DELAY_NEAR_MS;
                    else delay = DELAY_BACKGROUND_MS;

                    currentBackoffMs = Math.max(0, currentBackoffMs - 500);
                } else {
                    // Failure: Apply exponential backoff.
                    if (currentBackoffMs == 0) currentBackoffMs = BACKOFF_MIN_MS;
                    else currentBackoffMs = (int) Math.min(BACKOFF_MAX_MS, currentBackoffMs * BACKOFF_FACTOR);
                    delay = currentBackoffMs;
                }

                if (waitOnLock(delay)) return;
            } else {
                if (waitOnLock(DELAY_IDLE_MS)) return;
            }
        }
    }


    /**
     * @return false, if the wait was interrupted..
     */
    private static boolean waitOnLock(long millis) {
        synchronized (lock) {
            waiting = true;
            try {
                lock.wait(millis);
                return false;
            } catch (InterruptedException ex) {
                VoiceOverTranslationPatch.logError(() -> "Prefetch thread interrupted", ex);
                Thread.currentThread().interrupt();
                return true;
            } finally {
                waiting = false;
            }
        }
    }

    @Nullable
    private static NextFetch findNextToFetch(String videoId, List<TranscriptSegment> segments,
                                             long timeMs, String voice) {
        final int segmentsSize = segments.size();
        int firstFutureIndex = segmentsSize;

        // Priority 1: Future segments, closest first.
        for (int i = 0; i < segmentsSize; i++) {
            final TranscriptSegment seg = segments.get(i);
            if (seg.startMs() >= timeMs) {
                if (firstFutureIndex == segmentsSize) {
                    firstFutureIndex = i;
                }
                if (TtsCache.notCached(videoId, i, voice, seg.text())) {
                    return new NextFetch(i, i - firstFutureIndex);
                }
            }
        }

        // Priority 2: Past segments (for loops/seeks), closest to playhead first.
        for (int i = firstFutureIndex - 1; i >= 0; i--) {
            final TranscriptSegment seg = segments.get(i);
            if (TtsCache.notCached(videoId, i, voice, seg.text())) {
                return new NextFetch(i, firstFutureIndex - i);
            }
        }

        return null;
    }

    private static boolean fetchPiper(String videoId, TranscriptSegment seg, int index,
                                      String lang) {
        try {
            byte[] data = PiperTtsEngine.synthesize(seg.text(), lang);
            if (data != null && data.length > 0) {
                TtsCache.put(videoId, index, VoiceOverTranslationPatch.TTS_ENGINE_PIPER,
                        seg.text(), data);
                Logger.printDebug(() -> "Prefetched Piper segment: " + index);
                return true;
            }
            return false;
        } catch (Exception ex) {
            VoiceOverTranslationPatch.logError(() -> "Piper prefetch failed for segment " + index, ex);
            return false;
        }
    }

    private static boolean fetch(String videoId, TranscriptSegment seg, int index,
                                 int totalSegments, String voice) {
        try {
            final byte[] data = engine.prefetch(seg.text(), voice);
            if (data.length > 0) {
                TtsCache.put(videoId, index, voice, seg.text(), data);
                Logger.printDebug(() -> "Prefetched TTS: " + videoId
                        + " segment: " + index + "/" + totalSegments + " text: " + seg.text());
                return true;
            }
            return false;
        } catch (Exception ex) {
            VoiceOverTranslationPatch.logError(() -> "Prefetch failed for segment " + index, ex);
            return false;
        }
    }
}
