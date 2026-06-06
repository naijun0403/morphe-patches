/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.innertube.utils.AuthUtils;
import app.morphe.extension.youtube.patches.CaptionCookiesPatch;
import app.morphe.extension.youtube.settings.Settings;

final class TranscriptFetcher {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final String INNERTUBE_PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";

    static List<TranscriptSegment> fetch(String videoId) throws Exception {
        String[] innertubeResult = fetchFromInnertube(videoId);
        String captionUrl = innertubeResult[0];
        String poToken    = innertubeResult[1];
        String targetLang = Settings.VOT_CAPTION_LANGUAGE.get();

        Logger.printInfo(() -> "VoiceOverTranslation: captionUrl=" + (captionUrl != null ? "found" : "null")
                + " pot=" + (poToken != null ? "found" : "null") + " for " + videoId);

        if (captionUrl != null) {
            try {
                String sourceLang = extractLangFromUrl(captionUrl);
                String json3Url = captionUrl.replaceAll("&fmt=[^&]*", "") + "&fmt=json3";
                if (poToken != null) json3Url += "&pot=" + poToken;

                String json = fetchUrl(json3Url);
                Logger.printInfo(() -> "VoiceOverTranslation: json3 len=" + json.length() + " for " + videoId);
                if (!json.isEmpty()) {
                    List<TranscriptSegment> segments = parseJson3(json);
                    if (!segments.isEmpty()) {
                        Logger.printInfo(() -> "VoiceOverTranslation: parsed " + segments.size()
                                + " segments (" + sourceLang + ") for " + videoId);
                        return translate(segments, sourceLang, targetLang);
                    }
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "VoiceOverTranslation: innertube caption fetch failed, trying direct: "
                        + ex.getMessage());
            }
        }

        Logger.printInfo(() -> "VoiceOverTranslation: trying direct fetch for " + videoId);
        return fetchDirect(videoId, targetLang);
    }

    private static List<TranscriptSegment> translate(List<TranscriptSegment> segments,
                                                      String sourceLang, String targetLang) {
        try {
            return TranscriptTranslator.translateAll(segments, sourceLang, targetLang);
        } catch (Exception ex) {
            Logger.printException(() -> "VoiceOverTranslation: translation failed, using original", ex);
            return segments;
        }
    }

    private static String[] fetchFromInnertube(String videoId) throws Exception {
        String body = "{\"context\":{\"client\":{\"clientName\":\"ANDROID\","
                + "\"clientVersion\":\"20.10.38\"}},"
                + "\"videoId\":\"" + videoId + "\"}";

        HttpURLConnection conn = (HttpURLConnection) new URL(INNERTUBE_PLAYER_URL).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent",
                    "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip");
            conn.setRequestProperty("X-YouTube-Client-Name", "3");
            conn.setRequestProperty("X-YouTube-Client-Version", "20.10.38");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("Innertube HTTP " + code);

            String response = readResponseBody(conn);
            Logger.printInfo(() -> "VoiceOverTranslation: innertube len=" + response.length()
                    + " hasTracks=" + response.contains("\"captionTracks\":[") + " for " + videoId);

            return new String[]{extractFirstCaptionUrl(response), extractPoToken(response)};
        } finally {
            conn.disconnect();
        }
    }

    private static String extractPoToken(String json) {
        int idx = json.indexOf("\"poToken\":\"");
        if (idx < 0) return null;
        idx += "\"poToken\":\"".length();
        int end = json.indexOf('"', idx);
        return end < 0 ? null : json.substring(idx, end);
    }

    private static String extractFirstCaptionUrl(String json) {
        int tracksIdx = json.indexOf("\"captionTracks\":[");
        if (tracksIdx < 0) return null;

        String firstUrl = null;
        String firstNonGemini = null;
        int searchFrom = tracksIdx;

        while (true) {
            int baseUrlIdx = json.indexOf("\"baseUrl\":\"", searchFrom);
            if (baseUrlIdx < 0 || baseUrlIdx > tracksIdx + 50_000) break;
            baseUrlIdx += "\"baseUrl\":\"".length();

            int endIdx = json.indexOf('"', baseUrlIdx);
            if (endIdx < 0) break;

            String url = json.substring(baseUrlIdx, endIdx)
                             .replace("\\u0026", "&")
                             .replace("\\u003d", "=")
                             .replace("\\u003e", ">")
                             .replace("\\u003c", "<");

            if (firstUrl == null) firstUrl = url;
            if (firstNonGemini == null && !url.contains("variant=gemini")) firstNonGemini = url;

            searchFrom = endIdx + 1;
        }

        return firstNonGemini != null ? firstNonGemini : firstUrl;
    }

    private static List<TranscriptSegment> fetchDirect(String videoId, String targetLang) {
        for (String srcLang : new String[]{"en", "en-US", "en-GB"}) {
            try {
                String urlStr = "https://www.youtube.com/api/timedtext?v=" + videoId
                        + "&lang=" + srcLang + "&kind=asr&fmt=json3";
                String json = fetchUrl(urlStr);
                final String langFinal = srcLang;
                Logger.printInfo(() -> "VoiceOverTranslation: direct lang=" + langFinal
                        + " len=" + json.length() + " for " + videoId);
                if (!json.isEmpty()) {
                    List<TranscriptSegment> segments = parseJson3(json);
                    if (!segments.isEmpty()) {
                        Logger.printInfo(() -> "VoiceOverTranslation: direct fetch got " + segments.size()
                                + " segments (" + langFinal + ") for " + videoId);
                        return translate(segments, "en", targetLang);
                    }
                }
            } catch (Exception ex) {
                final String langFinal = srcLang;
                Logger.printInfo(() -> "VoiceOverTranslation: direct exception lang=" + langFinal
                        + " " + ex.getMessage());
            }
        }
        Logger.printInfo(() -> "VoiceOverTranslation: no captions available for " + videoId);
        return new ArrayList<>();
    }

    private static String extractLangFromUrl(String url) {
        for (String prefix : new String[]{"&lang=", "?lang="}) {
            int idx = url.indexOf(prefix);
            if (idx >= 0) {
                idx += prefix.length();
                int end = url.indexOf('&', idx);
                return end < 0 ? url.substring(idx) : url.substring(idx, end);
            }
        }
        return "en";
    }

    private static List<TranscriptSegment> parseJson3(String json) throws Exception {
        List<TranscriptSegment> segments = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray events = root.optJSONArray("events");
        if (events == null) return segments;

        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            JSONArray segs = event.optJSONArray("segs");
            if (segs == null) continue;

            long startMs = event.optLong("tStartMs", -1);
            if (startMs < 0) continue;
            long durationMs = event.optLong("dDurationMs", 2000);

            StringBuilder text = new StringBuilder();
            for (int j = 0; j < segs.length(); j++) {
                text.append(segs.getJSONObject(j).optString("utf8", ""));
            }

            String textStr = text.toString().replace('\n', ' ').trim();
            if (!textStr.isEmpty()) {
                segments.add(new TranscriptSegment(startMs, startMs + Math.max(durationMs, 500), textStr));
            }
        }
        return segments;
    }

    private static String fetchUrl(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", CaptionCookiesPatch.getUserAgent());
            String cookies = CaptionCookiesPatch.getCookies();
            if (!cookies.isEmpty()) {
                conn.setRequestProperty("Cookie", cookies);
            }
            Map<String, String> authHeaders = AuthUtils.getRequestHeader();
            for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new Exception("HTTP " + responseCode + " for " + urlStr);
            }
            return readResponseBody(conn);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readResponseBody(HttpURLConnection conn) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }
}
