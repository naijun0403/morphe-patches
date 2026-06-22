/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import android.media.AudioAttributes;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.util.Base64;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;

/**
 * Synthesizes speech via the Microsoft Edge TTS WebSocket API and plays the result
 * through Android's MediaPlayer on the NAVIGATION_GUIDANCE audio channel (independent
 * volume from YouTube's media stream).
 *
 * <p>One instance is shared for the lifetime of the patch; only one synthesis + playback
 * runs at a time (callers gate on {@link #isSpeaking()}).
 *
 * <p>The underlying WebSocket connection is kept alive across calls and only torn down
 * on an explicit error or when the server closes it.
 *
 * <p>State management is performed on the main thread to avoid complex synchronization.
 */
final class TtsEngine {

    public static final TtsEngine INSTANCE = new TtsEngine();

    private static final String WS_HOST            = "speech.platform.bing.com";
    private static final int    WS_PORT            = 443;
    private static final String TOKEN              = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String AUDIO_FORMAT       = "audio-24khz-48kbitrate-mono-mp3";
    // Keep in sync with the Edge browser version used in the User-Agent below.
    private static final String SEC_MS_GEC_VERSION = "1-143.0.3650.75";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS    = 20_000;
    // Close the persistent socket if it has been idle longer than this to avoid
    // "Connection reset" when the server drops an idle WebSocket connection.
    private static final long SOCKET_MAX_IDLE_MS = 20_000;

    public static final long ESTIMATED_MS_PER_CHAR = 65;
    // TODO: Allow changing this with a setting of low/medium/high?
    public final long PLAYBACK_ADJUST_LIMIT_MS = 2500;

    // All fields below (except synthesisLock related) must be accessed ONLY on the main thread.
    private boolean stopped;
    private boolean speaking;
    private MediaPlayer currentPlayer;
    private CountDownLatch playLatch;
    /** Tracks the active synthesis/playback session to prevent overlapping segments. */
    private long playbackId;

    // Ensures only one synthesis turn happens on the WebSocket at a time.
    private final Object synthesisLock = new Object();
    @GuardedBy("synthesisLock")
    private long lastSynthesisEndMs;
    @GuardedBy("synthesisLock")
    private SSLSocket persistentSocket;
    @GuardedBy("synthesisLock")
    private InputStream persistentIn;
    @GuardedBy("synthesisLock")
    private OutputStream persistentOut;
    @GuardedBy("synthesisLock")
    private boolean configSent;

    private TtsEngine() {}

    boolean isSpeaking() {
        Utils.verifyOnMainThread();
        return speaking;
    }

    /**
     * High-level entry point for Edge TTS speech at natural speed (rate=1.0).
     * Handles background synthesis and playback for the given voice.
     */
    void speak(String text, String voiceId, String lang, float volume, Runnable onDone) {
        speak(text, voiceId, lang, volume, 1.0f, 0, onDone);
    }

    /**
     * High-level entry point for Edge TTS speech starting at {@code startTimeMs}.
     */
    void speak(String text, String voiceId, String lang, float volume, float rate, long startTimeMs, Runnable onDone) {
        Utils.verifyOnMainThread();
        final long id = markBusy();

        Utils.runOnBackgroundThread(() -> {
            try {
                byte[] data = synthesize(text, voiceId, lang, rate);
                Utils.runOnMainThread(() -> {
                    if (data.length > 0 && !stopped && id == playbackId) {
                        // Must play based on playback rate. Underlying voice speaking rate
                        // is baked into synthesized data.
                        final float playbackRate = VideoInformation.getPlaybackSpeed();
                        play(data, volume, playbackRate, startTimeMs, id, onDone);
                    } else if (id == playbackId) {
                        speaking = false;
                        if (onDone != null) onDone.run();
                    }
                });
            } catch (Exception ex) {
                VoiceOverTranslationPatch.logError(() -> "Edge TTS speak failed", ex);
                Utils.runOnMainThread(() -> {
                    if (id == playbackId) {
                        speaking = false;
                        if (onDone != null) onDone.run();
                    }
                });
            }
        });
    }

