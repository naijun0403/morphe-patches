/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import androidx.annotation.NonNull;

/**
 * Single utterance owned by {@link TtsQueue}. Mutable state is touched only on the main thread.
 */
final class TtsQueueItem {

    enum State { QUEUED, PLAYING }

    final int segmentIndex;
    final TranscriptSegment seg;
    final String voice;
    final String lang;
    final boolean isSystemTts;
    /** Natural (1.0x) duration; exact from cache or estimated from char count. */
    final long estimatedDurationMs;

    /** Recomputed on each enqueue until the item leaves the QUEUED state. */
    float assignedRate;
    /** Non-zero only for the first item enqueued after a seek into a segment. */
    long startTimeMs;
    State state;

    TtsQueueItem(int segmentIndex, TranscriptSegment seg, String voice, String lang,
                 boolean isSystemTts, long estimatedDurationMs, long startTimeMs) {
        this.segmentIndex = segmentIndex;
        this.seg = seg;
        this.voice = voice;
        this.lang = lang;
        this.isSystemTts = isSystemTts;
        this.estimatedDurationMs = estimatedDurationMs;
        this.startTimeMs = startTimeMs;
        this.assignedRate = 1.0f;
        this.state = State.QUEUED;
    }

    @NonNull
    @Override
    public String toString() {
        return "TtsQueueItem{idx=" + segmentIndex
                + ", state=" + state
                + ", rate=" + assignedRate
                + ", startTimeMs=" + startTimeMs
                + ", estDur=" + estimatedDurationMs
                + ", seg=" + seg + "}";
    }
}
