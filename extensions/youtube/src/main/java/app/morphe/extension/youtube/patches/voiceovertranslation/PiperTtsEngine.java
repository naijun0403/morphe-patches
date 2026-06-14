/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import android.os.Build;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Manages Piper offline TTS via sherpa-onnx:
 * <ul>
 *   <li>downloads the sherpa-onnx native libraries from GitHub releases on first use</li>
 *   <li>downloads the model tarball from the sherpa-onnx GitHub release</li>
 *   <li>extracts it to the app's internal storage</li>
 *   <li>synthesises speech as PCM and returns a WAV byte array</li>
 * </ul>
 *
 * <p>Requires sherpa-onnx-android.aar (downloaded to .gradle/ by the Gradle build task) and
 * commons-compress on the classpath (for tar.bz2 extraction).
 *
 * <p>Tested against sherpa-onnx v1.10.46 AAR.
 */
final class PiperTtsEngine {

    interface DownloadListener {
        /** Called on the main thread with a value in [0, 1]. */
        void onProgress(float progress);
        /** Called on the main thread when the download and extraction are complete. */
        void onComplete();
        /** Called on the main thread if the download fails. */
        void onError(String message);
    }

    private interface ProgressMapper {
        float map(float raw);
    }

    private static final String SHERPA_ONNX_VERSION = "1.10.46";
    private static final String NATIVE_LIBS_AAR_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v" + SHERPA_ONNX_VERSION
                    + "/sherpa-onnx-" + SHERPA_ONNX_VERSION + ".aar";

    private static volatile OfflineTts activeTts;
    private static String activeLang;

    private static volatile boolean downloading;
    private static volatile String downloadingLang;
    private static volatile float downloadProgress;
    private static volatile boolean nativeLibsLoaded;
    private static volatile boolean maintenanceDone;

    static boolean isDownloaded(String lang) {
        performMaintenanceOnce();
        return isNativeLibsReady() && isVoiceModelDownloaded(lang);
    }