    /**
     * Marks the engine as busy before synthesis or playback begins.
     * @return the unique ID for this playback session.
     */
    long markBusy() {
        Utils.verifyOnMainThread();
        stopped = false;
        playbackId++;
        speaking = true;
        return playbackId;
    }

    /** Clears the busy flag for a specific playback ID if it's still current. */
    void clearBusy(long id) {
        Utils.verifyOnMainThread();
        if (id == playbackId) {
            Logger.printDebug((() -> "clearing busy flag"));
            speaking = false;
        }
    }

    /** Returns the current active playback session ID. */
    long getPlaybackId() {
        Utils.verifyOnMainThread();
        return playbackId;
    }

    /**
     * Synthesizes {@code text} with the given Edge TTS {@code voice} on a background thread.
     */
    byte[] synthesize(String text, String voice, String lang, float rate) throws Exception {
        return synthesizeEdge(text, voice, lang, rate);
    }

    /** Overload for background synthesis (prefetching). */
    byte[] prefetch(String text, String voice, String lang) throws Exception {
        return synthesizeEdge(text, voice, lang, 1.0f);
    }

    /**
     * Returns the duration in milliseconds of an MP3 produced by this engine.
     * Valid only for {@link #AUDIO_FORMAT} (48 kbps CBR = 6000 bytes/second).
     */
    static long mp3DurationMs(int byteCount) {
        return byteCount * 1000L / 6000L;
    }

    /**
     * Plays the MP3 result through Android's MediaPlayer at natural speed (rate=1.0).
     */
    void play(byte[] mp3, float volume, long id, @Nullable Runnable onDone) {
        play(mp3, volume, 1.0f, 0, id, onDone);
    }

    /**
     * Plays the MP3 result through Android's MediaPlayer starting at {@code startTimeMs}.
     */
    void play(byte[] mp3, float volume, float rate, long startTimeMs, long id, @Nullable Runnable onDone) {
        Utils.verifyOnMainThread();

        // Reject audio that completed synthesis after stop() was called (e.g. post-seek).
        if (stopped || id != playbackId) {
            if (id == playbackId) {
                speaking = false;
                if (onDone != null) onDone.run();
            }
            return;
        }

        Utils.runOnBackgroundThread(() -> {
            try {
                // playMp3 blocks until completion or error.
                playMp3(mp3, volume, rate, startTimeMs, id);
            } catch (Exception ex) {
                Utils.runOnMainThread(() -> {
                    if (!stopped && id == playbackId) {
                        VoiceOverTranslationPatch.logError(() -> "Playback failed", ex);
                    }
                });
            } finally {
                Utils.runOnMainThread(() -> {
                    if (id == playbackId) {
                        speaking = false;
                        if (onDone != null) onDone.run();
                    }
                });
            }
        });
    }

    /** Stops any in-progress synthesis or playback immediately. */
    void stop() {
        Utils.verifyOnMainThread();
        if (!stopped) {
            Logger.printDebug(() -> "Stopping TTS");
        }
        stopped = true;
        speaking = false;

        // Unblock latch.await() in playMp3() so the thread exits quickly.
        if (playLatch != null) {
            playLatch.countDown();
            playLatch = null;
        }

        if (currentPlayer != null) {
            try {
                currentPlayer.setVolume(0, 0);
                currentPlayer.stop();
            } catch (Exception ex) {
                VoiceOverTranslationPatch.logError(() -> "MediaPlayer stop failed", ex);
            }
            try {
                currentPlayer.release();
            } catch (Exception ex) {
                VoiceOverTranslationPatch.logError(() -> "MediaPlayer release failed", ex);
            }
            currentPlayer = null;
        }
    }

