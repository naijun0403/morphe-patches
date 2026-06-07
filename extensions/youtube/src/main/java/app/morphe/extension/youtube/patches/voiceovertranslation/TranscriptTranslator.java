/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;

/**
 * Translates transcript segments via the Google Translate public endpoint.
 * Used as a fallback when YouTube's &tlang= parameter is not supported by the caption track.
 */
final class TranscriptTranslator {

    private static final String TRANSLATE_URL =
            "https://translate.googleapis.com/translate_a/t?client=te_lib&sl=auto&format=text&tl=";

    // Max segments per POST body to stay within reasonable request size.
    private static final int BATCH_SIZE = 50;

    static List<TranscriptSegment> translate(List<TranscriptSegment> segments, String targetLang) {
        if (segments.isEmpty()) return segments;
        List<TranscriptSegment> result = new ArrayList<>(segments.size());
        for (int i = 0, segmentsSize = segments.size(); i < segmentsSize; i += BATCH_SIZE) {
            List<TranscriptSegment> batch = segments.subList(i, Math.min(i + BATCH_SIZE, segments.size()));
            try {
                List<String> translated = translateBatch(batch, targetLang);
                final int translatedSize = translated.size();
                for (int j = 0, batchSize = batch.size(); j < batchSize; j++) {
                    TranscriptSegment orig = batch.get(j);
                    String text = j < translatedSize ? translated.get(j) : orig.text();
                    result.add(new TranscriptSegment(orig.startMs(), orig.endMs(), text));
                }
            } catch (Exception ex) {
                // Keep original text for this batch on failure rather than losing segments.
                result.addAll(batch);
                Logger.printDebug(() -> "TranscriptTranslator: batch failed: " + ex.getMessage());
            }
        }
        return result;
    }

    private static List<String> translateBatch(List<TranscriptSegment> segments, String targetLang) throws Exception {
        StringBuilder body = new StringBuilder();
        for (TranscriptSegment seg : segments) {
            if (body.length() > 0) body.append('&');
            //noinspection CharsetObjectCanBeUsed
            body.append("q=").append(URLEncoder.encode(seg.text(), StandardCharsets.UTF_8.name()));
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(TRANSLATE_URL + targetLang).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setDoOutput(true);

        byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bodyBytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyBytes);
        }

        final int code = conn.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);

        return parseResponse(conn);
    }

    private static List<String> parseResponse(HttpURLConnection conn) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);

        JSONArray arr = new JSONArray(sb.toString());
        List<String> result = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            result.add(arr.getString(i));
        }
        return result;
    }
}
