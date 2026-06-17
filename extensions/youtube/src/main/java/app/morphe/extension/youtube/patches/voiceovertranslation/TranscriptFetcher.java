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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.patches.CaptionCookiesPatch;

final class TranscriptFetcher {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final String INNERTUBE_PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";

    /** Language code detected from the video's own caption track. Updated on every successful fetch. */
    static volatile String lastSourceLang = "en";

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
            String targetLangCode = VoiceOverTranslationPatch.resolveTargetLang();
            // Skip translation when the caption track is already in the target language.
            if (!isSameSpokenLanguage(targetLangCode, lastSourceLang)) {
                segments = TranscriptTranslator.translate(videoId, segments, targetLangCode, onUpdate, cancelled);
            }
        }

        return segments;
    }

    public static boolean isSameSpokenLanguage(String target, String source) {
        if (target == null || source == null) return false;
        if (target.equalsIgnoreCase(source)) return true; // Direct match (handles pt-BR == pt-BR)

        // If Portuguese but didn't match directly above, the regions are different (e.g., pt-BR != pt-PT)
        if (target.startsWith("pt-")) return false;

        // For everything else, strip the region and compare base languages (e.g., en-US == en-GB)
        return VoiceCatalog.getIso639(target).equalsIgnoreCase(VoiceCatalog.getIso639(source));
    }

    private static List<TranscriptSegment> fetchEnglishSegments(String videoId) {
        String captionUrl = null;
        String poToken    = null;
        try {
            String[] innertubeResult = fetchFromInnertube(videoId);
            captionUrl = innertubeResult[0];
            poToken    = innertubeResult[1];
        } catch (Exception ex) {
            Logger.printDebug(() -> "Innertube player failed", ex);
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
                Logger.printDebug(() -> "Caption fetch failed, trying direct", ex);
            }
        }

        return fetchDirect(videoId);
    }

    private static String[] fetchFromInnertube(String videoId) throws Exception {
        Utils.verifyOffMainThread();

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
        if (code != 200) throw new Exception("Unexpected response status: " + code);

        String response = Requester.parseString(conn);
        return new String[]{findBestCaptionUrl(response), extractPoToken(response)};
    }

    @Nullable
    private static String extractPoToken(String json) {
        int idx = json.indexOf("\"poToken\":\"");
        if (idx < 0) return null;
        idx += "\"poToken\":\"".length();
        final int end = json.indexOf('"', idx);
        return end < 0 ? null : json.substring(idx, end);
    }

    /**
     * Returns the best caption URL from the captionTracks list.
     * Prefers a non-gemini track in the target language; falls back to a non-gemini
     * English track, then the first non-gemini track, then the first available.
     */
    @Nullable
    private static String findBestCaptionUrl(String json) {
        final int tracksIdx = json.indexOf("\"captionTracks\":[");
        if (tracksIdx < 0) return null;

        String targetLang = VoiceOverTranslationPatch.resolveTargetLangIso639();
        String firstUrl = null;
        String firstNonGemini = null;
        String targetLangUrl = null;
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

            String urlLang = extractLangFromUrl(url).split("-")[0];
            if (targetLangUrl == null && nonGemini && targetLang.equals(urlLang)) targetLangUrl = url;
            if (englishUrl == null && nonGemini && "en".equals(urlLang)) englishUrl = url;

            searchFrom = endIdx + 1;
        }

        if (targetLangUrl != null) return targetLangUrl;
        if (englishUrl != null) return englishUrl;
        return firstNonGemini != null ? firstNonGemini : firstUrl;
    }

    private static List<TranscriptSegment> fetchDirect(String videoId) {
        for (String srcLang : List.of("en", "en-US", "en-GB")) {
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
        for (String prefix : List.of("&lang=", "?lang=")) {
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
    // Small gaps between segments are closed when they are below this threshold.
    private static final long CLOSE_GAP_THRESHOLD_MS = 2500;

    // Heuristics for old ASR tracks that have no punctuation at all.
    // A pause this long is treated as a sentence boundary on its own.
    private static final long UNPUNCTUATED_GAP_MS = 700;
    // A shorter pause counts as a boundary only when the next line starts with a capital.
    private static final long UNPUNCTUATED_SOFT_GAP_MS = 250;
    // Tighter length cap so unpunctuated chunks stay short and re-sync with the video often.
    private static final int MAX_UNPUNCTUATED_CHARS = 200;

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

        // Adjust timings to ensure contiguity for small gaps and resolve overlaps.
        // dDurationMs is display time: with ASR a line stays on screen while the next
        // line is already spoken, so ranges overlap. Clamp each end to the next start
        // so segments represent actual speech time instead of caption visibility.
        // Small gaps are also closed to ensure smooth TTS flow.
        for (int i = 0, last = lines.size() - 1; i < last; i++) {
            TranscriptSegment cur = lines.get(i);
            TranscriptSegment next = lines.get(i + 1);

            if (cur.endMs() > next.startMs()) {
                // Overlap: shrink current segment's end.
                lines.set(i, new TranscriptSegment(cur.startMs(), next.startMs(), cur.text()));
            } else if (next.startMs() - cur.endMs() <= CLOSE_GAP_THRESHOLD_MS) {
                // Small gap: expand each segment time duration to take up the gap.
                final long mid = (cur.endMs() + next.startMs()) / 2;
                lines.set(i, new TranscriptSegment(cur.startMs(), mid, cur.text()));
                lines.set(i + 1, new TranscriptSegment(mid, next.endMs(), next.text()));
            }
        }

        return mergeIntoSentences(lines);
    }

    /**
     * Merges caption lines into sentence-sized segments so TTS speaks whole sentences
     * without pauses at line breaks. For punctuated transcripts a sentence ends on
     * terminal punctuation; old ASR tracks have no punctuation at all, so boundaries
     * are approximated from speech pauses and capitalization instead.
     */
    private static List<TranscriptSegment> mergeIntoSentences(List<TranscriptSegment> lines) {
        final boolean punctuated = detectPunctuation(lines);
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

            boolean flush;
            if (i == size - 1) {
                flush = true;
            } else {
                final long gap = lines.get(i + 1).startMs() - endMs;
                if (punctuated) {
                    flush = endsSentence(text)
                            || text.length() >= MAX_SENTENCE_CHARS
                            || gap > MAX_SENTENCE_GAP_MS;
                } else {
                    flush = gap > UNPUNCTUATED_GAP_MS
                            || (gap > UNPUNCTUATED_SOFT_GAP_MS
                            && startsWithUpperCase(lines.get(i + 1).text()))
                            || text.length() >= MAX_UNPUNCTUATED_CHARS;
                }
            }

            if (flush) {
                sentences.add(new TranscriptSegment(startMs, endMs, text.toString()));
                text.setLength(0);
            }
        }
        return sentences;
    }

    /**
     * Returns true when a meaningful share of lines contains terminal punctuation.
     * Old auto-generated tracks contain none, so punctuation can't be trusted there
     * as a sentence boundary signal.
     */
    private static boolean detectPunctuation(List<TranscriptSegment> lines) {
        int punctuatedLines = 0;
        for (TranscriptSegment line : lines) {
            String t = line.text();
            for (int i = 0, len = t.length(); i < len; i++) {
                final char c = t.charAt(i);
                if (c == '.' || c == '!' || c == '?') {
                    punctuatedLines++;
                    break;
                }
            }
        }
        // At least ~10% of lines must carry punctuation - the occasional "$5.99"
        // in an otherwise unpunctuated track should not flip the mode.
        return punctuatedLines * 10 >= lines.size();
    }

    private static boolean startsWithUpperCase(String text) {
        for (int i = 0, len = text.length(); i < len; i++) {
            final char c = text.charAt(i);
            if (Character.isLetter(c)) return Character.isUpperCase(c);
        }
        return false;
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