    /**
     * Adjusts playback times for a contiguous block of segments to fit the actual spoken audio.
     */
    public void adjustPlaybackTimes(List<TranscriptSegment> segments, int index,
                                    String videoId, String voice, String lang) {
        if (index < 0 || index >= segments.size()) return;

        // Find contiguous block
        int startIdx = index;
        while (startIdx > 0 && segments.get(startIdx).startMs() == segments.get(startIdx - 1).endMs()) {
            startIdx--;
        }
        int endIdx = index;
        while (endIdx < segments.size() - 1 && segments.get(endIdx).endMs() == segments.get(endIdx + 1).startMs()) {
            endIdx++;
        }

        // Calculate total spoken duration
        long totalSpokenMs = 0;
        final long originalDurationMs = segments.get(endIdx).endMs() - segments.get(startIdx).startMs();

        for (int i = startIdx; i <= endIdx; i++) {
            TranscriptSegment s = segments.get(i);
            long duration = s.durationMs();
            if (duration <= 0) {
                duration = TtsCache.getDuration(videoId, i, voice, lang, s.text());
                if (duration > 0) s.setDurationMs(duration);
            }
            if (duration <= 0) {
                duration = s.text().length() * ESTIMATED_MS_PER_CHAR;
            }
            totalSpokenMs += duration;
        }

        // Calculate limits
        final long originalStart = segments.get(startIdx).startMs();
        final long originalEnd = segments.get(endIdx).endMs();

        final long gapStart = (startIdx > 0) ? segments.get(startIdx - 1).endMs() : 0;
        final long gapEnd = (endIdx < segments.size() - 1) ? segments.get(endIdx + 1).startMs() : Long.MAX_VALUE;

        // Available expansion at start is half the gap to the previous non-contiguous segment
        final long maxExpandStart = Math.min(PLAYBACK_ADJUST_LIMIT_MS, (originalStart - gapStart) / 2);
        // Available expansion at end is half the gap to the next non-contiguous segment
        final long maxExpandEnd = Math.min(PLAYBACK_ADJUST_LIMIT_MS, (gapEnd - originalEnd) / 2);

        long limitStart = originalStart - maxExpandStart;
        long limitEnd = originalEnd + maxExpandEnd;

        // Expand block if needed
        long newStart = originalStart;
        long newEnd = originalEnd;

        if (totalSpokenMs > originalDurationMs) {
            long needed = totalSpokenMs - originalDurationMs;

            // Prefer end expansion
            final long expandEnd = Math.min(needed, limitEnd - originalEnd);
            newEnd += expandEnd;
            needed -= expandEnd;

            if (needed > 0) {
                final long expandStart = Math.min(needed, originalStart - limitStart);
                newStart -= expandStart;
            }
        }

        // Redistribute internal boundaries
        long currentPos = newStart;
        final long totalWindow = newEnd - newStart;

        for (int i = startIdx; i <= endIdx; i++) {
            TranscriptSegment s = segments.get(i);
            long spoken = s.durationMs();
            if (spoken <= 0) {
                spoken = TtsCache.getDuration(videoId, i, voice, lang, s.text());
                if (spoken > 0) s.setDurationMs(spoken);
            }
            if (spoken <= 0) spoken = s.text().length() * ESTIMATED_MS_PER_CHAR;

            s.setPlaybackStartMs(currentPos);
            final long segmentWindow;
            if (i == endIdx) {
                segmentWindow = newEnd - currentPos;
            } else {
                final long idealEnd = currentPos + (long) (totalWindow * (spoken / (double) totalSpokenMs));
                // Clamp every boundary to respect the drift limit
                long clampedEnd = Math.max(s.endMs() - PLAYBACK_ADJUST_LIMIT_MS,
                        Math.min(s.endMs() + PLAYBACK_ADJUST_LIMIT_MS, idealEnd));
                // Ensure monotonicity
                clampedEnd = Math.max(currentPos, Math.min(newEnd, clampedEnd));
                segmentWindow = clampedEnd - currentPos;
            }
            currentPos += segmentWindow;
            s.setPlaybackEndMs(currentPos);
        }
    }

