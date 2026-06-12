/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory LRU cache for synthesized Edge TTS MP3 segments.
 *
 * <p>Edge TTS audio at 24 kHz / 48 kbps uses ~6 KB/s. A full hour of speech is
 * ~20 MB, so keeping up to {@value #MAX_ENTRIES} segments in memory is safe.
 */
final class TtsCache {

    private static final int MAX_ENTRIES = 1000;

    private static final LinkedHashMap<String, byte[]> cache =
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    static synchronized boolean notCached(String videoId, int segmentIndex, String voice, String text) {
        return !cache.containsKey(key(videoId, segmentIndex, voice, text));
    }

    static synchronized byte[] get(String videoId, int segmentIndex, String voice, String text) {
        return cache.get(key(videoId, segmentIndex, voice, text));
    }

    static synchronized void put(String videoId, int segmentIndex, String voice, String text, byte[] data) {
        cache.put(key(videoId, segmentIndex, voice, text), data);
    }

    private static String key(String videoId, int segmentIndex, String voice, String text) {
        return videoId + ':' + segmentIndex + ':' + voice + ':' + text.hashCode();
    }
}
