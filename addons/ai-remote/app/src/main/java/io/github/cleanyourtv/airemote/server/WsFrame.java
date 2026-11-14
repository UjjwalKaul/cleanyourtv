package io.github.cleanyourtv.airemote.server;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Lightweight RFC-6455 WebSocket framing decoder and encoder.
 */
public final class WsFrame {

    public static final int OPCODE_TEXT = 0x1;
    public static final int OPCODE_CLOSE = 0x8;
    public static final int OPCODE_PING = 0x9;
    public static final int OPCODE_PONG = 0xA;

    public static class Frame {
        public final int opcode;
        public final byte[] payload;

        public Frame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }

        public String getText() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    private WsFrame() {}

    /**
     * Reads a single RFC-6455 frame from the InputStream.
     */
    public static Frame readFrame(InputStream in) throws IOException {
        int b1 = in.read();
        if (b1 == -1) throw new EOFException("WebSocket stream ended");
        int opcode = b1 & 0x0F;

        int b2 = in.read();
        if (b2 == -1) throw new EOFException("WebSocket stream ended");
        boolean masked = (b2 & 0x80) != 0;
        long payloadLength = b2 & 0x7F;

        if (payloadLength == 126) {
            int high = in.read();
            int low = in.read();
            if ((high | low) < 0) throw new EOFException("WebSocket stream ended");
            payloadLength = ((high & 0xFF) << 8) | (low & 0xFF);
        } else if (payloadLength == 127) {
            payloadLength = 0;
            for (int i = 0; i < 8; i++) {
                int b = in.read();
                if (b < 0) throw new EOFException("WebSocket stream ended");
                payloadLength = (payloadLength << 8) | (b & 0xFF);
            }
        }

        if (payloadLength > 1024 * 1024) { // 1 MB sanity limit
            throw new IOException("WebSocket frame payload too large: " + payloadLength);
        }

        byte[] mask = new byte[4];
        if (masked) {
            readFully(in, mask, 4);
        }

        byte[] payload = new byte[(int) payloadLength];
        readFully(in, payload, payload.length);

        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i % 4]);
            }
        }

        return new Frame(opcode, payload);
    }

    /**
     * Sends a text frame synchronized on the target stream only (no global monitor lock).
     */
    public static void sendText(OutputStream out, String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        sendFrame(out, OPCODE_TEXT, payload);
    }

    public static void sendFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(payload.length + 10);
        // FIN bit set (0x80) | opcode
        buf.write(0x80 | (opcode & 0x0F));

        int len = payload.length;
        if (len <= 125) {
            buf.write(len);
        } else if (len <= 65535) {
            buf.write(126);
            buf.write((len >> 8) & 0xFF);
            buf.write(len & 0xFF);
        } else {
            buf.write(127);
            for (int i = 7; i >= 0; i--) {
                buf.write((int) ((len >> (i * 8)) & 0xFF));
            }
        }

        buf.write(payload);
        byte[] bytes = buf.toByteArray();

        synchronized (out) {
            out.write(bytes);
            out.flush();
        }
    }

    private static void readFully(InputStream in, byte[] b, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int count = in.read(b, read, len - read);
            if (count < 0) throw new EOFException("Unexpected end of WebSocket stream");
            read += count;
        }
    }
}
