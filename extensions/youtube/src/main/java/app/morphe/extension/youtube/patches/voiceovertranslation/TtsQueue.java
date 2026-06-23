/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import java.util.ArrayDeque;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;

/**
 * FIFO queue of pending TTS utterances. Each push recomputes the speech rate of every
 * not-yet-started item so the tail stays close to its target video time.
 *
 * <p>State is touched only on the main thread; the playing-index cache below is the one
 * exception, exposed for background prefetcher reads.
 */
final class TtsQueue {

    /** Speech above this rate becomes unintelligible; we'd rather let drift grow than play
     *  faster than this. */
    private static final float MAX_RATE = 2.5f;

    private static final ArrayDeque<TtsQueueItem> items = new ArrayDeque<>();
    private static int lastEnqueuedIndex = -1;
    private static boolean paused;
    /** Volatile so the prefetcher's background thread can read the playing index lock-free. */
    private static volatile int playingSegmentIndexCache = -1;

    /** Wall-clock instant the current head started playing; 0 when no head is playing. */
    private static long headDispatchedAtWallMs;
    /** Wall-clock instant of the latest pause; 0 when not currently paused. Used to shift
     *  {@link #headDispatchedAtWallMs} forward on resume so paused time isn't counted as elapsed. */
    private static long pauseStartedAtWallMs;

    private TtsQueue() {}

    static boolean isEmpty() {
        Utils.verifyOnMainThread();
        return items.isEmpty();
    }

    static boolean isNotEmpty() {
        return !isEmpty();
    }

    static int getLastEnqueuedIndex() {
        Utils.verifyOnMainThread();
        return lastEnqueuedIndex;
    }

    /** @return Segment index of the playing head, or -1. Safe to call from any thread. */
    static int getPlayingSegmentIndex() {
        return playingSegmentIndexCache;
    }

    static void enqueue(TtsQueueItem item) {
        Utils.verifyOnMainThread();
        items.addLast(item);
        if (item.segmentIndex > lastEnqueuedIndex) lastEnqueuedIndex = item.segmentIndex;
        recomputeRates();
        Logger.printDebug(() -> "enqueue: " + item + " queueSize=" + items.size());
        ensurePlayingHead();
    }

    static void clear() {
        Utils.verifyOnMainThread();
        if (!items.isEmpty()) {
            Logger.printDebug(() -> "TtsQueue clear (size=" + items.size() + ")");
        }
        items.clear();
        lastEnqueuedIndex = -1;
        paused = false;
        headDispatchedAtWallMs = 0;
        pauseStartedAtWallMs = 0;
        refreshPlayingCache();
        // Stopping the engines fires a delayed completion for the discarded head; the
        // empty-queue guard in onItemFinished swallows it.
        VoiceOverTranslationPatch.stopAllEnginesForQueue();
    }

    /** Pauses the playing head while leaving the queue intact for resume. */
    static void pauseForVideoState() {
        Utils.verifyOnMainThread();
        paused = true;
        TtsQueueItem head = items.peekFirst();
        if (head == null) return;
        if (head.state != TtsQueueItem.State.PLAYING) return;

        if (head.isSystemTts) {
            Logger.printDebug(() -> "TtsQueue pause - stopping System TTS head");
            // System TTS has no pause; we stop it and revert the head to QUEUE.
            // The engine's delayed completion is keyed by playbackId, so bump it now so the
            // listener filters out the stale callback that would otherwise pop the head.
            TtsEngine.INSTANCE.markBusy();
            VoiceOverTranslationPatch.stopSystemTtsForPause();
            head.state = TtsQueueItem.State.QUEUED;
            headDispatchedAtWallMs = 0;
            refreshPlayingCache();
        } else {
            Logger.printDebug(() -> "TtsQueue pause - pausing Edge TTS head");
            TtsEngine.INSTANCE.pause();
            pauseStartedAtWallMs = System.currentTimeMillis();
        }
    }

    static void resumeForVideoState() {
        Utils.verifyOnMainThread();
        if (!paused) return;
        paused = false;
        TtsQueueItem head = items.peekFirst();
        if (head == null) return;
        if (head.state == TtsQueueItem.State.PLAYING && !head.isSystemTts) {
            Logger.printDebug(() -> "TtsQueue resume - Edge TTS");
            if (pauseStartedAtWallMs > 0) {
                headDispatchedAtWallMs += System.currentTimeMillis() - pauseStartedAtWallMs;
                pauseStartedAtWallMs = 0;
            }
            TtsEngine.INSTANCE.resume();
        } else {
            Logger.printDebug(() -> "TtsQueue resume - replaying head");
            pauseStartedAtWallMs = 0;
            ensurePlayingHead();
        }
    }

