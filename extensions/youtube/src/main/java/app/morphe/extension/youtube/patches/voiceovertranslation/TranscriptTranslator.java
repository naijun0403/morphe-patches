/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import android.os.Handler;
import android.os.Looper;

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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
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
    // Delay between consecutive background batches to reduce IP rate-limit pressure.
    private static final int GOOGLE_INTER_BATCH_DELAY_MS = 500;
    // OpenRouter LLM inference can take longer than the shared read timeout.
    private static final int OPENROUTER_READ_TIMEOUT_MS = 30_000;
    // MyMemory enforces a per-minute request rate; a longer pause keeps us well under it.
    private static final int MYMEMORY_INTER_BATCH_DELAY_MS = 2_000;
    // Same as arrays.xml value
    public static final String TRANSLATION_SERVICE_GOOGLE = "google";
    // Same as arrays.xml value
    public static final String TRANSLATION_SERVICE_MY_MEMORY = "mymemory";
    // Same as arrays.xml value
    public static final String TRANSLATION_SERVICE_OPENROUTER = "openrouter";

    // Set to true at the start of each translate() call so the first batch failure per
    // video is reported via printException (visible to the user), while subsequent batch
    // failures in the same session are downgraded to debug to avoid toast spam.
    private static volatile boolean reportNextTranslationError;
    // Set to true when any batch returns HTTP 429. Remaining batches are skipped because
    // all subsequent requests will fail the same way until the quota/rate-limit window resets.
    private static volatile boolean abortTranslation;

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
        if (segments.isEmpty()) return segments;
        Utils.verifyOffMainThread();

        final boolean isMyMemory = Settings.VOT_TRANSLATION_SERVICE.get().equals(TRANSLATION_SERVICE_MY_MEMORY);
        List<List<TranscriptSegment>> batches = splitByCharBudget(segments,
                isMyMemory ? MYMEMORY_MAX_CHARS : MAX_BATCH_CHARS);
        reportNextTranslationError = true;
        abortTranslation = false;

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

        final int batchDelay = isMyMemory ? MYMEMORY_INTER_BATCH_DELAY_MS : GOOGLE_INTER_BATCH_DELAY_MS;
        Handler mainHandler = new Handler(Looper.getMainLooper());
        for (int batchIndex = 1, batchCount = batches.size(); batchIndex < batchCount; batchIndex++) {
            List<String> translated = translateBatchSafe(batches.get(batchIndex), targetLang);
            if (abortTranslation) break;
            List<TranscriptSegment> snapshot;

            applyBatch(working, batches.get(batchIndex), offsets[batchIndex], translated);
            snapshot = new ArrayList<>(working);
            if (onUpdate != null) mainHandler.post(() -> onUpdate.accept(snapshot));

            // Skip remaining work when the result is no longer needed (video changed).
            if (cancelled != null) {
                FutureTask<Boolean> cancelCheck = new FutureTask<>(cancelled::getAsBoolean);
                mainHandler.post(cancelCheck);
                try {
                    if (cancelCheck.get()) {
                        Logger.printDebug(() -> "translate batch canceled for: " + targetLang);
                        return initial;
                    }
                } catch (ExecutionException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            try {
                Thread.sleep(batchDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return initial;
            }
        }

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
            Logger.printDebug(() -> "Line count mismatch - expected: "
                    + batch.size() + ", got: " + translated.size() + "; last: "
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
            String msg = ex.getMessage();
            if (msg != null && msg.contains("429")) abortTranslation = true;
            if (reportNextTranslationError) {
                reportNextTranslationError = false;
                VoiceOverTranslationPatch.logError(() -> "Translation failed: " + msg, ex);
            } else {
                Logger.printDebug(() -> "Batch failed", ex);
            }
            return null;
        }
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
        String service = Settings.VOT_TRANSLATION_SERVICE.get();
        if (service.equals(TRANSLATION_SERVICE_MY_MEMORY)) return translateBatchMyMemory(segments, targetLang);
        if (service.equals(TRANSLATION_SERVICE_OPENROUTER)) return translateBatchOpenRouter(segments, targetLang);
        return translateBatchGoogle(segments, targetLang);
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
        if (code != 200) {
            VoiceOverTranslationPatch.notifyHttpError(code);
            throw new Exception("HTTP " + code);
        }

        // Response: [[["translated","original",...],...],null,"src_lang",...]
        // Concatenate sentence translations; newline separators from joined input are preserved.
        JSONArray sentences = new JSONArray(Requester.parseString(conn)).getJSONArray(0);
        StringBuilder translatedJoined = new StringBuilder();
        for (int i = 0, length = sentences.length(); i < length; i++) {
            translatedJoined.append(sentences.getJSONArray(i).getString(0));
        }
        Logger.printDebug(() -> "Google translation complete: " + targetLang);
        return Arrays.asList(translatedJoined.toString().split("\n", -1));
    }

    // MyMemory limits q to 500 bytes per request.
    private static final int MYMEMORY_MAX_CHARS = 450;

    private static List<String> translateBatchMyMemory(
            List<TranscriptSegment> segments, String targetLang) throws Exception {
        Utils.verifyOffMainThread();
        Logger.printDebug(() -> "MyMemory translation starting: " + targetLang);

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
        if (httpCode != 200) {
            VoiceOverTranslationPatch.notifyHttpError(httpCode);
            throw new Exception("MyMemory HTTP status: " + httpCode);
        }

        // Response: {"responseStatus": 200, "responseData": {"translatedText": "..."}}
        JSONObject json = new JSONObject(Requester.parseString(conn));
        final int responseStatus = json.optInt("responseStatus", 200);
        if (responseStatus != 200) throw new Exception("MyMemory error " + responseStatus
                + ": " + json.optString("responseDetails", "unknown error"));

        String translation = json.getJSONObject("responseData").getString("translatedText");
        return Arrays.asList(translation.split("\n", -1));
    }

    private static List<String> translateBatchOpenRouter(
            List<TranscriptSegment> segments, String targetLang) throws Exception {
        Utils.verifyOffMainThread();

        String modelValue = Settings.VOT_OPENROUTER_MODEL.get();
        if (modelValue.equals("custom")) {
            modelValue = Settings.VOT_OPENROUTER_CUSTOM_MODEL_ID.get().trim();
            if (modelValue.isEmpty()) throw new Exception("Custom OpenRouter model ID is not set");
        }
        final String model = modelValue;
        Logger.printDebug(() -> "OpenRouter translation starting: " + targetLang + " model: " + model);

        String apiKey = Settings.VOT_OPENROUTER_API_KEY.get().trim();
        if (apiKey.isEmpty()) throw new Exception("OpenRouter API key is not set");

        StringBuilder joined = new StringBuilder();
        for (TranscriptSegment seg : segments) {
            if (joined.length() > 0) joined.append('\n');
            joined.append(seg.text());
        }

        JSONObject systemMessage = new JSONObject()
                .put("role", "system")
                .put("content", "Translate the following subtitle lines to " + targetLang
                        + ". Output ONLY the translated lines in the same order, one per line. Do not add explanations or extra text.");
        JSONObject userMessage = new JSONObject()
                .put("role", "user")
                .put("content", joined.toString());

        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", new JSONArray().put(systemMessage).put(userMessage));

        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);

        //noinspection ExtractMethodRecommender
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "https://openrouter.ai/api/v1/chat/completions").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(OPENROUTER_READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(bodyBytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }

        final int code = conn.getResponseCode();
        if (code != 200) {
            VoiceOverTranslationPatch.notifyHttpError(code);
            throw new Exception("HTTP " + code);
        }

        // Response: {"choices": [{"message": {"content": "translated text"}}]}
        JSONObject json = new JSONObject(Requester.parseString(conn));
        String translation = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");

        Logger.printDebug(() -> "OpenRouter translation complete: " + targetLang);
        return Arrays.asList(translation.split("\n", -1));
    }
}
