/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Translates transcript segments via the configured translation service.
 */
final class TranscriptTranslator {

    private static final String TRANSLATE_URL =
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t&tl=";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    // Batches are built by character budget rather than segment count, so request
    // sizes stay uniform regardless of how long the merged sentences are.
    private static final int MAX_BATCH_CHARS = 4_000;

    // Same as arrays.xml value
    public static final String TRANSLATION_SERVICE_GOOGLE = "google";
    // Same as arrays.xml value
    public static final String TRANSLATION_SERVICE_MY_MEMORY = "mymemory";

    // Set to true at the start of each translate() call so the first batch failure per
    // video is reported via printException (visible to the user), while subsequent batch
    // failures in the same session are downgraded to debug to avoid toast spam.
    private static volatile boolean reportNextTranslationError;

    private static final AtomicLong currentSessionId = new AtomicLong();

    /**
     * Progressive translation. The first batch is translated synchronously so the returned
     * list is immediately usable for playback; remaining batches are translated on background
     * threads and published through {@code onUpdate} (called once per completed batch with a
     * full snapshot of the segment list). Failed batches keep their original text.
     *
     * <p>Timings and list size never change between updates - only segment text - so callers
     * may keep indexing into the list across snapshots.
     */
    static List<TranscriptSegment> translate(List<TranscriptSegment> segments,
                                             String targetLang,
                                             Consumer<List<TranscriptSegment>> onUpdate,
                                             BooleanSupplier cancelled) {
        Utils.verifyOffMainThread();
        if (segments.isEmpty()) return segments;
        final long sessionId = currentSessionId.incrementAndGet();
        reportNextTranslationError = true;

        List<TranscriptSegment> working = new ArrayList<>(segments);
        Handler mainHandler = new Handler(Looper.getMainLooper());

        int i = 0;
        while (i < segments.size()) {
            if (sessionId != currentSessionId.get()) return working;

            if (cancelled != null) {
                FutureTask<Boolean> task = new FutureTask<>(cancelled::getAsBoolean);
                mainHandler.post(task);
                try {
                    if (!task.get()) return working;
                } catch (ExecutionException | InterruptedException e) {
                    return working;
                }
            }

            List<TranscriptSegment> batch = new ArrayList<>();
            int batchChars = 0;
            int batchStart = i;
            while (i < segments.size()) {
                TranscriptSegment s = segments.get(i);
                final int len = s.text().length() + 1;
                if (!batch.isEmpty() && batchChars + len > MAX_BATCH_CHARS) break;
                batch.add(s);
                batchChars += len;
                i++;
            }

            try {
                List<String> translated = translateBatch(batch, targetLang);
                if (translated != null) {
                    for (int j = 0, limit = Math.min(batch.size(), translated.size()); j < limit; j++) {
                        TranscriptSegment orig = batch.get(j);
                        working.set(batchStart + j, new TranscriptSegment(
                                orig.startMs(), orig.endMs(), translated.get(j)));
                    }
                    if (onUpdate != null) {
                        List<TranscriptSegment> snapshot = new ArrayList<>(working);
                        mainHandler.post(() -> onUpdate.accept(snapshot));
                    }
                }
            } catch (Exception ex) {
                if (reportNextTranslationError) {
                    reportNextTranslationError = false;
                    VoiceOverTranslationPatch.logError(() -> "Translation failed", ex);
                }
            }
        }
        return working;
    }

    private static List<List<TranscriptSegment>> splitByCharBudget(List<TranscriptSegment> segments, int maxChars) {
        List<List<TranscriptSegment>> batches = new ArrayList<>();
        List<TranscriptSegment> batch = new ArrayList<>(segments.size());
        int chars = 0;
        for (TranscriptSegment seg : segments) {
            final int len = seg.text().length() + 1;
            if (!batch.isEmpty() && chars + len > maxChars) {
                batches.add(batch);
                batch = new ArrayList<>();
                chars = 0;
            }
            batch.add(seg);
            chars += len;
        }
        if (!batch.isEmpty()) batches.add(batch);
        return batches;
    }