    /** Drops the queue if any item's text has been replaced by a fresh translation. */
    static void invalidateStale(List<TranscriptSegment> updated) {
        Utils.verifyOnMainThread();
        if (items.isEmpty()) return;
        for (TtsQueueItem item : items) {
            if (item.segmentIndex >= updated.size()) continue;
            if (!item.seg.text.equals(updated.get(item.segmentIndex).text)) {
                Logger.printDebug(() -> "TtsQueue invalidating stale item: " + item);
                clear();
                return;
            }
        }
    }

    static void onItemFinished() {
        Utils.verifyOnMainThread();
        if (items.isEmpty()) {
            // Engine completion fired after a clear() drained the queue.
            Logger.printDebug(() -> "onItemFinished on empty queue (stale)");
            return;
        }
        TtsQueueItem finished = items.pollFirst();
        Logger.printDebug(() -> "onItemFinished: " + finished + " remaining=" + items.size());
        headDispatchedAtWallMs = 0;
        refreshPlayingCache();
        if (items.isEmpty()) {
            VoiceOverTranslationPatch.onQueueEmpty();
        } else {
            recomputeRates();
            ensurePlayingHead();
        }
    }

    private static void ensurePlayingHead() {
        if (paused) return;
        TtsQueueItem head = items.peekFirst();
        if (head == null || head.state != TtsQueueItem.State.QUEUED) return;
        head.state = TtsQueueItem.State.PLAYING;
        headDispatchedAtWallMs = System.currentTimeMillis();
        refreshPlayingCache();
        VoiceOverTranslationPatch.dispatchItem(head, TtsQueue::onItemFinished);
    }

    private static void refreshPlayingCache() {
        TtsQueueItem head = items.peekFirst();
        playingSegmentIndexCache = (head != null && head.state == TtsQueueItem.State.PLAYING)
                ? head.segmentIndex : -1;
    }

    /**
     * Assigns every not-yet-playing item the single smallest rate that keeps every item
     * in the queue within its drift window. Solving globally (instead of greedily per
     * item) prevents one item under-using its budget and forcing successors to compensate.
     */
    static void recomputeRates() {
        TtsQueueItem head = items.peekFirst();
        if (head == null) return;

        final long currentVideoTimeMs = VideoInformation.getVideoTime();
        final long driftMs = Settings.VOT_MAX_QUEUE_AGE.get() * 1000L;
        final boolean headIsPlaying = head.state == TtsQueueItem.State.PLAYING;

        // T = predicted video time when the first not-yet-playing item begins playback.
        final long T = headIsPlaying
                ? currentVideoTimeMs + estimateRemainingMs(head)
                : Math.max(currentVideoTimeMs, head.seg.playbackStartMs);

        // For item i with cumulative natural duration D_i and drift-padded deadline E_i,
        // a single shared rate R must satisfy: T + D_i / R <= E_i for every i.
        // Therefore, R = max over i of D_i / (E_i - T), clamped to [1.0, MAX_RATE].
        float globalRate = 1.0f;
        long cumulativeDurMs = 0;
        boolean skip = headIsPlaying;
        for (TtsQueueItem item : items) {
            if (skip) { skip = false; continue; }
            cumulativeDurMs += Math.max(1, item.estimatedDurationMs);
            final long budgetMs = item.seg.playbackEndMs + driftMs - T;
            if (budgetMs <= 0) {
                globalRate = MAX_RATE;
                break;
            }
            final float required = cumulativeDurMs / (float) budgetMs;
            if (required > globalRate) globalRate = required;
        }
        globalRate = Math.min(MAX_RATE, Math.max(1.0f, globalRate));

        skip = headIsPlaying;
        for (TtsQueueItem item : items) {
            if (skip) { skip = false; continue; }
            item.assignedRate = globalRate;
        }
    }

    private static long estimateRemainingMs(TtsQueueItem head) {
        final long fullPlayMs = (long) (head.estimatedDurationMs
                / Math.max(1.0f, head.assignedRate));
        if (headDispatchedAtWallMs == 0) return fullPlayMs;
        final long elapsedMs = System.currentTimeMillis() - headDispatchedAtWallMs;
        return Math.max(0, fullPlayMs - elapsedMs);
    }
}
