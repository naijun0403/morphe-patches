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

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
    private static final int GOOGLE_MAX_BATCH_CHARS = 4_000;
    // Smaller batches for OpenRouter so the first batch completes faster and TTS starts sooner.
    private static final int OPENROUTER_MAX_BATCH_CHARS = 1_500;
    // Delay between consecutive background batches to reduce IP rate-limit pressure.
    private static final int GOOGLE_INTER_BATCH_DELAY_MS = 500;
    private static final int OPENROUTER_INTER_BATCH_DELAY_MS = 0;
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
    // Set to true when any batch returns HTTP 429, or when a new video is loaded while
    // translation is in progress. Remaining batches are skipped immediately.
    private static volatile boolean abortTranslation;
    // Held while an OpenRouter request is in flight (connecting, waiting for response, or reading
    // the SSE stream) so requestAbort() can disconnect it and unblock the background thread.
    private static volatile HttpURLConnection activeConnection;

    static void requestAbort() {
        abortTranslation = true;
        HttpURLConnection conn = activeConnection;
        if (conn != null) conn.disconnect();
    }

    /**
     * Progressive translation. The first batch is translated synchronously so the returned
     * list is immediately usable for playback; remaining batches are translated on background
     * threads and published through {@code onUpdate} (called once per completed batch with a
     * full snapshot of the segment list). Failed batches keep their original text.
     *
     * <p>Timings and list size never change between updates - only segment text - so callers
     * may keep indexing into the list across snapshots.
     */
    static List<TranscriptSegment> translate(String videoId,
                                             List<TranscriptSegment> segments,
                                             String targetLang,
                                             Consumer<List<TranscriptSegment>> onUpdate,
                                             BooleanSupplier cancelled) {
        if (segments.isEmpty()) return segments;
        Utils.verifyOffMainThread();

        final String service = Settings.VOT_TRANSLATION_SERVICE.get();
        final boolean isMyMemory = service.equals(TRANSLATION_SERVICE_MY_MEMORY);
        final boolean isOpenRouter = service.equals(TRANSLATION_SERVICE_OPENROUTER);
        final int maxBatchChars = isMyMemory ? MYMEMORY_MAX_CHARS
                : isOpenRouter ? OPENROUTER_MAX_BATCH_CHARS
                : GOOGLE_MAX_BATCH_CHARS;
        List<List<TranscriptSegment>> batches = splitByCharBudget(segments, maxBatchChars);
        reportNextTranslationError = true;
        abortTranslation = false;

        // Working copy that accumulates translated batches over the original text.
        List<TranscriptSegment> working = new ArrayList<>(segments);

        final int batchesSize = batches.size();
        int[] offsets = new int[batchesSize];
        for (int b = 1; b < batchesSize; b++) {
            offsets[b] = offsets[b - 1] + batches.get(b - 1).size();
        }

        final Handler mainHandler = new Handler(Looper.getMainLooper());

        // Tracks which batches have been translated to support dynamic priority re-ordering.
        final boolean[] batchDone = new boolean[batchesSize];

        // Translate the batch at the current video position first so TTS gets translated text
        // without waiting for all preceding batches. Critical when starting mid-video or seeking.
        final long currentTimeMs = VoiceOverTranslationPatch.lastVideoTimeMs;
        final int priorityBatchIndex = currentTimeMs > 0 ? findBatchAtTime(batches, currentTimeMs) : 0;

        if (priorityBatchIndex > 0) {
            final List<TranscriptSegment> priorityBatch = batches.get(priorityBatchIndex);
            final int priorityOffset = offsets[priorityBatchIndex];
            applyBatch(working, priorityBatch, priorityOffset,
                    translateBatchSafe(videoId, priorityBatch, targetLang,
                            streamCallback(onUpdate, mainHandler, working, priorityBatch, priorityOffset)));
            batchDone[priorityBatchIndex] = true;
            if (onUpdate != null && !isOpenRouter) {
                List<TranscriptSegment> snap = new ArrayList<>(working);
                mainHandler.post(() -> onUpdate.accept(snap));
            }
        }

        final List<TranscriptSegment> batch0 = batches.get(0);
        applyBatch(working, batch0, 0, translateBatchSafe(videoId, batch0, targetLang,
                streamCallback(onUpdate, mainHandler, working, batch0, 0)));
        batchDone[0] = true;
        if (batchesSize == 1) return working;

        // Snapshot to return before background batches start mutating the working copy.
        List<TranscriptSegment> initial = new ArrayList<>(working);

        final int batchDelay = isMyMemory ? MYMEMORY_INTER_BATCH_DELAY_MS
                : isOpenRouter ? OPENROUTER_INTER_BATCH_DELAY_MS
                : GOOGLE_INTER_BATCH_DELAY_MS;
        // For non-streaming services (Google, MyMemory), onUpdate hasn't fired yet for batch 0 —
        // post it now so TTS can start. For OpenRouter the stream already posted incremental updates.
        if (onUpdate != null && !isOpenRouter) {
            mainHandler.post(() -> onUpdate.accept(initial));
        }

        for (int batchIndex = 1; batchIndex < batchesSize; batchIndex++) {
            if (abortTranslation) break;
            if (batchDone[batchIndex]) continue;

            // Re-check priority between batches: if the user seeked forward while translation
            // is in progress, translate the batch at the new position before continuing.
            final long liveTimeMs = VoiceOverTranslationPatch.lastVideoTimeMs;
            if (liveTimeMs > 0) {
                final int livePriority = findBatchAtTime(batches, liveTimeMs);
                if (livePriority > batchIndex && !batchDone[livePriority]) {
                    final List<TranscriptSegment> liveBatch = batches.get(livePriority);
                    final int liveOffset = offsets[livePriority];
                    applyBatch(working, liveBatch, liveOffset,
                            translateBatchSafe(videoId, liveBatch, targetLang,
                                    streamCallback(onUpdate, mainHandler, working, liveBatch, liveOffset)));
                    batchDone[livePriority] = true;
                    if (onUpdate != null && !isOpenRouter) {
                        List<TranscriptSegment> snap = new ArrayList<>(working);
                        mainHandler.post(() -> onUpdate.accept(snap));
                    }
                    if (abortTranslation) break;
                }
            }

            if (batchDone[batchIndex]) continue;

            List<TranscriptSegment> batchN = batches.get(batchIndex);
            final int batchOffset = offsets[batchIndex];
            List<String> translated = translateBatchSafe(videoId, batchN, targetLang,
                    streamCallback(onUpdate, mainHandler, working, batchN, batchOffset));

            applyBatch(working, batchN, batchOffset, translated);
            batchDone[batchIndex] = true;
            List<TranscriptSegment> snapshot = new ArrayList<>(working);
            if (onUpdate != null) mainHandler.post(() -> onUpdate.accept(snapshot));

            // Skip remaining work when the result is no longer needed (video changed).
            if (cancelled != null) {
                FutureTask<Boolean> cancelCheck = new FutureTask<>(cancelled::getAsBoolean);
                mainHandler.post(cancelCheck);
                try {
                    if (cancelCheck.get()) {
                        Logger.printDebug(() -> "Translate batch canceled for: " + targetLang);
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
    private static Consumer<List<String>> streamCallback(
            @Nullable Consumer<List<TranscriptSegment>> onUpdate,
            Handler mainHandler,
            List<TranscriptSegment> working,
            List<TranscriptSegment> batch,
            int offset) {
        if (onUpdate == null) return null;
        return partial -> {
            List<TranscriptSegment> snap = new ArrayList<>(working);
            applyBatch(snap, batch, offset, partial);
            mainHandler.post(() -> onUpdate.accept(snap));
        };
    }

    @Nullable
    private static List<String> translateBatchSafe(String videoId,
                                                   List<TranscriptSegment> batch, String targetLang,
                                                   @Nullable Consumer<List<String>> onLineStreamed) {
        try {
            return translateBatch(videoId, batch, targetLang, onLineStreamed);
        } catch (Exception ex) {
            if (abortTranslation) {
                Logger.printDebug(() -> "Translation aborted: " + ex.getMessage());
                return null;
            }
            String msg = ex.getMessage();
            // FileNotFoundException from getInputStream() is Android's HttpURLConnection reporting
            // a 4xx/5xx error when getResponseCode() incorrectly returned 200 in streaming mode.
            if (ex instanceof FileNotFoundException
                    || (msg != null && (msg.contains("429") || msg.contains("401") || msg.contains("403")))) {
                abortTranslation = true;
            }
            if (reportNextTranslationError) {
                reportNextTranslationError = false;
                VoiceOverTranslationPatch.logError(() -> "Translation failed: " + msg, ex);
            } else {
                Logger.printDebug(() -> "Batch failed", ex);
            }
            return null;
        }
    }

    private static int findBatchAtTime(List<List<TranscriptSegment>> batches, long timeMs) {
        for (int i = 0; i < batches.size(); i++) {
            List<TranscriptSegment> batch = batches.get(i);
            if (batch.get(batch.size() - 1).endMs() > timeMs) {
                return i;
            }
        }
        return batches.size() - 1;
    }

    private static List<List<TranscriptSegment>> splitByCharBudget(
            List<TranscriptSegment> segments, int maxChars) {
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

    private static List<String> translateBatch(String videoId,
                                               List<TranscriptSegment> segments,
                                               String targetLang,
                                               @Nullable Consumer<List<String>> onLineStreamed) throws Exception {
        String service = Settings.VOT_TRANSLATION_SERVICE.get();
        if (service.equals(TRANSLATION_SERVICE_MY_MEMORY)) {
            return translateBatchMyMemory(videoId, segments, targetLang);
        }
        if (service.equals(TRANSLATION_SERVICE_OPENROUTER)) {
            return translateBatchOpenRouter(videoId, segments, targetLang, onLineStreamed);
        }
        return translateBatchGoogle(videoId, segments, targetLang);
    }

    private static boolean parseLine(String line, List<String> result, int segmentCount) {
        int i = 0;
        while (i < line.length() && Character.isDigit(line.charAt(i))) i++;
        if (i == 0 || i >= line.length()) return false;
        final char sep = line.charAt(i);
        if (sep != ':' && sep != '.') return false;
        try {
            int num = Integer.parseInt(line.substring(0, i));
            String text = line.substring(i + 1).trim();
            if (num >= 1 && num <= segmentCount && !text.isEmpty()) {
                result.set(num - 1, text);
                return true;
            }
        } catch (NumberFormatException ignored) {}
        return false;
    }

    private static List<String> translateBatchGoogle(String videoId,
                                                     List<TranscriptSegment> segments,
                                                     String targetLang) throws Exception {
        Utils.verifyOffMainThread();
        final long start = System.currentTimeMillis();
        Logger.printDebug(() -> "Google translation starting: " + videoId + " lang: " + targetLang);

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
            throw new Exception("Google HTTP status: " + code + " language: " + targetLang
                    + " response: " + Requester.parseString(conn));
        }

        // Response: [[["translated","original",...],...],null,"src_lang",...]
        // Concatenate sentence translations; newline separators from joined input are preserved.
        JSONArray sentences = new JSONArray(Requester.parseString(conn)).getJSONArray(0);
        StringBuilder translatedJoined = new StringBuilder();
        for (int i = 0, length = sentences.length(); i < length; i++) {
            translatedJoined.append(sentences.getJSONArray(i).getString(0));
        }
        Logger.printDebug(() -> "Google translation complete: " + targetLang
                + " fetchTime: " + (System.currentTimeMillis() - start) + "ms");
        return Arrays.asList(translatedJoined.toString().split("\n", -1));
    }

    // MyMemory limits q to 500 bytes per request.
    private static final int MYMEMORY_MAX_CHARS = 450;

    private static List<String> translateBatchMyMemory(String videoId,
                                                       List<TranscriptSegment> segments,
                                                       String targetLang) throws Exception {
        Utils.verifyOffMainThread();
        final long start = System.currentTimeMillis();
        Logger.printDebug(() -> "MyMemory translation starting: " + videoId + " lang: " + targetLang);

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
            throw new Exception("MyMemory HTTP status: " + httpCode + " language: " + targetLang
                    + " response: " + Requester.parseString(conn));
        }

        // Response: {"responseStatus": 200, "responseData": {"translatedText": "..."}}
        JSONObject json = new JSONObject(Requester.parseString(conn));
        final int responseStatus = json.optInt("responseStatus", 200);
        if (responseStatus != 200) throw new Exception("MyMemory error " + responseStatus
                + ": " + json.optString("responseDetails", "unknown error"));

        String translation = json.getJSONObject("responseData").getString("translatedText");
        List<String> result = Arrays.asList(translation.split("\n", -1));

        Logger.printDebug(() -> "MyMemory translation complete: " + targetLang
                + " fetchTime: " + (System.currentTimeMillis() - start) + "ms");
        return result;
    }

    private static List<String> translateBatchOpenRouter(
            String videoId,
            List<TranscriptSegment> segments, String targetLang,
            @Nullable Consumer<List<String>> onLineStreamed) throws Exception {
        Utils.verifyOffMainThread();

        final String model = Settings.VOT_OPENROUTER_MODEL.get();
        final long start = System.currentTimeMillis();
        Logger.printDebug(() -> "OpenRouter translation starting: " + videoId + " lang: " + targetLang + " model: " + model);

        String apiKey = Settings.VOT_OPENROUTER_API_KEY.get().trim();
        if (apiKey.isEmpty()) throw new Exception("OpenRouter API key is not set");

        // Number each line so the model cannot silently merge or skip lines.
        StringBuilder joined = new StringBuilder();
        for (int i = 0, size = segments.size(); i < size; i++) {
            if (i > 0) joined.append('\n');
            joined.append(i + 1).append(": ").append(segments.get(i).text());
        }

        final String targetLangName = Locale.forLanguageTag(targetLang).getDisplayLanguage(Locale.ENGLISH);
        JSONObject systemMessage = new JSONObject()
                .put("role", "system")
                .put("content", "Translate the following numbered subtitle lines to " + targetLangName
                        + " (BCP 47 language code: " + targetLang + "). "
                        + "Return each translation in the format \"N: translation\" using the same number. "
                        + "Output exactly one line per input number. Do not merge, skip, or reorder lines.");
        JSONObject userMessage = new JSONObject()
                .put("role", "user")
                .put("content", joined.toString());

        JSONObject body = new JSONObject()
                .put("model", model)
                .put("temperature", 0)
                .put("stream", true)
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
        conn.setRequestProperty("Accept-Encoding", "identity");
        conn.setDoOutput(true);
        conn.setFixedLengthStreamingMode(bodyBytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }

        List<String> result = new ArrayList<>(segments.size());
        for (TranscriptSegment seg : segments) result.add(seg.text());
        int[] matched = {0};

        activeConnection = conn;
        try {
            final int code = conn.getResponseCode();
            if (code != 200) {
                VoiceOverTranslationPatch.notifyHttpError(code);
                throw new Exception("OpenRouter HTTP status: " + code + " language: " + targetLang
                        + " response: " + Requester.parseString(conn));
            }

            // Read SSE stream. Each chunk is a "data: {...}" line; content deltas are accumulated
            // into lineBuffer and flushed to result whenever a newline arrives.
            StringBuilder lineBuffer = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String sseLine;
                while ((sseLine = reader.readLine()) != null) {
                    if (!sseLine.startsWith("data: ")) continue;
                    final String data = sseLine.substring(6).trim();
                    if (data.equals("[DONE]")) break;

                    final JSONObject chunk;
                    try {
                        chunk = new JSONObject(data);
                    } catch (Exception ignored) {
                        continue;
                    }
                    JSONArray choices = chunk.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) continue;
                    JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                    if (delta == null) continue;

                    String content = delta.optString("content", "");
                    for (int ci = 0; ci < content.length(); ci++) {
                        final char c = content.charAt(ci);
                        if (c == '\n') {
                            final String line = lineBuffer.toString().trim();
                            lineBuffer.setLength(0);
                            if (!line.isEmpty() && parseLine(line, result, segments.size())) {
                                matched[0]++;
                                if (onLineStreamed != null) onLineStreamed.accept(new ArrayList<>(result));
                            }
                        } else {
                            lineBuffer.append(c);
                        }
                    }
                }
                // Flush any remaining content that arrived without a trailing newline.
                if (lineBuffer.length() > 0) {
                    final String line = lineBuffer.toString().trim();
                    if (!line.isEmpty() && parseLine(line, result, segments.size())) {
                        matched[0]++;
                        if (onLineStreamed != null) onLineStreamed.accept(new ArrayList<>(result));
                    }
                }
            }
        } finally {
            if (activeConnection == conn) activeConnection = null;
        }

        final int segmentSize = segments.size();
        final int matchedFirst = matched[0];
        if (matchedFirst != segmentSize) {
            Logger.printDebug(() -> "OpenRouter line mismatch - expected: " + segmentSize
                    + ", got: " + matchedFirst + "; last: " + (segmentSize - matchedFirst)
                    + " segment(s) keep original text");
        }

        Logger.printDebug(() -> "OpenRouter translation complete: " + targetLang
                + " fetchTime: " + (System.currentTimeMillis() - start) + "ms");
        return result;
    }
}
