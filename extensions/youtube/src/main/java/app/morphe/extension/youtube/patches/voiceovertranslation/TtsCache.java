package app.morphe.extension.youtube.patches.voiceovertranslation;

import java.util.Collections;
import java.util.Map;

import app.morphe.extension.shared.Utils;

/**
 * In-memory LRU cache for synthesized Edge TTS MP3 segments.
 *
 * <p>Edge TTS audio at 24 kHz / 48 kbps uses ~6 KB/s. A full hour of speech is
 * ~20 MB, so keeping a few thousand sentences in memory is safe.
 */
final class TtsCache {

    private static final Map<String, byte[]> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(1000));

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
        return videoId + ':' + segmentIndex + ':' + voice + ':' + text;
    }
}
