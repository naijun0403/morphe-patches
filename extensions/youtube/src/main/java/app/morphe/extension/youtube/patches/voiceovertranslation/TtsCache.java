/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

final class TtsCache {

    private static final String CACHE_DIR = "vot_cache";
    private static final long MAX_CACHE_SIZE = 100 * 1024 * 1024; // 100 MB

    static boolean notCached(String videoId, int segmentIndex, String voice, String text) {
        return !getCacheFile(videoId, segmentIndex, voice, text).exists();
    }

    static byte[] get(String videoId, int segmentIndex, String voice, String text) {
        File file = getCacheFile(videoId, segmentIndex, voice, text);
        if (!file.exists()) return null;

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = fis.read(data);
            // Update last modified to keep it in LRU
            //noinspection ResultOfMethodCallIgnored
            file.setLastModified(System.currentTimeMillis());
            return read == data.length ? data : null;
        } catch (IOException e) {
            return null;
        }
    }

    static void put(String videoId, int segmentIndex, String voice, String text, byte[] data) {
        File file = getCacheFile(videoId, segmentIndex, voice, text);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) return;

        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        } catch (IOException e) {
            Logger.printException(() -> "Failed to write TTS cache", e);
        }

        Utils.runOnBackgroundThread(TtsCache::pruneCache);
    }

    private static File getCacheFile(String videoId, int segmentIndex, String voice, String text) {
        String key = videoId + "_" + segmentIndex + "_" + voice + "_" + text;
        String hash = md5(key);
        return new File(getCacheDir(), hash + ".mp3");
    }

    private static File getCacheDir() {
        File dir = new File(Utils.getContext().getCacheDir(), CACHE_DIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static void pruneCache() {
        File dir = getCacheDir();
        File[] files = dir.listFiles();
        if (files == null) return;

        long totalSize = 0;
        for (File f : files) totalSize += f.length();

        if (totalSize > MAX_CACHE_SIZE) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            for (File f : files) {
                totalSize -= f.length();
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                if (totalSize <= MAX_CACHE_SIZE * 0.8) break; // Prune down to 80%
            }
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(s.getBytes(StandardCharsets.UTF_8));
            byte[] messageDigest = digest.digest();
            StringBuilder hexString = new StringBuilder(2 * messageDigest.length);
            for (byte b : messageDigest) {
                final int val = 0xFF & b;
                if (val < 16) {
                    hexString.append('0');
                }
                hexString.append(Integer.toHexString(val));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(s.hashCode());
        }
    }
}
