package app.morphe.extension.youtube.patches.voiceovertranslation;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * In-memory LRU cache for synthesized Edge TTS MP3 segments.
 *
 * <p>Edge TTS audio at 24 kHz / 48 kbps uses ~6 KB/s. A full hour of speech is
 * ~20 MB, so keeping a few thousand sentences in memory is safe.
 *
 * <p>Test voice samples are also persisted to disk so they survive app restarts
 * and do not require re-synthesis on subsequent sessions.
 */
final class TtsCache {

    private static final Map<String, byte[]> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(1000));

    private static final String TEST_SAMPLES_DIR = "vot_voice_samples";

    static synchronized boolean notCached(String videoId, int segmentIndex, String voice, String text) {
        return !cache.containsKey(key(videoId, segmentIndex, voice, text));
    }

    static synchronized byte[] get(String videoId, int segmentIndex, String voice, String text) {
        return cache.get(key(videoId, segmentIndex, voice, text));
    }

    static synchronized void put(String videoId, int segmentIndex, String voice, String text, byte[] data) {
        cache.put(key(videoId, segmentIndex, voice, text), data);
    }

    static byte[] getTestSampleFromDisk(String voiceId) {
        try {
            File file = testSampleFile(voiceId);
            if (!file.exists()) return null;
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] data = new byte[(int) file.length()];
                //noinspection ResultOfMethodCallIgnored
                fis.read(data);
                return data;
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Disk read failed: " + voiceId);
            return null;
        }
    }

    static void putTestSampleToDisk(String voiceId, byte[] data) {
        try {
            File file = testSampleFile(voiceId);
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                Logger.printDebug(() -> "Failed to create cache directory");
                return;
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Disk write failed: " + voiceId);
        }
    }

    private static File testSampleFile(String voiceId) {
        Context context = Utils.getContext();
        return new File(context.getCacheDir(), TEST_SAMPLES_DIR + File.separator + voiceId);
    }

    private static String key(String videoId, int segmentIndex, String voice, String text) {
        return videoId + ':' + segmentIndex + ':' + voice + ':' + text;
    }
}
