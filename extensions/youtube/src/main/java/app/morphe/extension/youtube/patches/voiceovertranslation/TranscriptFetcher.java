/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.innertube.utils.AuthUtils;
import app.morphe.extension.youtube.patches.CaptionCookiesPatch;
import app.morphe.extension.youtube.settings.Settings;

final class TranscriptFetcher {

    /** Language code detected from the video's own caption track. Updated on every successful fetch. */
    static volatile String lastSourceLang = "en";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final String INNERTUBE_PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";

    static List<TranscriptSegment> fetch(String videoId) {
        String targetLang = Settings.VOT_CAPTION_LANGUAGE.get();
        String captionUrl = null;
        String poToken    = null;
        try {
            String[] innertubeResult = fetchFromInnertube(videoId);
            captionUrl = innertubeResult[0];
            poToken    = innertubeResult[1];
        } catch (Exception ex) {
            Logger.printDebug(() -> "VoiceOverTranslation: innertube player failed: " + ex.getMessage());
        }

        if (captionUrl != null) {
            try {
                String sourceLang     = extractLangFromUrl(captionUrl);
                String sourceLangCode = sourceLang.split("-")[0];
                String targetLangCode = targetLang.split("-")[0];

                String baseUrl = captionUrl.replaceAll("&fmt=[^&]*", "") + "&fmt=json3";
                if (poToken != null) baseUrl += "&pot=" + poToken;

                boolean needsTranslation = !"auto".equals(targetLang)
                        && !sourceLangCode.equals(targetLangCode);
                String json3Url = needsTranslation ? baseUrl + "&tlang=" + targetLangCode : baseUrl;

                String json = fetchUrl(json3Url);
                List<TranscriptSegment> segments = parseJson3(json);
                boolean clientTranslate = false;
                // &tlang= can return valid JSON with 0 events on manual tracks; fall back to original.
                if (segments.isEmpty() && needsTranslation) {
                    json = fetchUrl(baseUrl);
                    segments = parseJson3(json);
                    clientTranslate = true;
                }
                if (!segments.isEmpty()) {
                    lastSourceLang = sourceLangCode;
                    if (clientTranslate) {
                        segments = TranscriptTranslator.translate(segments, targetLangCode);
                    }
                    return segments;
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Innertube caption fetch failed, trying direct", ex);
            }
        }

        return fetchDirect(videoId, targetLang);
    }

    private static String[] fetchFromInnertube(String videoId) throws Exception {
        String body = "{\"context\":{\"client\":{\"clientName\":\"ANDROID\","
                + "\"clientVersion\":\"20.10.38\"}},"
                + "\"videoId\":\"" + videoId + "\"}";

        //noinspection ExtractMethodRecommender
        HttpURLConnection conn = (HttpURLConnection) new URL(INNERTUBE_PLAYER_URL).openConnection();
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

        final int code = conn.getResponseCode();
        if (code != 200) throw new Exception("Innertube HTTP response: " + code);

        String response = Requester.parseString(conn);
        String targetLangCode = Settings.VOT_CAPTION_LANGUAGE.get().split("-")[0];
        return new String[]{findBestCaptionUrl(response, targetLangCode), extractPoToken(response)};
    }

    private static String extractPoToken(String json) {
        int idx = json.indexOf("\"poToken\":\"");
        if (idx < 0) return null;
        idx += "\"poToken\":\"".length();
        int end = json.indexOf('"', idx);
        return end < 0 ? null : json.substring(idx, end);
    }

    /**
     * Returns the caption URL that best matches {@code preferredLang} (ISO code, e.g. "uk").
     * Prefers a native track in the target language over translation; falls back to the first
     * non-gemini track if no match is found. "auto" (or any unmatched lang) uses the default.
     */
    private static String findBestCaptionUrl(String json, String preferredLang) {
        final int tracksIdx = json.indexOf("\"captionTracks\":[");
        if (tracksIdx < 0) return null;

        String firstUrl = null;
        String firstNonGemini = null;
        String preferredUrl = null;
        int searchFrom = tracksIdx;

        while (true) {
            int baseUrlIdx = json.indexOf("\"baseUrl\":\"", searchFrom);
            if (baseUrlIdx < 0 || baseUrlIdx > tracksIdx + 50_000) break;
            baseUrlIdx += "\"baseUrl\":\"".length();

            final int endIdx = json.indexOf('"', baseUrlIdx);
            if (endIdx < 0) break;

            String url = json.substring(baseUrlIdx, endIdx)
                             .replace("\\u0026", "&")
                             .replace("\\u003d", "=")
                             .replace("\\u003e", ">")
                             .replace("\\u003c", "<");

            if (firstUrl == null) firstUrl = url;
            final boolean nonGemini = !url.contains("variant=gemini");
            if (firstNonGemini == null && nonGemini) firstNonGemini = url;
            if (preferredUrl == null && nonGemini && !"auto".equals(preferredLang)) {
                String urlLang = extractLangFromUrl(url).split("-")[0];
                if (urlLang.equals(preferredLang)) preferredUrl = url;
            }

            searchFrom = endIdx + 1;
        }

        if (preferredUrl != null) return preferredUrl;
        return firstNonGemini != null ? firstNonGemini : firstUrl;
    }

    private static List<TranscriptSegment> fetchDirect(String videoId, String targetLang) {
        String targetLangCode = "auto".equals(targetLang) ? "" : targetLang.split("-")[0];

        // Try the target language first (catches non-English videos), then English ASR fallbacks.
        List<String> candidates = new ArrayList<>();
        if (!targetLangCode.isEmpty() && !"en".equals(targetLangCode)) {
            candidates.add(targetLangCode);
        }
        candidates.add("en");
        candidates.add("en-US");
        candidates.add("en-GB");

        for (String srcLang : candidates) {
            try {
                String urlStr = "https://www.youtube.com/api/timedtext?v=" + videoId
                        + "&lang=" + srcLang + "&kind=asr&fmt=json3";
                String srcLangCode = srcLang.split("-")[0];
                if (!targetLangCode.isEmpty() && !srcLangCode.equals(targetLangCode)) {
                    urlStr += "&tlang=" + targetLangCode;
                }
                String json = fetchUrl(urlStr);
                if (!json.isEmpty()) {
                    List<TranscriptSegment> segments = parseJson3(json);
                    if (!segments.isEmpty()) {
                        lastSourceLang = srcLangCode;
                        return segments;
                    }
                }
            } catch (Exception ex) {
                final String langFinal = srcLang;
                Logger.printDebug(() -> "Direct caption fetch failed lang: " + langFinal, ex);
            }
        }
        Logger.printDebug(() -> "No captions available for video: " + videoId);
        return new ArrayList<>();
    }

    private static String extractLangFromUrl(String url) {
        for (String prefix : new String[]{"&lang=", "?lang="}) {
            int idx = url.indexOf(prefix);
            if (idx >= 0) {
                idx += prefix.length();
                final int end = url.indexOf('&', idx);
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

        for (int i = 0, eventsLength = events.length(); i < eventsLength; i++) {
            JSONObject event = events.getJSONObject(i);
            JSONArray segs = event.optJSONArray("segs");
            if (segs == null) continue;

            final long startMs = event.optLong("tStartMs", -1);
            if (startMs < 0) continue;
            final long durationMs = event.optLong("dDurationMs", 2000);

            StringBuilder text = new StringBuilder();
            for (int j = 0, segsLength = segs.length(); j < segsLength; j++) {
                text.append(segs.getJSONObject(j).optString("utf8", ""));
            }

            String textStr = text.toString().replace('\n', ' ').trim();
            if (!textStr.isEmpty()) {
                segments.add(new TranscriptSegment(startMs,
                        startMs + Math.max(durationMs, 500), textStr));
            }
        }
        return segments;
    }

    private static String fetchUrl(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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

        final int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("HTTP response code: " + responseCode + " url: " + urlStr);
        }
        return Requester.parseString(conn);
    }
}
