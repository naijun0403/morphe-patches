/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import org.json.JSONArray;

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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

/**
 * Translates transcript segments via the Google Translate public endpoint.
 */
final class TranscriptTranslator {

    private static final String TRANSLATE_URL =
            "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t&tl=";

    // Batches are built by character budget rather than segment count, so request
    // sizes stay uniform regardless of how long the merged sentences are.
    private static final int MAX_BATCH_CHARS = 4_000;
    // Concurrent requests to the translate endpoint. Keep modest to avoid rate limiting.
    private static final int PARALLEL_REQUESTS = 4;
    private static final long BATCH_TIMEOUT_SECONDS = 30;

    static List<TranscriptSegment> translate(List<TranscriptSegment> segments, String targetLang) {
        if (segments.isEmpty()) return segments;

        List<List<TranscriptSegment>> batches = splitByCharBudget(segments);
        //noinspection resource
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(PARALLEL_REQUESTS, batches.size()));
        try {
            List<Future<List<String>>> futures = new ArrayList<>(batches.size());
            for (List<TranscriptSegment> batch : batches) {
                futures.add(pool.submit(() -> translateBatch(batch, targetLang)));
            }

            List<TranscriptSegment> result = new ArrayList<>(segments.size());
            for (int b = 0, batchCount = batches.size(); b < batchCount; b++) {
                List<TranscriptSegment> batch = batches.get(b);
                List<String> translated = null;
                try {
                    translated = futures.get(b).get(BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    Logger.printDebug(() -> "TranscriptTranslator: batch failed: " + ex.getMessage());
                }

                if (translated == null) {
                    // Keep original text for this batch on failure rather than losing segments.
                    result.addAll(batch);
                    continue;
                }
                final int translatedSize = translated.size();
                for (int j = 0, batchSize = batch.size(); j < batchSize; j++) {
                    TranscriptSegment orig = batch.get(j);
                    String text = j < translatedSize ? translated.get(j) : orig.text();
                    result.add(new TranscriptSegment(orig.startMs(), orig.endMs(), text));
                }
            }
            return result;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<List<TranscriptSegment>> splitByCharBudget(List<TranscriptSegment> segments) {
        List<List<TranscriptSegment>> batches = new ArrayList<>();
        List<TranscriptSegment> batch = new ArrayList<>();
        int chars = 0;
        for (TranscriptSegment seg : segments) {
            final int len = seg.text().length() + 1;
            if (!batch.isEmpty() && chars + len > MAX_BATCH_CHARS) {
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
        StringBuilder joined = new StringBuilder();
        for (TranscriptSegment seg : segments) {
            if (joined.length() > 0) joined.append('\n');
            joined.append(seg.text());
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(TRANSLATE_URL + targetLang).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
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
}
