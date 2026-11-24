/**
 * @file CastWire.java
 * @summary Pure-Java Protobuf wire codec, ASN.1 DER X.509 builder, and YouTube URL parser for AndroidTV & Cast Utilities.
 * @description Encodes/decodes varint-framed Cast v2 and Android TV Remote v2 messages, builds self-signed RSA-2048 X.509 certs, and extracts YouTube video IDs and timestamps.
 * @testing Verified via Gradle Java 11 compilation and parity with the verified Python/Swift/Kotlin protocol test suite.
 */
package com.project.autoswitch.ui.cast;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CastWire {
    private CastWire() {}

    public static final class Field {
        public final int number;
        public final boolean isBytes;
        public final long varint;
        public final byte[] bytes;

        public Field(int number, long varint) {
            this.number = number;
            this.isBytes = false;
            this.varint = varint;
            this.bytes = null;
        }

        public Field(int number, byte[] bytes) {
            this.number = number;
            this.isBytes = true;
            this.varint = 0L;
            this.bytes = bytes;
        }
    }

    public static byte[] encodeVarint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long v = value;
        while ((v & ~0x7FL) != 0L) {
            out.write((int) ((v & 0x7FL) | 0x80L));
            v >>>= 7;
        }
        out.write((int) (v & 0x7FL));
        return out.toByteArray();
    }

    public static Long decodeVarint(byte[] data, int[] offsetRef) {
        long result = 0L;
        int shift = 0;
        int idx = offsetRef[0];
        while (idx < data.length) {
            int b = data[idx++] & 0xFF;
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) {
                offsetRef[0] = idx;
                return result;
            }
            shift += 7;
            if (shift >= 64) return null;
        }
        return null;
    }

    public static byte[] varintField(int fieldNumber, long value) {
        return concat(encodeVarint(((long) fieldNumber << 3) | 0L), encodeVarint(value));
    }

    public static byte[] bytesField(int fieldNumber, byte[] payload) {
        return concat(encodeVarint(((long) fieldNumber << 3) | 2L), encodeVarint(payload.length), payload);
    }

    public static byte[] stringField(int fieldNumber, String text) {
        return bytesField(fieldNumber, text.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] frameVarint(byte[] payload) {
        return concat(encodeVarint(payload.length), payload);
    }

    public static List<Field> parseFields(byte[] data) {
        List<Field> out = new ArrayList<>();
        int[] offset = new int[]{0};
        while (offset[0] < data.length) {
            Long tag = decodeVarint(data, offset);
            if (tag == null) break;
            int fieldNumber = (int) (tag >>> 3);
            int wireType = (int) (tag & 0x07L);
            if (wireType == 0) {
                Long v = decodeVarint(data, offset);
                if (v == null) return out;
                out.add(new Field(fieldNumber, v));
            } else if (wireType == 1) {
                if (offset[0] + 8 > data.length) return out;
                offset[0] += 8;
            } else if (wireType == 2) {
                Long lenObj = decodeVarint(data, offset);
                if (lenObj == null) return out;
                int len = lenObj.intValue();
                if (len < 0 || offset[0] + len > data.length) return out;
                byte[] slice = Arrays.copyOfRange(data, offset[0], offset[0] + len);
                offset[0] += len;
                out.add(new Field(fieldNumber, slice));
            } else if (wireType == 5) {
                if (offset[0] + 4 > data.length) return out;
                offset[0] += 4;
            } else {
                return out;
            }
        }
        return out;
    }

    public static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] a : arrays) {
            if (a != null && a.length > 0) out.write(a, 0, a.length);
        }
        return out.toByteArray();
    }

    // MARK: - Minimal ASN.1 DER Builder for X.509 v3 Certificates

    public static byte[] derTlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = content.length;
        if (len < 0x80) {
            out.write(len);
        } else {
            List<Integer> lenBytes = new ArrayList<>();
            int rem = len;
            while (rem > 0) {
                lenBytes.add(0, rem & 0xFF);
                rem >>>= 8;
            }
            out.write(0x80 | lenBytes.size());
            for (int b : lenBytes) out.write(b);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    public static byte[] derSequence(byte[]... parts) {
        return derTlv(0x30, concat(parts));
    }

    public static byte[] derSet(byte[]... parts) {
        return derTlv(0x31, concat(parts));
    }

    public static byte[] derInteger(byte[] raw) {
        int idx = 0;
        while (idx < raw.length - 1 && raw[idx] == 0) idx++;
        byte[] trimmed = Arrays.copyOfRange(raw, idx, raw.length);
        if ((trimmed[0] & 0x80) != 0) {
            trimmed = concat(new byte[]{0}, trimmed);
        }
        return derTlv(0x02, trimmed);
    }

    public static byte[] derBitString(byte[] data) {
        return derTlv(0x03, concat(new byte[]{0}, data));
    }

    public static byte[] derNull() {
        return new byte[]{0x05, 0x00};
    }

    public static byte[] derOid(int... bytes) {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) b[i] = (byte) bytes[i];
        return derTlv(0x06, b);
    }

    public static byte[] derUtf8(String text) {
        return derTlv(0x0C, text.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] derUtcTime(String text) {
        return derTlv(0x17, text.getBytes(StandardCharsets.US_ASCII));
    }

    public static byte[] derExplicit(int index, byte[] content) {
        return derTlv(0xA0 | index, content);
    }

    public static byte[] stripLeadingZero(byte[] bytes) {
        int idx = 0;
        while (idx < bytes.length - 1 && bytes[idx] == 0) idx++;
        return Arrays.copyOfRange(bytes, idx, bytes.length);
    }

    // MARK: - YouTube Link Parser

    public static final class ParsedLink {
        public final String videoId;
        public final int startSeconds;

        public ParsedLink(String videoId, int startSeconds) {
            this.videoId = videoId;
            this.startSeconds = startSeconds;
        }
    }

    private static final Pattern P_WATCH = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})");
    private static final Pattern P_SHORT = Pattern.compile("youtu\\.be/([A-Za-z0-9_-]{11})");
    private static final Pattern P_PATH = Pattern.compile("/(shorts|embed|live)/([A-Za-z0-9_-]{11})");
    private static final Pattern P_BARE = Pattern.compile("^([A-Za-z0-9_-]{11})$");
    private static final Pattern P_TIME = Pattern.compile("[?&]t=([^&]+)");
    private static final Pattern P_H = Pattern.compile("(\\d+)h");
    private static final Pattern P_M = Pattern.compile("(\\d+)m");
    private static final Pattern P_S = Pattern.compile("(\\d+)s");

    public static ParsedLink parseYouTubeLink(String raw) {
        if (raw == null) return new ParsedLink(null, 0);
        String text = raw.trim();
        String videoId = null;
        Matcher m;
        if ((m = P_WATCH.matcher(text)).find()) videoId = m.group(1);
        else if ((m = P_SHORT.matcher(text)).find()) videoId = m.group(1);
        else if ((m = P_PATH.matcher(text)).find()) videoId = m.group(2);
        else if ((m = P_BARE.matcher(text)).find()) videoId = m.group(1);

        int start = 0;
        Matcher tm = P_TIME.matcher(text);
        if (tm.find()) {
            start = parseStart(tm.group(1));
        }
        return new ParsedLink(videoId, start);
    }

    private static int parseStart(String raw) {
        if (raw == null) return 0;
        String t = raw.trim();
        if (t.endsWith("s")) {
            try { return Math.max(0, Integer.parseInt(t.substring(0, t.length() - 1))); } catch (Exception ignored) {}
        }
        try { return Math.max(0, Integer.parseInt(t)); } catch (Exception ignored) {}
        int total = 0;
        Matcher mh = P_H.matcher(t);
        if (mh.find()) total += Integer.parseInt(mh.group(1)) * 3600;
        Matcher mm = P_M.matcher(t);
        if (mm.find()) total += Integer.parseInt(mm.group(1)) * 60;
        Matcher ms = P_S.matcher(t);
        if (ms.find()) total += Integer.parseInt(ms.group(1));
        return Math.max(0, total);
    }
}
