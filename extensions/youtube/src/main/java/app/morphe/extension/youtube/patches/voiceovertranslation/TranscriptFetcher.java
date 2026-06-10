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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.patches.CaptionCookiesPatch;
import app.morphe.extension.youtube.settings.Settings;

final class TranscriptFetcher {

    /** Language code detected from the video's own caption track. Updated on every successful fetch. */
    static volatile String lastSourceLang = "en";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final String INNERTUBE_PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";

    /**
     * Fetches and, if needed, translates the transcript. The returned list is immediately
     * usable; when translation spans multiple batches, later batches are published
     * asynchronously through {@code onUpdate} (see {@link TranscriptTranslator#translate}).
     * {@code cancelled} is polled before each background batch so translation of an
     * abandoned video stops early.
     */
    static List<TranscriptSegment> fetch(String videoId, Consumer<List<TranscriptSegment>> onUpdate,
                                         BooleanSupplier cancelled) {
        List<TranscriptSegment> segments = fetchEnglishSegments(videoId);

        if (!segments.isEmpty()) {
            String targetLang = Settings.VOT_CAPTION_LANGUAGE.get();
            String targetLangCode = "auto".equals(targetLang) ? "" : targetLang.split("-")[0];
            // Skip translation when the caption track is already in the target language.
            if (!targetLangCode.isEmpty() && !targetLangCode.equals(lastSourceLang)) {
                segments = TranscriptTranslator.translate(segments, targetLangCode, onUpdate, cancelled);
            }
        }

        return segments;
    }

    private static List<TranscriptSegment> fetchEnglishSegments(String videoId) {
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
                String baseUrl = captionUrl.replaceAll("&fmt=[^&]*", "") + "&fmt=json3";
                if (poToken != null) baseUrl += "&pot=" + poToken;

                String json = fetchUrl(baseUrl);
                List<TranscriptSegment> segments = parseJson3(json);
                if (!segments.isEmpty()) {
                    lastSourceLang = extractLangFromUrl(captionUrl).split("-")[0];
                    return segments;
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Innertube caption fetch failed, trying direct", ex);
            }
        }

        return fetchDirect(videoId);
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
        return new String[]{findBestCaptionUrl(response), extractPoToken(response)};
    }

    private static String extractPoToken(String json) {
        int idx = json.indexOf("\"poToken\":\"");
        if (idx < 0) return null;
        idx += "\"poToken\":\"".length();
        int end = json.indexOf('"', idx);
        return end < 0 ? null : json.substring(idx, end);
    }

    /**
     * Returns an English caption URL from the captionTracks list.
     * Prefers a non-gemini English track; falls back to the first non-gemini track,
     * then the first available.
     */
    private static String findBestCaptionUrl(String json) {
        final int tracksIdx = json.indexOf("\"captionTracks\":[");
        if (tracksIdx < 0) return null;

        String firstUrl = null;
        String firstNonGemini = null;
        String englishUrl = null;
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
            if (englishUrl == null && nonGemini) {
                String urlLang = extractLangFromUrl(url).split("-")[0];
                if ("en".equals(urlLang)) englishUrl = url;
            }

            searchFrom = endIdx + 1;
        }

        if (englishUrl != null) return englishUrl;
        return firstNonGemini != null ? firstNonGemini : firstUrl;
    }

    private static List<TranscriptSegment> fetchDirect(String videoId) {
        for (String srcLang : new String[]{"en", "en-US", "en-GB"}) {
            try {
                String urlStr = "https://www.youtube.com/api/timedtext?v=" + videoId
                        + "&lang=" + srcLang + "&kind=asr&fmt=json3";
                String json = fetchUrl(urlStr);
                if (!json.isEmpty()) {
                    List<TranscriptSegment> segments = parseJson3(json);
                    if (!segments.isEmpty()) {
                        lastSourceLang = "en";
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

    // Flush a merged sentence when it grows past this size, to keep TTS utterances manageable.
    private static final int MAX_SENTENCE_CHARS = 300;
    // A silence gap longer than this between lines starts a new utterance even mid-sentence.
    private static final long MAX_SENTENCE_GAP_MS = 1_500;

    private static List<TranscriptSegment> parseJson3(String json) throws Exception {
        List<TranscriptSegment> lines = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray events = root.optJSONArray("events");
        if (events == null) return lines;

        for (int i = 0, eventsLength = events.length(); i < eventsLength; i++) {
            JSONObject event = events.getJSONObject(i);
            // ASR streams emit append events that only carry a "\n" to scroll the
            // 2-line caption window. They duplicate timing of real lines - skip them.
            if (event.optInt("aAppend", 0) == 1) continue;

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
            // Drop sound effect markers such as [Applause] or [Music] - they should not be spoken.
            if (textStr.startsWith("[") && textStr.endsWith("]")) continue;
            if (!textStr.isEmpty()) {
                lines.add(new TranscriptSegment(startMs,
                        startMs + Math.max(durationMs, 500), textStr));
            }
        }

        // dDurationMs is display time: with ASR a line stays on screen while the next
        // line is already spoken, so ranges overlap. Clamp each end to the next start
        // so segments represent actual speech time instead of caption visibility.
        for (int i = 0, last = lines.size() - 1; i < last; i++) {
            TranscriptSegment cur = lines.get(i);
            long nextStart = lines.get(i + 1).startMs();
            if (cur.endMs() > nextStart) {
                lines.set(i, new TranscriptSegment(cur.startMs(), nextStart, cur.text()));
            }
        }

        return mergeIntoSentences(lines);
    }

    /**
     * Merges caption lines into sentence-sized segments so TTS speaks whole sentences
     * without pauses at line breaks. A sentence ends on terminal punctuation,
     * a long silence gap, or when the accumulated text grows too large.
     */
    private static List<TranscriptSegment> mergeIntoSentences(List<TranscriptSegment> lines) {
        List<TranscriptSegment> sentences = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        long startMs = 0;
        long endMs;

        for (int i = 0, size = lines.size(); i < size; i++) {
            TranscriptSegment line = lines.get(i);
            if (text.length() == 0) {
                startMs = line.startMs();
            } else {
                text.append(' ');
            }
            text.append(line.text());
            endMs = line.endMs();

            final boolean sentenceEnd = endsSentence(text);
            final boolean tooLong = text.length() >= MAX_SENTENCE_CHARS;
            final boolean lastLine = i == size - 1;
            final boolean longGap = !lastLine
                    && lines.get(i + 1).startMs() - endMs > MAX_SENTENCE_GAP_MS;

            if (sentenceEnd || tooLong || longGap || lastLine) {
                sentences.add(new TranscriptSegment(startMs, endMs, text.toString()));
                text.setLength(0);
            }
        }
        return sentences;
    }

    private static boolean endsSentence(CharSequence text) {
        if (text.length() == 0) return false;
        final char c = text.charAt(text.length() - 1);
        return c == '.' || c == '!' || c == '?' || c == '…';
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