    static boolean isNativeLibsReady() {
        if (!new File(nativeLibsDir(), "libsherpa-onnx-jni.so").exists()) return false;
        File versionFile = new File(nativeLibsDir(), "version");
        try (FileInputStream fis = new FileInputStream(versionFile)) {
            byte[] buf = new byte[32];
            int n = fis.read(buf);
            return n > 0 && SHERPA_ONNX_VERSION.equals(new String(buf, 0, n).trim());
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isVoiceModelDownloaded(String lang) {
        PiperVoiceCatalog.Model model = PiperVoiceCatalog.forLang(lang);
        if (model == null) return false;
        return new File(modelDir(model.tarName()), ".complete").exists();
    }

    private static File findOnnxFile(File dir) {
        File[] files = dir.listFiles(f -> f.getName().endsWith(".onnx"));
        return (files != null && files.length > 0) ? files[0] : null;
    }

    static boolean isDownloading(String lang) {
        return downloading && lang.equals(downloadingLang);
    }

    static float getDownloadProgress() {
        return downloadProgress;
    }

    static void download(String lang, DownloadListener listener) {
        if (downloading) return;
        PiperVoiceCatalog.Model model = PiperVoiceCatalog.forLang(lang);
        if (model == null) {
            listener.onError("No Piper model for language: " + lang);
            return;
        }
        downloading = true;
        downloadingLang = lang;
        downloadProgress = 0f;

        boolean needsLibs = !isNativeLibsReady();
        boolean needsModel = !isVoiceModelDownloaded(lang);

        Runnable notifyProgress = () -> Utils.runOnMainThread(
                () -> listener.onProgress(downloadProgress));

        Utils.runOnBackgroundThread(() -> {
            File tmpAar = new File(modelsRootDir(), "sherpa-onnx.tmp.aar");
            File tmpTar = new File(modelsRootDir(), model.tarName() + ".tmp.tar.bz2");
            try {
                if (needsLibs && needsModel) {
                    downloadFile(NATIVE_LIBS_AAR_URL, tmpAar, raw -> raw * 0.25f, notifyProgress);
                    extractNativeLibsFromAar(tmpAar);
                    downloadProgress = 0.3f;
                    notifyProgress.run();
                    downloadFile(model.downloadUrl(), tmpTar, raw -> 0.3f + raw * 0.35f, notifyProgress);
                    extractTarBz2(tmpTar, modelsRootDir(), raw -> 0.65f + raw * 0.35f, notifyProgress);
                    markModelComplete(model);
                } else if (needsLibs) {
                    downloadFile(NATIVE_LIBS_AAR_URL, tmpAar, raw -> raw * 0.8f, notifyProgress);
                    extractNativeLibsFromAar(tmpAar);
                    downloadProgress = 1.0f;
                    notifyProgress.run();
                } else if (needsModel) {
                    downloadFile(model.downloadUrl(), tmpTar, raw -> raw * 0.5f, notifyProgress);
                    extractTarBz2(tmpTar, modelsRootDir(), raw -> 0.5f + raw * 0.5f, notifyProgress);
                    markModelComplete(model);
                }
                Utils.runOnMainThread(() -> {
                    downloading = false;
                    downloadingLang = null;
                    listener.onComplete();
                });
            } catch (Exception e) {
                Logger.printException(() -> "Piper download failed", e);
                Utils.runOnMainThread(() -> {
                    downloading = false;
                    downloadingLang = null;
                    listener.onError(e.getMessage() != null ? e.getMessage() : "Download failed");
                });
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmpAar.delete();
                //noinspection ResultOfMethodCallIgnored
                tmpTar.delete();
            }
        });
    }

    /**
     * Synthesises {@code text} for {@code lang}.
     * Must be called on a background thread. Returns a WAV byte array, or {@code null} on failure.
     */
    static synchronized byte[] synthesize(String text, String lang) {
        Utils.verifyOffMainThread();
        OfflineTts tts = ensureTts(lang);
        if (tts == null) return null;
        try {
            GeneratedAudio audio = tts.generate(text, 0, 1.0f);
            return toWav(audio.getSamples(), audio.getSampleRate());
        } catch (Exception e) {
            Logger.printException(() -> "Piper synthesis failed", e);
            return null;
        }
    }

    /** Pre-loads the ONNX model so the first synthesize() call doesn't stall for 5-10 s. */
    static void warmUp(String lang) {
        if (!isDownloaded(lang)) return;
        if (activeTts != null && lang.equals(activeLang)) return;
        Utils.runOnBackgroundThread(() -> ensureTts(lang));
    }

    @SuppressWarnings("unused")
    static void release() {
        Utils.verifyOnMainThread();
        OfflineTts t = activeTts;
        activeTts = null;
        activeLang = null;
        if (t != null) {
            try { t.release(); } catch (Exception ignored) {}
        }
    }

    private static synchronized OfflineTts ensureTts(String lang) {
        if (activeTts != null && lang.equals(activeLang)) return activeTts;

        // Release previous instance if language changed.
        if (activeTts != null) {
            try { activeTts.release(); } catch (Exception ignored) {}
            activeTts = null;
            activeLang = null;
        }

        PiperVoiceCatalog.Model model = PiperVoiceCatalog.forLang(lang);
        if (model == null) return null;

        File dir = modelDir(model.tarName());
        File modelFile = findOnnxFile(dir);
        File dataDirFile = new File(dir, "espeak-ng-data");
        if (modelFile == null || !dataDirFile.isDirectory()) return null;

        File tokensFile = new File(dir, "tokens.txt");

        try {
            loadNativeLibs();
        } catch (UnsatisfiedLinkError e) {
            Logger.printException(() -> "sherpa-onnx native library not found", e);
            return null;
        }

        try {
            activeTts = new OfflineTts(null, buildTtsConfig(modelFile, dataDirFile, tokensFile));
            activeLang = lang;
            return activeTts;
        } catch (Exception e) {
            Logger.printException(() -> "Piper init failed", e);
            return null;
        }
    }

    @SuppressWarnings("UnsafeDynamicallyLoadedCode")
    private static synchronized void loadNativeLibs() {
        if (nativeLibsLoaded) return;
        File dir = nativeLibsDir();
        System.load(new File(dir, "libonnxruntime.so").getAbsolutePath());
        System.load(new File(dir, "libsherpa-onnx-c-api.so").getAbsolutePath());
        File cxxApi = new File(dir, "libsherpa-onnx-cxx-api.so");
        if (cxxApi.length() > 0) System.load(cxxApi.getAbsolutePath());
        System.load(new File(dir, "libsherpa-onnx-jni.so").getAbsolutePath());
        nativeLibsLoaded = true;
    }

    private static OfflineTtsConfig buildTtsConfig(File modelFile, File dataDirFile, File tokensFile) {
        OfflineTtsVitsModelConfig vitsConfig = new OfflineTtsVitsModelConfig(
                modelFile.getAbsolutePath(),
                "",
                tokensFile.exists() ? tokensFile.getAbsolutePath() : "",
                dataDirFile.getAbsolutePath(),
                "",
                0.667f,
                0.8f,
                1.0f
        );
        OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig(
                vitsConfig,
                new OfflineTtsMatchaModelConfig(),
                new OfflineTtsKokoroModelConfig(),
                1,
                false,
                "cpu"
        );
        return new OfflineTtsConfig(modelConfig, "", "", 1, 1.0f);
    }

    private static void extractNativeLibsFromAar(File aarFile) throws IOException {
        String prefix = "jni/" + deviceAbi() + "/";
        File libDir = nativeLibsDir();
        //noinspection ResultOfMethodCallIgnored
        libDir.mkdirs();
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(aarFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith(prefix) && name.endsWith(".so")) {
                    File out = new File(libDir, name.substring(name.lastIndexOf('/') + 1));
                    try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out), 131_072)) {
                        byte[] buf = new byte[131_072];
                        int n;
                        while ((n = zip.read(buf)) != -1) {
                            bos.write(buf, 0, n);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
        File versionFile = new File(nativeLibsDir(), "version");
        try (FileOutputStream fos = new FileOutputStream(versionFile)) {
            fos.write(SHERPA_ONNX_VERSION.getBytes());
        }
    }

    private static void markModelComplete(PiperVoiceCatalog.Model model) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        new File(modelDir(model.tarName()), ".complete").createNewFile();
    }

    private static void performMaintenanceOnce() {
        if (maintenanceDone) return;
        maintenanceDone = true;
        Utils.runOnBackgroundThread(PiperTtsEngine::cleanStaleModels);
    }

    private static void cleanStaleModels() {
        File piperDir = modelsRootDir();
        File[] dirs = piperDir.listFiles(File::isDirectory);
        if (dirs == null) return;
        Set<String> validNames = new HashSet<>();
        for (PiperVoiceCatalog.Model m : PiperVoiceCatalog.allModels()) {
            validNames.add(m.tarName());
        }
        for (File dir : dirs) {
            if (!validNames.contains(dir.getName())) {
                deleteRecursive(dir);
            }
        }
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void downloadFile(String urlStr, File dest, ProgressMapper progress, Runnable onProgress) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        URL url = new URL(urlStr);
        HttpURLConnection conn = openConnection(url);
        long total = conn.getContentLengthLong();

        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[131_072];
            long downloaded = 0;
            long lastNotifyMs = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                downloaded += n;
                if (total > 0) {
                    downloadProgress = progress.map(downloaded / (float) total);
                    long now = System.currentTimeMillis();
                    if (now - lastNotifyMs >= 100) {
                        lastNotifyMs = now;
                        onProgress.run();
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection openConnection(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(120_000);
        conn.setInstanceFollowRedirects(true);
        conn.connect();

        // Follow manual redirects (GitHub release → CDN).
        int code = conn.getResponseCode();
        if (code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == 307 || code == 308) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            return openConnection(new URL(location));
        }
        return conn;
    }

    private static void extractTarBz2(File archive, File destRoot, ProgressMapper progress, Runnable onProgress) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        destRoot.mkdirs();
        // Track progress via compressed bytes read - avoids a costly second bzip2 pass.
        long totalBytes = archive.length();
        long[] compressedRead = {0};
        long[] lastNotifyMs = {0};

        try (FileInputStream fis = new FileInputStream(archive);
             BZip2CompressorInputStream bzip2 = new BZip2CompressorInputStream(new InputStream() {
                 @Override public int read() throws IOException { return fis.read(); }
                 @Override public int read(byte[] b, int off, int len) throws IOException {
                     int n = fis.read(b, off, len);
                     if (n > 0 && totalBytes > 0) {
                         compressedRead[0] += n;
                         downloadProgress = progress.map(compressedRead[0] / (float) totalBytes);
                         long now = System.currentTimeMillis();
                         if (now - lastNotifyMs[0] >= 100) {
                             lastNotifyMs[0] = now;
                             onProgress.run();
                         }
                     }
                     return n;
                 }
             });
             TarArchiveInputStream tar = new TarArchiveInputStream(bzip2)) {

            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                File out = new File(destRoot, entry.getName());
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                File outParent = out.getParentFile();
                if (outParent != null) {
                    //noinspection ResultOfMethodCallIgnored
                    outParent.mkdirs();
                }
                try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out), 131_072)) {
                    byte[] buf = new byte[131_072];
                    int n;
                    while ((n = tar.read(buf)) != -1) {
                        bos.write(buf, 0, n);
                    }
                }
            }
        }
    }

    /** Converts normalized float[] PCM samples to a 16-bit mono WAV byte array. */
    static byte[] toWav(float[] samples, int sampleRate) {
        int dataSize = samples.length * 2;
        ByteBuffer buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);

        buf.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        buf.putInt(36 + dataSize);
        buf.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
        buf.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        buf.putInt(16);
        buf.putShort((short) 1);           // PCM
        buf.putShort((short) 1);           // mono
        buf.putInt(sampleRate);
        buf.putInt(sampleRate * 2);     // byte rate
        buf.putShort((short) 2);           // block align
        buf.putShort((short) 16);          // bits per sample
        buf.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        buf.putInt(dataSize);

        for (float s : samples) {
            buf.putShort((short) (Math.max(-1f, Math.min(1f, s)) * 32767));
        }

        return buf.array();
    }

    private static String deviceAbi() {
        return Build.SUPPORTED_ABIS[0];
    }

    private static File nativeLibsDir() {
        return new File(Utils.getContext().getFilesDir(), "sherpa-libs/" + deviceAbi());
    }

    private static File modelsRootDir() {
        return new File(Utils.getContext().getFilesDir(), "piper");
    }

    private static File modelDir(String tarName) {
        return new File(modelsRootDir(), tarName);
    }

    private PiperTtsEngine() {}
}
