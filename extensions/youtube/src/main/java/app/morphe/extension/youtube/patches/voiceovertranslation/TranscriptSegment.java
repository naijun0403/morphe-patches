/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import androidx.annotation.NonNull;

import java.util.Objects;

public final class TranscriptSegment {
    public final String lang;
    public final String text;
    public final long startMs;
    public final long endMs;

    public volatile long playbackStartMs;
    public volatile long playbackEndMs;
    public volatile long durationMs;

    public TranscriptSegment(long startMs, long endMs, String text, String lang) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
        this.lang = lang;
        this.playbackStartMs = startMs;
        this.playbackEndMs = endMs;
        this.durationMs = -1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TranscriptSegment that = (TranscriptSegment) o;
        return startMs == that.startMs && endMs == that.endMs &&
                Objects.equals(text, that.text) && Objects.equals(lang, that.lang);
    }

    @Override
    public int hashCode() {
        return Objects.hash(startMs, endMs, text, lang);
    }

    @NonNull
    @Override
    public String toString() {
        final long duration = durationMs;
        final long playbackEnd = playbackEndMs;
        final long playbackStart = playbackStartMs;
        return "TranscriptSegment{" +
                "lang=" + lang +
                ", startMs=" + startMs +
                ", endMs=" + endMs +
                ", playbackStartMs=" + playbackStart +
                ", playbackEndMs=" + playbackEnd +
                ", durationMs=" + duration +
                ", playbackRate=" + (duration > 0 ? (duration / (float) (playbackEnd - playbackStart)) : 0) +
                ", text='" + text + '\'' +
                '}';
    }
}
