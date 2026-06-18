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
 * - Segments near the current time are fetched quickly.
 * - Segments further out are fetched moderately.
 * - Remaining segments are fetched slowly until the video is fully cached.
 * </pre>
 */
final class TtsPrefetcher {

    // Adaptive delay tiers based on segment distance (time) from play head.
    private static final int DISTANCE_IMMEDIATE_MS = 15_000;
    private static final int DISTANCE_NEAR_MS      = 60_000;

    private static final int DELAY_IMMEDIATE_MS  = 200;
    private static final int DELAY_NEAR_MS       = 1_000;
    private static final int DELAY_BACKGROUND_MS = 3_000;
    private static final int DELAY_IDLE_MS       = 60_000;

    // Backoff constants for handling server-side rate limits/errors.
    private static final int BACKOFF_MIN_MS      = 5_000;
    private static final int BACKOFF_MAX_MS      = 60_000; // Cap at 1 minute.
    private static final float BACKOFF_FACTOR    = 1.5f;

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
    @GuardedBy("lock")
    private static int currentBackoffMs;

    private static final TtsEngine engine = TtsEngine.INSTANCE;

    private record NextFetch(int index, int distance, TranscriptSegment seg) {}

    static void updateVideo(String videoId, List<TranscriptSegment> segments) {
        synchronized (lock) {
            currentVideoId = videoId;
            currentSegments = Collections.unmodifiableList(segments);
            currentVideoTimeMs = 0;
            if (running) {
                lock.notifyAll();
            } else {
                startBackgroundThread();
            }
        }
    }

    static void clear() {
        synchronized (lock) {
            currentVideoId = "";
            currentSegments = Collections.emptyList();
            currentVideoTimeMs = 0;
            lock.notifyAll();
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
        long lastFetchTimeMs = 0;
        String lastVideoId = "";

        while (!Thread.currentThread().isInterrupted()) {
            String videoId;
            List<TranscriptSegment> segments;
            final long timeMs;
            synchronized (lock) {
                videoId = currentVideoId;
                segments = currentSegments;
                timeMs = currentVideoTimeMs;
            }

            if (!videoId.equals(lastVideoId)) {
                lastVideoId = videoId;
                lastFetchTimeMs = 0;
            }

            if (videoId.isEmpty() || segments.isEmpty()
                    || !Settings.VOT_ENABLED.get()
                    || !Settings.VOT_SESSION_ENABLED.get()
                    || Settings.VOT_USE_NATIVE_TTS.get()) {
                if (waitOnLock(DELAY_IDLE_MS)) return;
                continue;
            }

            String voiceLang = VoiceOverTranslationPatch.resolveTargetLang();
            String voice = VoiceCatalog.resolve(voiceLang, Settings.VOT_TTS_VOICE_TYPE.get());

            if (voice == null) {
                if (waitOnLock(DELAY_IDLE_MS)) return;
                continue;
            }

            NextFetch next = findNextToFetch(videoId, segments, timeMs, voice, voiceLang);
            if (next != null) {
                final int delay;
                synchronized (lock) {
                    final long distanceMs = Math.abs(next.seg.startMs() - timeMs);
                    if (currentBackoffMs > 0) {
                        delay = currentBackoffMs;
                    } else if (distanceMs <= DISTANCE_IMMEDIATE_MS) {
                        delay = DELAY_IMMEDIATE_MS;
                    } else if (distanceMs <= DISTANCE_NEAR_MS) {
                        delay = DELAY_NEAR_MS;
                    } else {
                        delay = DELAY_BACKGROUND_MS;
                    }
                }

                long now = System.currentTimeMillis();
                long elapsed = now - lastFetchTimeMs;

                if (elapsed < delay) {
                    if (waitOnLock(delay - elapsed)) return;
                    continue;
                }

                final boolean success = fetch(videoId, segments.get(next.index),
                        next.index, segments.size(), voice, voiceLang);
                lastFetchTimeMs = System.currentTimeMillis();

                synchronized (lock) {
                    if (success) {
                        currentBackoffMs = Math.max(0, currentBackoffMs - 500);
                    } else {
                        if (currentBackoffMs == 0) currentBackoffMs = BACKOFF_MIN_MS;
                        else currentBackoffMs = (int) Math.min(BACKOFF_MAX_MS, currentBackoffMs * BACKOFF_FACTOR);
                    }
                }
            } else {
                if (waitOnLock(DELAY_IDLE_MS)) return;
            }
        }
    }


    /** @return true if interrupted (caller should stop the loop). */
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
                                             long timeMs, String voice, String lang) {
        final int segmentsSize = segments.size();
        int firstFutureIndex = segmentsSize;

        // Priority 1: Future segments, closest first.
        for (int i = 0; i < segmentsSize; i++) {
            TranscriptSegment seg = segments.get(i);
            if (seg.startMs() >= timeMs) {
                if (firstFutureIndex == segmentsSize) {
                    firstFutureIndex = i;
                }
                if (TtsCache.notCached(videoId, i, voice, lang, seg.text())) {
                    return new NextFetch(i, i - firstFutureIndex, seg);
                }
            }
        }

        // Priority 2: Past segments (for loops/seeks), closest to playhead first.
        for (int i = firstFutureIndex - 1; i >= 0; i--) {
            final TranscriptSegment seg = segments.get(i);
            if (TtsCache.notCached(videoId, i, voice, lang, seg.text())) {
                return new NextFetch(i, firstFutureIndex - i, seg);
            }
        }

        return null;
    }

    private static boolean fetch(String videoId, TranscriptSegment seg, int index,
                                 int totalSegments, String voice, String lang) {
        try {
            final long start = System.currentTimeMillis();
            final byte[] data = engine.prefetch(seg.text(), voice, lang);
            if (data.length > 0) {
                TtsCache.put(videoId, index, voice, lang, seg.text(), data);
                TtsCache.putDuration(videoId, index, voice, lang, seg.text(), TtsEngine.mp3DurationMs(data.length));
                final int textSubstringLength = 30;
                Logger.printDebug(() -> "prefetched TTS: " + videoId
                        + " segment: " + index + "/" + totalSegments + " fetchTime: "
                        + (System.currentTimeMillis() - start) + "ms text: "
                        + (seg.text().length() > textSubstringLength ? seg.text()
                        .substring(0, textSubstringLength).concat("...") : seg.text()));
                return true;
            }
            return false;
        } catch (Exception ex) {
            VoiceOverTranslationPatch.logError(() -> "Prefetch failed for segment " + index, ex);
            return false;
        }
    }
}