    private byte[] synthesizeEdge(String text, String voice, String lang, float rate) throws Exception {
        Utils.verifyOffMainThread();
        synchronized (synthesisLock) {
            IOException lastEx = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    String timestamp = edgeTimestamp();
                    String requestId = uuidHex();
                    String ssml = buildSsml(text, voice, lang, rate);

                    // Ensure we have a valid connection.
                    ensureConnected();

                    boolean needsConfig = !configSent;
                    if (needsConfig) configSent = true;

                    // speech.config only needs to be sent once per connection.
                    if (needsConfig) {
                        sendText(persistentOut, "Path: speech.config\r\n"
                                + "Content-Type: application/json; charset=utf-8\r\n"
                                + "X-Timestamp: " + timestamp + "\r\n\r\n"
                                + "{\"context\":{\"synthesis\":{\"audio\":{"
                                + "\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\","
                                + "\"wordBoundaryEnabled\":\"false\"},"
                                + "\"outputFormat\":\"" + AUDIO_FORMAT + "\"}}}}");
                    }

                    sendText(persistentOut, "Path: ssml\r\n"
                            + "X-RequestId: " + requestId + "\r\n"
                            + "X-Timestamp: " + timestamp + "\r\n"
                            + "Content-Type: application/ssml+xml\r\n\r\n" + ssml);

                    // Collect audio frames until the server signals turn.end.
                    ByteArrayOutputStream audioOut = new ByteArrayOutputStream();
                    collectAudio(persistentIn, audioOut);

                    lastSynthesisEndMs = System.currentTimeMillis();
                    return audioOut.toByteArray();
                } catch (IOException ex) {
                    closeSocket();
                    lastEx = ex;
                    Logger.printDebug(() -> "TTS synthesis failed, retrying... ", ex);
                }
            }
            throw lastEx;
        }
    }

    private String buildSsml(String text, String voice, String lang, float rate) {
        String inner = escapeXml(text);
        int ratePercent = Math.round((rate - 1.0f) * 100);
        if (ratePercent != 0) {
            inner = "<prosody rate='" + (ratePercent > 0 ? "+" : "") + ratePercent + "%'>"
                    + inner + "</prosody>";
        }
        final String speakLang = lang != null && !lang.isEmpty() ? lang : localePart(voice);
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis'"
                + " xml:lang='" + speakLang + "'>"
                + "<voice name='" + voice + "'>" + inner + "</voice></speak>";
    }

    /**
     * Opens a new TLS connection and performs the WebSocket upgrade handshake.
     */
    @GuardedBy("synthesisLock")
    private void ensureConnected() throws Exception {
        Utils.verifyOffMainThread();
        if (persistentSocket != null && !persistentSocket.isClosed()) {
            // Proactively close the socket if it has been idle long enough for the
            // server to have dropped it; avoids a "Connection reset" on the first send.
            if (System.currentTimeMillis() - lastSynthesisEndMs <= SOCKET_MAX_IDLE_MS) return;
            closeSocket();
        }

        String secMsGec     = genSecMsGec();
        String connectionId = uuidHex();
        String path = "/consumer/speech/synthesize/readaloud/edge/v1"
                + "?TrustedClientToken=" + TOKEN
                + "&ConnectionId=" + connectionId
                + "&Sec-MS-GEC=" + secMsGec
                + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION;

        SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
        try {
            socket.connect(new InetSocketAddress(WS_HOST, WS_PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            socket.startHandshake();
            InputStream in = socket.getInputStream();
            sendHttpUpgrade(socket, path);
            readHttpUpgrade(in);
            persistentSocket = socket;
            persistentOut    = socket.getOutputStream();
            persistentIn     = in;
            configSent       = false;
        } catch (Exception e) {
            try { socket.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    /**
     * Closes and nulls the persistent socket. Must be called with synthesisLock held.
     */
    @GuardedBy("synthesisLock")
    private void closeSocket() {
        Utils.verifyOffMainThread();
        if (persistentSocket != null) {
            try { persistentSocket.close(); } catch (Exception ignored) {}
            persistentSocket = null;
            persistentIn     = null;
            persistentOut    = null;
            configSent       = false;
        }
    }

    private void sendHttpUpgrade(SSLSocket socket, String path) throws IOException {
        String key  = Base64.encodeToString(randomBytes(16), Base64.NO_WRAP);
        String muid = uuidHex().toUpperCase(Locale.US);
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + WS_HOST + "\r\n"
                + "Pragma: no-cache\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold\r\n"
                + "Accept-Language: en-US,en;q=0.9\r\n"
                + "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                + " (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0\r\n"
                + "Cookie: muid=" + muid + "\r\n"
                + "\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private void readHttpUpgrade(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int b; (b = in.read()) != -1; ) {
            sb.append((char) b);
            if (sb.length() >= 4 && "\r\n\r\n".contentEquals(sb.subSequence(sb.length() - 4, sb.length()))) {
                if (!sb.toString().contains("101")) {
                    throw new IOException("WebSocket upgrade failed: " + sb.substring(0, Math.min(sb.length(), 120)));
                }
                return;
            }
        }
        throw new IOException("Connection closed during WebSocket handshake");
    }

    // WebSocket frame encoding - client-to-server frames must be masked per RFC 6455.
    private void sendText(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] mask    = randomBytes(4);
        int    len     = payload.length;

        // Frame header: FIN=1, opcode=0x1 (text), MASK=1.
        ByteArrayOutputStream buf = new ByteArrayOutputStream(len + 10);
        buf.write(0x81);
        if (len < 126) {
            buf.write(0x80 | len);
        } else if (len < 65536) {
            buf.write(0x80 | 126);
            buf.write((len >> 8) & 0xFF);
            buf.write(len & 0xFF);
        } else {
            buf.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) buf.write((len >> (8 * i)) & 0xFF);
        }
        buf.write(mask);
        for (int i = 0; i < len; i++) buf.write(payload[i] ^ mask[i % 4]);

        out.write(buf.toByteArray());
        out.flush();
    }

    /**
     * Reads WebSocket frames until a {@code Path:turn.end} text frame or a close frame.
     * Binary frames carry MP3 chunks; the first two bytes are the header length - strip
     * them and append the rest to {@code audioOut}.
     */
    private void collectAudio(InputStream in, ByteArrayOutputStream audioOut) throws IOException {
        for (int b0, b1; (b0 = in.read()) >= 0 && (b1 = in.read()) >= 0; ) {
            int  opcode     = b0 & 0x0F;
            boolean masked  = (b1 & 0x80) != 0;
            long payloadLen = b1 & 0x7F;

            if (payloadLen == 126) {
                payloadLen = ((long)(in.read() & 0xFF) << 8) | (in.read() & 0xFF);
            } else if (payloadLen == 127) {
                payloadLen = 0;
                for (int i = 0; i < 8; i++) payloadLen = (payloadLen << 8) | (in.read() & 0xFF);
            }

            byte[] maskBytes = null;
            if (masked) {
                maskBytes = new byte[4];
                readFully(in, maskBytes);
            }

            byte[] payload = new byte[(int) payloadLen];
            readFully(in, payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= maskBytes[i % 4];
            }

            if (opcode == 0x8) break; // close frame

            if (opcode == 0x1) { // text frame
                if (new String(payload, StandardCharsets.UTF_8).contains("Path:turn.end")) break;
            } else if (opcode == 0x2 && payload.length > 2) { // binary audio frame
                // First 2 bytes encode the header length; audio data starts after the header.
                int headerLen  = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                int audioStart = 2 + headerLen;
                if (audioStart < payload.length) {
                    audioOut.write(payload, audioStart, payload.length - audioStart);
                }
            }
            // opcode 0x0 (continuation), 0x9 (ping), 0xA (pong) - ignored
        }
    }

    private void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("Unexpected end of stream");
            off += n;
        }
    }

    private void playMp3(byte[] mp3, float volume, float rate, long startTimeMs, long id) throws Exception {
        Utils.verifyOffMainThread();
        CountDownLatch latch = new CountDownLatch(1);
        MediaPlayer mp = new MediaPlayer();

        Utils.runOnMainThread(() -> {
            if (stopped || id != playbackId) {
                mp.release();
                latch.countDown(); // Prevent await block
                return;
            }
            playLatch = latch;
            currentPlayer = mp;

            try {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                mp.setVolume(volume, volume);
                mp.setDataSource(new MediaDataSource() {
                    @Override
                    public int readAt(long position, byte[] buffer, int offset, int size) {
                        if (position >= mp3.length) return -1;
                        int pos   = (int) position;
                        int count = Math.min(size, mp3.length - pos);
                        System.arraycopy(mp3, pos, buffer, offset, count);
                        return count;
                    }

                    @Override
                    public long getSize() {
                        return mp3.length;
                    }

                    @Override
                    public void close() {}
                });
                mp.setOnCompletionListener(m -> latch.countDown());
                mp.setOnErrorListener((m, what, extra) -> {
                    Logger.printDebug(() -> "MediaPlayer error what: " + what + " extra: " + extra);
                    latch.countDown();
                    return true;
                });

                mp.prepare();
                if (rate != 1.0f) {
                    mp.setPlaybackParams(new PlaybackParams().setSpeed(rate));
                }
                if (stopped || id != playbackId) {
                    latch.countDown();
                    return;
                }
                if (startTimeMs > 0) {
                    mp.seekTo((int) startTimeMs);
                }
                mp.start();
            } catch (Exception ex) {
                Logger.printDebug(() -> "MediaPlayer setup failed", ex);
                latch.countDown();
            }
        });

        // Block background thread until playback finishes or is cancelled.
        // If play() was cancelled before reaching runOnMainThread above, latch will already be 0.
        final long timeoutMs = mp3DurationMs(mp3.length) + 10_000;
        final boolean awaitSuccessful = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        if (!awaitSuccessful) {
            VoiceOverTranslationPatch.logError(() -> "Waited for latch but was not successful", null);
        }

        Utils.runOnMainThread(() -> {
            if (playLatch == latch) {
                playLatch = null;
            }
            // Release only if stop() hasn't already done so.
            if (currentPlayer == mp) {
                try {
                    mp.stop();
                } catch (Exception ex) {
                    Logger.printDebug(() -> "MediaPlayer stop failed", ex);
                }
                try {
                    mp.release();
                } catch (Exception ex) {
                    Logger.printDebug(() -> "MediaPlayer release failed", ex);
                }
                currentPlayer = null;
            }
        });
    }

    /**
     * Generates the Sec-MS-GEC DRM token required by the Edge TTS WebSocket endpoint.
     * Algorithm: current time → Windows FILETIME ticks, rounded to 5 minutes,
     * concatenated with the trusted client token, SHA-256 hashed.
     */
    private static String genSecMsGec() throws Exception {
        // Windows FILETIME epoch offset: 100-nanosecond ticks from 1601-01-01 to 1970-01-01.
        final long EPOCH_OFFSET_TICKS = 116_444_736_000_000_000L;
        // 5 minutes expressed in 100-nanosecond ticks.
        final long FIVE_MIN_TICKS     = 3_000_000_000L;

        long ticks   = System.currentTimeMillis() * 10_000L + EPOCH_OFFSET_TICKS;
        long rounded = ticks - (ticks % FIVE_MIN_TICKS);

        String input = (rounded + TOKEN).toUpperCase(Locale.US);
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] hash = sha256.digest(input.getBytes(StandardCharsets.UTF_8));

        StringBuilder sb = new StringBuilder(64);
        for (byte b : hash) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    /** Extract locale portion from a voice name, e.g. "uk-UA-OstapNeural" → "uk-UA". */
    private static String localePart(String voice) {
        int third = voice.indexOf('-', voice.indexOf('-') + 1);
        return third > 0 ? voice.substring(0, third) : "en-US";
    }

    private static String edgeTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }

    private static String uuidHex() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("'", "&apos;")
                .replace("\"", "&quot;");
    }
}