    private static List<String> translateBatch(List<TranscriptSegment> segments, String targetLang) throws Exception {
        return Settings.VOT_TRANSLATION_SERVICE.get().equals(TRANSLATION_SERVICE_MY_MEMORY)
                ? translateBatchMyMemory(segments, targetLang)
                : translateBatchGoogle(segments, targetLang);
    }

    private static List<String> translateBatchGoogle(
            List<TranscriptSegment> segments, String targetLang) throws Exception {
        Utils.verifyOffMainThread();
        Logger.printDebug(() -> "Google translation starting: " + targetLang);

        StringBuilder joined = new StringBuilder();
        for (TranscriptSegment seg : segments) {
            if (joined.length() > 0) joined.append('\n');
            joined.append(seg.text());
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(TRANSLATE_URL + targetLang).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setDoOutput(true);

        //noinspection CharsetObjectCanBeUsed
        byte[] bodyBytes = ("q=" + URLEncoder.encode(joined.toString(), StandardCharsets.UTF_8.name()))
                .getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bodyBytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }

        final int code = conn.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);

        // Response: [[["translated","original",...],...],null,"src_lang",...]
        // Concatenate sentence translations; newline separators from joined input are preserved.
        JSONArray sentences = new JSONArray(Requester.parseString(conn)).getJSONArray(0);
        StringBuilder translatedJoined = new StringBuilder();
        for (int i = 0, length = sentences.length(); i < length; i++) {
            final int iFinal = i;
            Logger.printDebug(() -> "Translating batch: " + (iFinal + 1) + "/" + length);
            translatedJoined.append(sentences.getJSONArray(i).getString(0));
        }
        Logger.printDebug(() -> "Google translation complete: " + targetLang);
        return Arrays.asList(translatedJoined.toString().split("\n", -1));
    }

    // MyMemory limits q to 500 bytes per request.
    private static final int MYMEMORY_MAX_CHARS = 450;

    private static List<String> translateBatchMyMemory(
            List<TranscriptSegment> segments, String targetLang) throws Exception {
        Logger.printDebug(() -> "MyMemory translation starting: " + targetLang);

        // Re-split into sub-batches that fit within the 500-byte request limit.
        List<List<TranscriptSegment>> subBatches = splitByCharBudget(segments, MYMEMORY_MAX_CHARS);
        List<String> results = new ArrayList<>(segments.size());
        int i = 0;
        final int subBatchesSize = subBatches.size();
        for (List<TranscriptSegment> sub : subBatches) {
            final int iFinal = i++;
            Logger.printDebug(() -> "Translating batch: " + iFinal + "/" + subBatchesSize);
            results.addAll(translateMyMemoryRequest(sub, targetLang));
        }
        Logger.printDebug(() -> "MyMemory translation complete: " + targetLang);
        return results;
    }

    private static List<String> translateMyMemoryRequest(
            List<TranscriptSegment> segments, String targetLang) throws Exception {
        Utils.verifyOffMainThread();

        StringBuilder joined = new StringBuilder();
        for (TranscriptSegment seg : segments) {
            if (joined.length() > 0) joined.append('\n');
            joined.append(seg.text());
        }

        String source = TranscriptFetcher.lastSourceLang;
        //noinspection CharsetObjectCanBeUsed
        String encoded = URLEncoder.encode(joined.toString(), StandardCharsets.UTF_8.name());

        String email = Settings.VOT_MYMEMORY_EMAIL.get();
        //noinspection CharsetObjectCanBeUsed
        String emailParam = email.isEmpty() ? "" : "&de=" + URLEncoder.encode(email, StandardCharsets.UTF_8.name());
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://api.mymemory.translated.net/get?q=" + encoded + "&langpair=" + source + "|" + targetLang + emailParam)
                .openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");

        final int httpCode = conn.getResponseCode();
        if (httpCode != 200) throw new Exception("MyMemory HTTP status: " + httpCode);

        // Response: {"responseStatus": 200, "responseData": {"translatedText": "..."}}
        JSONObject json = new JSONObject(Requester.parseString(conn));
        final int responseStatus = json.optInt("responseStatus", 200);
        if (responseStatus != 200) throw new Exception("MyMemory error " + responseStatus
                + ": " + json.optString("responseDetails", "unknown error"));

        String translation = json.getJSONObject("responseData").getString("translatedText");
        return Arrays.asList(translation.split("\n", -1));
    }
}
