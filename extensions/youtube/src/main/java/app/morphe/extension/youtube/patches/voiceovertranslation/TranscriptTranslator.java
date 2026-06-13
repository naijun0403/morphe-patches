/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import androidx.annotation.Nullable;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    // Concurrent requests to the translate endpoint. Keep modest to avoid rate limiting.
    private static final int PARALLEL_REQUESTS = 4;

    // Set to true at the start of each translate() call so the first batch failure per
    // video is reported via printException (visible to the user), while subsequent batch
    // failures in the same session are downgraded to debug to avoid toast spam.
    private static volatile boolean reportNextTranslationError;

    /**
     * Progressive translation. The first batch is translated synchronously so the returned
     * list is immediately usable for playback; remaining batches are translated on background
     * threads and published through {@code onUpdate} (called once per completed batch with a
     * full snapshot of the segment list). Failed batches keep their original text.
     *
     * <p>Timings and list size never change between updates - only segment text - so callers
     * may keep indexing into the list across snapshots.
     */
    static List<TranscriptSegment> translate(List<TranscriptSegment> segments, String targetLang,
                                             Consumer<List<TranscriptSegment>> onUpdate,
                                             BooleanSupplier cancelled) {
        if (segments.isEmpty()) return segments;

        List<List<TranscriptSegment>> batches = splitByCharBudget(segments);
        reportNextTranslationError = true;

        // Working copy that accumulates translated batches over the original text.
        List<TranscriptSegment> working = new ArrayList<>(segments);

        int[] offsets = new int[batches.size()];
        for (int b = 1, batchCount = batches.size(); b < batchCount; b++) {
            offsets[b] = offsets[b - 1] + batches.get(b - 1).size();
        }

        applyBatch(working, batches.get(0), 0, translateBatchSafe(batches.get(0), targetLang));
        if (batches.size() == 1) return working;

        // Snapshot to return before background batches start mutating the working copy.
        List<TranscriptSegment> initial = new ArrayList<>(working);

        //noinspection resource
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(PARALLEL_REQUESTS, batches.size() - 1));
        for (int b = 1, batchCount = batches.size(); b < batchCount; b++) {
            final int batchIndex = b;
            pool.execute(() -> {
                // Skip remaining work when the result is no longer needed (video changed).
                if (cancelled != null && cancelled.getAsBoolean()) return;
                List<String> translated = translateBatchSafe(batches.get(batchIndex), targetLang);
                List<TranscriptSegment> snapshot;
                synchronized (working) {
                    applyBatch(working, batches.get(batchIndex), offsets[batchIndex], translated);
                    snapshot = new ArrayList<>(working);
                }
                if (onUpdate != null) onUpdate.accept(snapshot);
            });
        }
        // Graceful shutdown - queued batches still run, the pool exits when they finish.
        pool.shutdown();

        return initial;
    }

    /**
     * Writes translated text over the batch's slots in {@code target}, preserving timings.
     * A {@code null} translation (failed batch) leaves the original text in place.
     */
    private static void applyBatch(List<TranscriptSegment> target, List<TranscriptSegment> batch,
                                   int offset, @Nullable List<String> translated) {
        if (translated == null) return;
        final int limit = Math.min(batch.size(), translated.size());
        if (translated.size() != batch.size()) {
            Logger.printDebug(() -> "Line count mismatch - expected "
                    + batch.size() + ", got " + translated.size() + "; last "
                    + (batch.size() - limit) + " segment(s) keep original text");
        }
        for (int j = 0; j < limit; j++) {
            TranscriptSegment orig = batch.get(j);
            target.set(offset + j,
                    new TranscriptSegment(orig.startMs(), orig.endMs(), translated.get(j)));
        }
    }

    @Nullable
    private static List<String> translateBatchSafe(List<TranscriptSegment> batch, String targetLang) {
        try {
            return translateBatch(batch, targetLang);
        } catch (Exception ex) {
            if (reportNextTranslationError) {
                reportNextTranslationError = false;
                Logger.printException(() -> "Translation failed: " + ex.getMessage(), ex);
            } else {
                Logger.printDebug(() -> "Batch failed: " + ex.getMessage());
            }
            return null;
        }
    }

    private static List<List<TranscriptSegment>> splitByCharBudget(List<TranscriptSegment> segments) {
        return splitByCharBudget(segments, MAX_BATCH_CHARS);
    }

    private static List<List<TranscriptSegment>> splitByCharBudget(List<TranscriptSegment> segments, int maxChars) {
        List<List<TranscriptSegment>> batches = new ArrayList<>();
        List<TranscriptSegment> batch = new ArrayList<>();
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
        return "mymemory".equals(Settings.VOT_TRANSLATION_SERVICE.get())
                ? translateBatchMyMemory(segments, targetLang)
                : translateBatchGoogle(segments, targetLang);
    }

    private static List<String> translateBatchGoogle(List<TranscriptSegment> segments, String targetLang) throws Exception {
        Utils.verifyOffMainThread();

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
        for (int i = 0; i < sentences.length(); i++) {
            translatedJoined.append(sentences.getJSONArray(i).getString(0));
        }

        return Arrays.asList(translatedJoined.toString().split("\n", -1));
    }

    // MyMemory limits q to 500 bytes per request.
    private static final int MYMEMORY_MAX_CHARS = 450;

    private static List<String> translateBatchMyMemory(List<TranscriptSegment> segments, String targetLang) throws Exception {
        // Re-split into sub-batches that fit within the 500-byte request limit.
        List<List<TranscriptSegment>> subBatches = splitByCharBudget(segments, MYMEMORY_MAX_CHARS);
        List<String> results = new ArrayList<>(segments.size());
        for (List<TranscriptSegment> sub : subBatches) {
            results.addAll(translateMyMemoryRequest(sub, targetLang));
        }
        return results;
    }

    private static List<String> translateMyMemoryRequest(List<TranscriptSegment> segments, String targetLang) throws Exception {
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
        if (httpCode != 200) throw new Exception("HTTP " + httpCode + " from MyMemory");

        // Response: {"responseStatus": 200, "responseData": {"translatedText": "..."}}
        JSONObject json = new JSONObject(Requester.parseString(conn));
        final int responseStatus = json.optInt("responseStatus", 200);
        if (responseStatus != 200) throw new Exception("MyMemory error " + responseStatus
                + ": " + json.optString("responseDetails", "unknown error"));

        String translation = json.getJSONObject("responseData").getString("translatedText");
        return Arrays.asList(translation.split("\n", -1));
    }
}
