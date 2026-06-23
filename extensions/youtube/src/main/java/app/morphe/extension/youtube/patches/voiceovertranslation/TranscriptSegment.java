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
    private final String lang;
    private final String text;
    private final long startMs;
    private final long endMs;

    private volatile long playbackStartMs;
    private volatile long playbackEndMs;
    private volatile long durationMs;

    public TranscriptSegment(long startMs, long endMs, String text, String lang) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
        this.lang = lang;
        this.playbackStartMs = startMs;
        this.playbackEndMs = endMs;
        this.durationMs = -1;
    }

    public long startMs() {
        return startMs;
    }

    public long endMs() {
        return endMs;
    }

    public String text() {
        return text;
    }

    public String lang() {
        return lang;
    }

    public long playbackStartMs() {
        return playbackStartMs;
    }

    public void setPlaybackStartMs(long playbackStartMs) {
        this.playbackStartMs = playbackStartMs;
    }

    public long playbackEndMs() {
        return playbackEndMs;
    }

    public void setPlaybackEndMs(long playbackEndMs) {
        this.playbackEndMs = playbackEndMs;
    }

    public long durationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
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
        return "TranscriptSegment{" +
                "lang=" + lang +
                ", startMs=" + startMs +
                ", endMs=" + endMs +
                ", playbackStartMs=" + playbackStartMs +
                ", playbackEndMs=" + playbackEndMs +
                ", durationMs=" + durationMs +
                ", playbackRate=" + (durationMs > 0 ? (durationMs / (float) (playbackEndMs - playbackStartMs)) : 0) +
                ", text='" + text + '\'' +
                '}';
    }
}
