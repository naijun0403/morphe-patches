/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import androidx.annotation.GuardedBy;

import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

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

    private static final TtsEngine engine = new TtsEngine();

    static void updateVideo(String videoId, List<TranscriptSegment> segments) {
        synchronized (lock) {
            currentVideoId = videoId;
            // Wrap in an unmodifiable snapshot. The caller's list must not be mutated
            // after this point, but we make that guarantee explicit here.
            currentSegments = Collections.unmodifiableList(segments);
            currentVideoTimeMs = 0;
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
            // Only wake the thread if it is actually sleeping; updateTime can be called
            // very frequently (e.g. every 200 ms) and spurious wakeups waste CPU.
            if (waiting) {
                lock.notifyAll();
            }
        }
    }

    /**
     * Clears current state and wakes the background thread so it idles rather than
     * continuing to prefetch stale content.
     */
    static void stop() {
        synchronized (lock) {
            currentVideoId = "";
            currentSegments = Collections.emptyList();
            currentVideoTimeMs = 0;
            lock.notifyAll();
        }
    }

    // -------------------------------------------------------------------------
    // Background thread
    // -------------------------------------------------------------------------

    /**
     * Must be called with {@code lock} held.
     */
    @GuardedBy("lock")
    private static void startBackgroundThread() {
        running = true;

        Utils.runOnBackgroundThread(() -> {
            try {
                runPrefetchLoop();
            } finally {
                // Guarantee running is cleared even if an unexpected exception escapes.
                synchronized (lock) {
                    running = false;
                }
            }
        });
    }

    private static void runPrefetchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            // Snapshot mutable state under the lock so the rest of the iteration
            // works on stable values without holding the lock during I/O.
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
                if (!waitOnLock(5000)) return;
                continue;
            }

            String lang = Settings.VOT_CAPTION_LANGUAGE.get();
            String voiceLang = "auto".equals(lang) ? VoiceOverTranslationPatch.detectedSourceLang : lang;
            String voice = VoiceCatalog.resolve(voiceLang, Settings.VOT_TTS_VOICE_TYPE.get());

            if (voice == null) {
                if (!waitOnLock(2000)) return;
                continue;
            }

            final int nextIndex = findNextToFetch(videoId, segments, timeMs, voice);
            if (nextIndex >= 0) {
                fetch(videoId, segments.get(nextIndex), nextIndex, voice);
            } else {
                if (!waitOnLock(2000)) return;
            }
        }
    }

    /**
     * Waits on {@code lock} for up to {@code millis} ms.
     *
     * @return {@code true} if the thread should continue, {@code false} if it was
     *         interrupted and should exit.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean waitOnLock(long millis) {
        synchronized (lock) {
            waiting = true;
            try {
                lock.wait(millis);
                return true;
            } catch (InterruptedException ex) {
                Logger.printDebug(() -> "Prefetch thread interrupted", ex);
                Thread.currentThread().interrupt();
                return false;
            } finally {
                waiting = false;
            }
        }
    }

    private static int findNextToFetch(String videoId, List<TranscriptSegment> segments,
                                       long timeMs, String voice) {
        final int segmentsSize = segments.size();
        int currentIndex = segmentsSize; // index of the first future segment

        // Priority 1: Future segments, closest first.
        // Track currentIndex in the same pass to avoid re-scanning for Priority 2.
        for (int i = 0; i < segmentsSize; i++) {
            final TranscriptSegment seg = segments.get(i);
            if (seg.startMs() >= timeMs) {
                if (currentIndex == segmentsSize) {
                    currentIndex = i; // record boundary on first encounter
                }
                if (!TtsCache.exists(videoId, i, voice, seg.text())) {
                    return i;
                }
            }
        }

        // Priority 2: Past segments (for loops/seeks), closest to current time first.
        for (int i = currentIndex - 1; i >= 0; i--) {
            final TranscriptSegment seg = segments.get(i);
            if (!TtsCache.exists(videoId, i, voice, seg.text())) {
                return i;
            }
        }

        return -1;
    }

    private static void fetch(String videoId, TranscriptSegment seg, int index, String voice) {
        try {
            // Synthesize at 1.0x speed for the cache. Playback rate is applied at render time.
            final byte[] data = engine.synthesize(seg.text(), voice, 1.0f);
            if (data.length > 0) {
                TtsCache.put(videoId, index, voice, seg.text(), data);
                Logger.printDebug(() -> "Prefetched segment " + index + " for " + videoId);
            }
        } catch (Exception e) {
            Logger.printException(() -> "Prefetch failed for segment " + index, e);
            // Back off before the next attempt to avoid hammering a failing endpoint.
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                // Restore the interrupt flag so the loop condition picks it up on the
                // next iteration rather than silently swallowing the shutdown signal.
                Thread.currentThread().interrupt();
            }
        }
    }
}
