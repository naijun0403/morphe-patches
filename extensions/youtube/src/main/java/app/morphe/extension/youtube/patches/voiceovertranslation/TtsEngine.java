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
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Synthesizes speech via the Microsoft Edge TTS WebSocket API and plays the result
 * through Android's MediaPlayer on the NAVIGATION_GUIDANCE audio channel (independent
 * volume from YouTube's media stream).
 *
 * <p>One instance is shared for the lifetime of the patch; {@link #speak} is non-blocking
 * and only one synthesis + playback runs at a time (callers gate on {@link #isSpeaking()}).
 *
 * <p>The underlying WebSocket connection is kept alive across calls and only torn down
 * on {@link #stop()} or when the server closes it. Reconnection is transparent to callers.
 */
final class TtsEngine {

    private static final String WS_HOST            = "speech.platform.bing.com";
    private static final int    WS_PORT            = 443;
    private static final String TOKEN              = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    private static final String AUDIO_FORMAT       = "audio-24khz-48kbitrate-mono-mp3";
    // Keep in sync with the Edge browser version used in the User-Agent below.
    private static final String SEC_MS_GEC_VERSION = "1-143.0.3650.75";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS    = 20_000;

    private final AtomicBoolean                     stopped       = new AtomicBoolean();
    private final AtomicBoolean                     speaking      = new AtomicBoolean();
    private final AtomicReference<MediaPlayer>      currentPlayer = new AtomicReference<>();
    private final AtomicReference<CountDownLatch>   playLatch     = new AtomicReference<>();

    private final Object lock = new Object();
    @GuardedBy("lock")
    private SSLSocket     persistentSocket;
    @GuardedBy("lock")
    private InputStream   persistentIn;
    @GuardedBy("lock")
    private OutputStream  persistentOut;
    // speech.config only needs to be sent once per connection.
    @GuardedBy("lock")
    private boolean       configSent;

    // Exponential moving average of synthesis latency (request to audio ready).
    // Callers subtract it from the time budget when computing speech rate, so the
    // delay before playback starts does not eat into the speaking time.
    private final AtomicLong averageSynthesisMs = new AtomicLong(600);

    boolean isSpeaking() {
        return speaking.get();
    }

    long averageSynthesisMs() {
        return averageSynthesisMs.get();
    }

    /**
     * Synthesizes {@code text} with the given Edge TTS {@code voice} on a background thread.
     * This is the underlying synthesis logic used by {@link #speak} and the prefetcher.
     */
    byte[] synthesize(String text, String voice, float rate) throws Exception {
        return synthesizeEdge(text, voice, rate);
    }

    /**
     * Plays the MP3 result through Android's MediaPlayer at the given speed {@code rate}.
     * Use 1.0f when the rate is already encoded in the audio (e.g. via SSML prosody).
     * This is the underlying playback logic used by {@link #speak} and the prefetcher.
     */
    void play(byte[] mp3, float volume, float rate, Runnable onDone) {
        stopped.set(false);
        speaking.set(true);

        Utils.runOnBackgroundThread(() -> {
            try {
                if (!stopped.get()) playMp3(mp3, volume, rate);
            } catch (Exception ex) {
                if (!stopped.get()) Logger.printException(() -> "Playback failed", ex);
            } finally {
                speaking.set(false);
                if (onDone != null) Utils.runOnMainThread(onDone);
            }
        });
    }

    /**
     * Non-blocking. Synthesizes {@code text} with the given Edge TTS {@code voice} on a
     * background thread, then plays the MP3 result. {@code rate} is a speed multiplier
     * (1.0 = normal). Calls {@code onDone} (on the main thread) when playback finishes
     * or on any failure.
     */
    void speak(String text, String voice, float volume, float rate, Runnable onDone) {
        stopped.set(false);
        speaking.set(true);

        Utils.runOnBackgroundThread(() -> {
            try {
                if (stopped.get()) return;
                final long synthStart = System.currentTimeMillis();
                byte[] mp3 = synthesize(text, voice, rate);
                if (mp3.length == 0) {
                    Logger.printDebug(() -> "Empty audio for «"
                            + text.substring(0, Math.min(text.length(), 50)) + "»");
                    return;
                }

                // Update the latency average only on successful synthesis.
                final long synthMs = System.currentTimeMillis() - synthStart;
                averageSynthesisMs.updateAndGet(avg -> (avg * 3 + synthMs) / 4);
                // Rate is already encoded in the SSML prosody element; play at 1.0x.
                if (!stopped.get()) playMp3(mp3, volume, 1.0f);
            } catch (Exception ex) {
                // Suppress exceptions caused by stop() closing the connection mid-request.
                if (!stopped.get()) Logger.printException(() -> "Speak failed", ex);
            } finally {
                speaking.set(false);
                if (onDone != null) Utils.runOnMainThread(onDone);
            }
        });
    }

    /** Stops any in-progress synthesis or playback immediately. */
    void stop() {
        stopped.set(true);
        speaking.set(false);

        synchronized (lock) {
            closeSocket();
        }

        // Unblock latch.await() in playMp3() so the thread exits quickly.
        CountDownLatch l = playLatch.getAndSet(null);
        if (l != null) l.countDown();

        MediaPlayer mp = currentPlayer.getAndSet(null);
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
        }
    }

    private byte[] synthesizeEdge(String text, String voice, float rate) throws Exception {
        synchronized (lock) {
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    // Reconnect lazily if the socket was closed (first call, stop(), or server timeout).
                    ensureConnected();

                    String timestamp = edgeTimestamp();
                    String requestId = uuidHex();

                    // speech.config is sent once per connection.
                    if (!configSent) {
                        sendText(persistentOut, "Path: speech.config\r\n"
                                + "Content-Type: application/json; charset=utf-8\r\n"
                                + "X-Timestamp: " + timestamp + "\r\n\r\n"
                                + "{\"context\":{\"synthesis\":{\"audio\":{"
                                + "\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\","
                                + "\"wordBoundaryEnabled\":\"false\"},"
                                + "\"outputFormat\":\"" + AUDIO_FORMAT + "\"}}}}");
                        configSent = true;
                    }

                    // SSML synthesis request. Edge TTS expects rate as a percentage delta, e.g. "+30%".
                    String inner = escapeXml(text);
                    int ratePercent = Math.round((rate - 1.0f) * 100);
                    if (ratePercent != 0) {
                        inner = "<prosody rate='" + (ratePercent > 0 ? "+" : "") + ratePercent + "%'>"
                                + inner + "</prosody>";
                    }
                    String ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis'"
                            + " xml:lang='" + localePart(voice) + "'>"
                            + "<voice name='" + voice + "'>" + inner + "</voice></speak>";
                    sendText(persistentOut, "Path: ssml\r\n"
                            + "X-RequestId: " + requestId + "\r\n"
                            + "X-Timestamp: " + timestamp + "\r\n"
                            + "Content-Type: application/ssml+xml\r\n\r\n" + ssml);

                    // Collect audio frames until the server signals turn.end.
                    ByteArrayOutputStream audioOut = new ByteArrayOutputStream();
                    collectAudio(persistentIn, audioOut);
                    return audioOut.toByteArray();
                } catch (IOException ex) {
                    // Socket died (e.g. server idle timeout, Connection reset).
                    // Drop it so the next attempt reconnects cleanly.
                    closeSocket();
                    if (attempt >= 2 || stopped.get()) throw ex;
                    Logger.printDebug(() -> "TTS synthesis failed, retrying... ", ex);
                }
            }
        }
        throw new IOException("Synthesis failed");
    }

    /**
     * Opens a new TLS connection and performs the WebSocket upgrade handshake.
     * Must be called with class lock held.
     */
    private void ensureConnected() throws Exception {
        if (persistentSocket != null && !persistentSocket.isClosed()) return;

        String secMsGec     = genSecMsGec();
        String connectionId = uuidHex();
        String path = "/consumer/speech/synthesize/readaloud/edge/v1"
                + "?TrustedClientToken=" + TOKEN
                + "&ConnectionId=" + connectionId
                + "&Sec-MS-GEC=" + secMsGec
                + "&Sec-MS-GEC-Version=" + SEC_MS_GEC_VERSION;

        SSLSocket socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
        socket.connect(new InetSocketAddress(WS_HOST, WS_PORT), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        socket.startHandshake();

        // Assign streams before the handshake readers so they are available immediately.
        persistentSocket = socket;
        persistentOut    = socket.getOutputStream();
        persistentIn     = socket.getInputStream();
        configSent       = false;

        sendHttpUpgrade(socket, path);
        readHttpUpgrade(persistentIn);
    }

    /**
     * Closes and nulls the persistent socket. Must be called with class lock held.
     */
    private void closeSocket() {
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

    private void playMp3(byte[] mp3, float volume, float rate) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        MediaPlayer    mp    = new MediaPlayer();
        playLatch.set(latch);
        currentPlayer.set(mp);

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
            if (stopped.get()) return;
            mp.start();
            //noinspection ResultOfMethodCallIgnored
            latch.await(60, TimeUnit.SECONDS);
        } finally {
            playLatch.compareAndSet(latch, null);
            // Release only if stop() hasn't already done so.
            if (currentPlayer.compareAndSet(mp, null)) {
                try { mp.stop(); }    catch (Exception ignored) {}
                try { mp.release(); } catch (Exception ignored) {}
            }
        }
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