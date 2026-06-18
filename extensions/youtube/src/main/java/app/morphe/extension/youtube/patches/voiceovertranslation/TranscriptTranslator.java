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
    // Set true when a seek moves the play head into a different, not-yet-translated batch while
    // a streaming request is in flight. The in-flight batch is abandoned (connection disconnected)
    // and the dispatcher re-picks the batch nearest the new play head. Unlike abortTranslation this
    // is not fatal - translation continues from the new position.
    private static volatile boolean reprioritize;
    // Session state published while translate() runs so onSeek() can map a timestamp to a batch and
    // decide whether the in-flight request is worth cutting. Null while no translation is running.
    @Nullable
    private static volatile List<List<TranscriptSegment>> liveBatches;
    @Nullable
    private static volatile boolean[] liveBatchDone;
    // Index of the batch currently being translated, or -1 when idle.
    private static volatile int translatingBatchIndex = -1;
    // The original (untranslated) segments for the running session, indexed identically to the
    // published snapshots, so callers can tell whether a given segment is still on its original
    // text. Null while no translation is running.
    @Nullable
    private static volatile List<TranscriptSegment> liveOriginals;

    // Cutting the in-flight streaming request on every seek means rapid scrubbing shreds batch
    // after batch with nothing completing. The cut is debounced: it only fires once the play head
    // has settled, so a deliberate seek stays responsive while scrubbing lets a batch finish.
    private static final long SEEK_DEBOUNCE_MS = 700;
    private static final Handler seekHandler = new Handler(Looper.getMainLooper());
    private static volatile long pendingSeekTimeMs;
    private static final Runnable seekCutter = TranscriptTranslator::applySeekCut;

    static void requestAbort() {
        abortTranslation = true;
        HttpURLConnection conn = activeConnection;
        if (conn != null) conn.disconnect();
    }

    /**
     * Called from the player thread on a large seek. If a batch other than the one being
     * translated now sits under the play head and is still untranslated, the in-flight streaming
     * request is cut so the dispatcher can immediately re-pick the batch at the new position.
     * Cheap no-op when nothing is streaming (idle, or a non-streaming service).
     */
    static void onSeek(long timeMs) {
        if (activeConnection == null) return; // Only an in-flight streaming request can be cut.
        pendingSeekTimeMs = timeMs;
        seekHandler.removeCallbacks(seekCutter);
        seekHandler.postDelayed(seekCutter, SEEK_DEBOUNCE_MS);
    }

    private static void applySeekCut() {
        HttpURLConnection conn = activeConnection;
        if (conn == null) return;
        final List<List<TranscriptSegment>> batches = liveBatches;
        final boolean[] done = liveBatchDone;
        if (batches == null || done == null) return;
        final int target = findBatchAtTime(batches, pendingSeekTimeMs);
        if (target == translatingBatchIndex) return; // Settled inside the batch being translated.
        if (target >= 0 && target < done.length && done[target]) return; // Target already translated.
        reprioritize = true;
        conn.disconnect();
    }

    /**
     * @return true while a translation is running, the given segment is still on its original text,
     *         and its batch has not finished, signaling the caller to wait rather than speak the
     *         untranslated original. Returns false as soon as the segment's text changes (so a line
     *         delivered early by the OpenRouter stream plays immediately), once its batch is done
     *         even if the text was kept after a failed translation (so playback is never blocked
     *         indefinitely), and when no translation is active.
     */
    static boolean isAwaitingTranslationAt(int segmentIndex, long segmentStartMs, String currentText) {
        final List<TranscriptSegment> originals = liveOriginals;
        if (originals == null) return false;
        if (segmentIndex < 0 || segmentIndex >= originals.size()) return false;
        // Text already differs from the original - translated by a completed batch or a streamed line.
        if (!currentText.equals(originals.get(segmentIndex).text())) return false;
        final List<List<TranscriptSegment>> batches = liveBatches;
        final boolean[] done = liveBatchDone;
        if (batches == null || done == null) return true;
        final int index = findBatchAtTime(batches, segmentStartMs);
        return index < 0 || index >= done.length || !done[index];
    }

    /**
     * Progressive, play-head-driven translation. Batches are translated one at a time, always
     * choosing the not-yet-done batch nearest the current play head (the batch under it first,
     * then batches ahead, then batches behind). This keeps the audible region translated first
     * whether playback starts at zero, resumes mid-video, or jumps after a seek.
     *
     * <p>Each completed batch is published through {@code onUpdate} with a full snapshot of the
     * segment list; streaming services (OpenRouter) additionally publish incremental line updates
     * while a batch is in flight. Failed batches keep their original text.
     *
     * <p>A large seek into a different, untranslated batch cuts the in-flight streaming request
     * (see {@link #onSeek(long)}) so the dispatcher re-targets the new position without waiting
     * for the current batch to finish.
     *
     * <p>Timings and list size never change between updates - only segment text - so callers may
     * keep indexing into the list across snapshots.
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
        final List<List<TranscriptSegment>> batches = splitByCharBudget(segments, maxBatchChars);
        reportNextTranslationError = true;
        abortTranslation = false;
        reprioritize = false;

        // Working copy that accumulates translated batches over the original text.
        final List<TranscriptSegment> working = new ArrayList<>(segments);

        final int batchesSize = batches.size();
        final int[] offsets = new int[batchesSize];
        for (int b = 1; b < batchesSize; b++) {
            offsets[b] = offsets[b - 1] + batches.get(b - 1).size();
        }

        final Handler mainHandler = new Handler(Looper.getMainLooper());

        // Tracks which batches have been translated, driving both dispatch order and seek handling.
        final boolean[] batchDone = new boolean[batchesSize];

        final int batchDelay = isMyMemory ? MYMEMORY_INTER_BATCH_DELAY_MS
                : isOpenRouter ? OPENROUTER_INTER_BATCH_DELAY_MS
                  : GOOGLE_INTER_BATCH_DELAY_MS;

        // Publish session state so onSeek()/isAwaitingTranslationAt() can see it.
        liveBatches = batches;
        liveBatchDone = batchDone;
        liveOriginals = segments;
        translatingBatchIndex = -1;

        // First completed batch snapshot, returned as a fallback only if onUpdate never runs.
        List<TranscriptSegment> initial = null;
        int completed = 0;

        try {
            while (completed < batchesSize) {
                if (abortTranslation) break;
                reprioritize = false;

                final long timeMs = VoiceOverTranslationPatch.lastVideoTimeMs;
                final int index = pickNextBatch(batches, batchDone, timeMs);
                if (index < 0) break;

                final List<TranscriptSegment> batch = batches.get(index);
                final int offset = offsets[index];

                translatingBatchIndex = index;
                final List<String> translated = translateBatchSafe(videoId, batch, targetLang,
                        streamCallback(onUpdate, mainHandler, working, batch, offset));
                translatingBatchIndex = -1;

                // A seek cut this request short - drop the partial result and re-pick against the
                // new play head without marking the batch done.
                if (reprioritize && !abortTranslation) {
                    Logger.printDebug(() -> "Reprioritizing translation after seek");
                    continue;
                }
                if (abortTranslation) break;

                applyBatch(working, batch, offset, translated);
                batchDone[index] = true;
                completed++;

                final List<TranscriptSegment> snapshot = new ArrayList<>(working);
                if (onUpdate != null) mainHandler.post(() -> onUpdate.accept(snapshot));
                if (initial == null) initial = snapshot;

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

                if (completed < batchesSize && batchDelay > 0) {
                    try {
                        Thread.sleep(batchDelay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return initial;
                    }
                }
            }
            return initial != null ? initial : working;
        } finally {
            liveBatches = null;
            liveBatchDone = null;
            liveOriginals = null;
            translatingBatchIndex = -1;
        }
    }

    /**
     * Picks the not-yet-translated batch to translate next: the batch under the play head, then
     * batches ahead of it (closest first), then batches behind it (closest first). Returns -1 when
     * every batch is done. With timeMs 0 (playback from the start) this degrades to plain
     * front-to-back order.
     */
    private static int pickNextBatch(List<List<TranscriptSegment>> batches, boolean[] done, long timeMs) {
        final int size = batches.size();
        final int current = findBatchAtTime(batches, timeMs);
        for (int i = current; i < size; i++) {
            if (!done[i]) return i;
        }
        for (int i = current - 1; i >= 0; i--) {
            if (!done[i]) return i;
        }
        return -1;
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
            if (abortTranslation || reprioritize) {
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

    /**
     * Strips a leading line number ("3: ", "3. ", "3) ") if present, returning the remaining text.
     */
    private static String stripNumberPrefix(String line) {
        int i = 0;
        while (i < line.length() && Character.isDigit(line.charAt(i))) i++;
        if (i > 0 && i < line.length()) {
            final char sep = line.charAt(i);
            if (sep == ':' || sep == '.' || sep == ')') {
                return line.substring(i + 1).trim();
            }
        }
        return line;
    }

    /**
     * Maps unnumbered or mis-numbered model output to segments by position. Succeeds only when the
     * stripped, non-empty line count matches the segment count exactly, so a merged or padded
     * response (which cannot be mapped safely) is rejected rather than scrambled across segments.
     */
    @Nullable
    private static List<String> positionalFallback(String raw, int segmentCount) {
        List<String> lines = new ArrayList<>(segmentCount);
        for (String line : raw.split("\n")) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            lines.add(stripNumberPrefix(trimmed));
        }
        return lines.size() == segmentCount ? lines : null;
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
        // Full raw model output, kept so a positional fallback can run if numbered parsing fails.
        final StringBuilder rawOutput = new StringBuilder();

        activeConnection = conn;
        try {
            final int code = conn.getResponseCode();
            if (code != 200) {
                VoiceOverTranslationPatch.notifyHttpError(code);
                throw new Exception("OpenRouter HTTP status: " + code + " language: " + targetLang
                        + " response: " + Requester.parseString(conn));
            }

            // Read SSE stream. Each chunk is a "data: {...}" line; content deltas are accumulated
            // into lineBuffer and flushed to result whenever a newline arrives, and mirrored into
            // rawOutput for the positional fallback.
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
                    rawOutput.append(content);
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
        // Some models translate correctly but omit or mangle the line numbers, leaving most
        // segments on their original text. When the stripped output lines up one-to-one with the
        // inputs, recover it by mapping positionally instead of dropping the translation.
        if (matched[0] != segmentSize) {
            List<String> positional = positionalFallback(rawOutput.toString(), segmentSize);
            if (positional != null) {
                for (int i = 0; i < segmentSize; i++) result.set(i, positional.get(i));
                matched[0] = segmentSize;
                if (onLineStreamed != null) onLineStreamed.accept(new ArrayList<>(result));
                Logger.printDebug(() -> "OpenRouter positional fallback applied: " + segmentSize + " lines");
            }
        }

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
