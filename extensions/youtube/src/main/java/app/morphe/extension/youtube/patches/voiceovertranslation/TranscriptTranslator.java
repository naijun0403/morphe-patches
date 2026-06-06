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

final class TranscriptTranslator {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS    = 30_000;

    static List<TranscriptSegment> translateAll(List<TranscriptSegment> segments,
                                                String sourceLang,
                                                String targetLang) throws Exception {
        if (sourceLang.split("-")[0].equals(targetLang.split("-")[0])) return segments;

        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) joined.append('\n');
            joined.append(segments.get(i).text());
        }

        String translatedJoined = requestTranslation(joined.toString(), sourceLang, targetLang);

        String[] parts = translatedJoined.split("\n", -1);
        List<TranscriptSegment> result = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            String text = (i < parts.length) ? parts[i].trim() : "";
            if (text.isEmpty()) text = segments.get(i).text();
            result.add(new TranscriptSegment(segments.get(i).startMs(), segments.get(i).endMs(), text));
        }
        Logger.printInfo(() -> "VoiceOverTranslation: translated " + result.size()
                + " segments (" + sourceLang + "→" + targetLang + ")");
        return result;
    }

    private static String requestTranslation(String text, String sourceLang, String targetLang) throws Exception {
        String urlStr = "https://translate.googleapis.com/translate_a/single"
                + "?client=gtx&sl=" + sourceLang + "&tl=" + targetLang + "&dt=t";
        //noinspection CharsetObjectCanBeUsed — Charset overload requires API 33; .name() works from API 1
        byte[] body = ("q=" + URLEncoder.encode(text, StandardCharsets.UTF_8.name()))
                .getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("Translate HTTP " + code + " for " + urlStr);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);

            return parseSentences(sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    // Response: [[["translated1","orig1",...],["translated2","orig2",...],...],...]
    private static String parseSentences(String json) throws Exception {
        JSONArray root = new JSONArray(json);
        JSONArray sentences = root.getJSONArray(0);
        StringBuilder translated = new StringBuilder();
        for (int i = 0; i < sentences.length(); i++) {
            Object item = sentences.get(i);
            if (item instanceof JSONArray sentence && !sentence.isNull(0)) {
                translated.append(sentence.getString(0));
            }
        }
        return translated.toString();
    }
}
