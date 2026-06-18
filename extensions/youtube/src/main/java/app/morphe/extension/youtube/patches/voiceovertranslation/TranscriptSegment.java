/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import androidx.annotation.NonNull;

record TranscriptSegment(long startMs, long endMs, String text, String lang) {
    @NonNull
    @Override
    public String toString() {
        return "TranscriptSegment{" +
                "startMs=" + startMs +
                ", endMs=" + endMs +
                ", text='" + text + '\'' +
                ", lang='" + lang + '\'' +
                '}';
    }
}
