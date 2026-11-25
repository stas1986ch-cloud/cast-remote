/**
 * @file CastAndTvEngine.java
 * @summary Pure-Java Cast v2 TLS (8009), YouTube Lounge HTTP, Android TV Remote v2 (6466/6467), and Phone Receiver (8766) engine.
 * @description Provides zero-dependency LAN Chromecast playback/mirroring/wake/stop and Mutual TLS Android TV PIN pairing and state-aware power control for both phones and Google TVs.
 * @testing Compiled cleanly under Java 11 / Android SDK 35 and verified against Cast v2 and androidtvremote2 wire specs.
 */
package com.project.autoswitch.ui.cast;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public final class CastAndTvEngine {
    public static final String YOUTUBE_APP_ID = "233637DE";
    public static final int ATV_PAIRING_PORT = 6467;
    public static final int ATV_REMOTE_PORT = 6466;
    public static final long KEYCODE_VOLUME_UP = 24L;
    public static final long KEYCODE_VOLUME_DOWN = 25L;
    public static final long KEYCODE_POWER = 26L;
    public static final long KEYCODE_VOLUME_MUTE = 164L;
    public static final long KEYCODE_SLEEP = 223L;
    public static final long KEYCODE_WAKEUP = 224L;

    private static final String NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String NS_RECEIVER = "urn:x-cast:com.google.cast.receiver";
    private static final String NS_MEDIA = "urn:x-cast:com.google.cast.media";
    private static final String NS_MDX = "urn:x-cast:com.google.youtube.mdx";
    private static final String SENDER_ID = "sender-0";
    private static final String RECEIVER_ID = "receiver-0";

    private static final String PREFS = "CastUtilitiesPrefs";
    private static final String KEY_PAIRED = "atv_paired_ids";

    public enum PowerAction { POWER_ON, POWER_OFF, TOGGLE }

    public static final class MediaStatus {
        public final String state;
        public final String title;
        public final String contentId;
        public final double position;
        public final double volumeLevel; // 0.0 .. 1.0 (-1 if unknown)
        public final boolean isMuted;
        public final String volumeControlType; // "attenuation", "master", or "fixed"

        public MediaStatus(String state, String title, String contentId, double position,
                           double volumeLevel, boolean isMuted, String volumeControlType) {
            this.state = state;
            this.title = title;
            this.contentId = contentId;
            this.position = position;
            this.volumeLevel = volumeLevel;
            this.isMuted = isMuted;
            this.volumeControlType = volumeControlType;
        }
    }

    private static final class CastMsg {
        final String namespace;
        final String payload;
        CastMsg(String namespace, String payload) {
            this.namespace = namespace;
            this.payload = payload;
        }
    }

    private static final class ClientIdentity {
        final X509Certificate cert;
        final KeyStore keyStore;
        ClientIdentity(X509Certificate cert, KeyStore keyStore) {
            this.cert = cert;
            this.keyStore = keyStore;
        }
    }

    private final Context appContext;
    private ClientIdentity cachedIdentity;
    private SSLSocket activePairingSocket;
    private X509Certificate activeServerCert;
    private String activePairingDeviceId;

    public CastAndTvEngine(Context context) {
        this.appContext = context.getApplicationContext();
    }

    // MARK: - Cast v2 TLS Client (Port 8009)

    private SSLSocket openCastSocket(String host, int port, int timeoutMs) throws Exception {
        TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new SecureRandom());
        SSLSocket sock = (SSLSocket) ctx.getSocketFactory().createSocket();
        sock.connect(new InetSocketAddress(host, port), timeoutMs);
        sock.setSoTimeout(timeoutMs);
        sock.startHandshake();
        return sock;
    }

    private void writeCastMsg(DataOutputStream out, String destId, String ns, String json) throws IOException {
        byte[] body = CastWire.concat(
                CastWire.varintField(1, 0),
                CastWire.stringField(2, SENDER_ID),
                CastWire.stringField(3, destId),
                CastWire.stringField(4, ns),
                CastWire.varintField(5, 0),
                CastWire.stringField(6, json)
        );
        out.writeInt(body.length);
        out.write(body);
        out.flush();
    }

    private CastMsg readCastMsg(DataInputStream inp, DataOutputStream out) throws IOException {
        while (true) {
            int len = inp.readInt();
            if (len <= 0 || len > 1_048_576) throw new IOException("Invalid CastV2 length: " + len);
            byte[] buf = new byte[len];
            inp.readFully(buf);
            String ns = "";
            String payload = "";
            for (CastWire.Field f : CastWire.parseFields(buf)) {
                if (f.isBytes && f.bytes != null) {
                    if (f.number == 4) ns = new String(f.bytes, StandardCharsets.UTF_8);
                    else if (f.number == 6) payload = new String(f.bytes, StandardCharsets.UTF_8);
                }
            }
            if (NS_HEARTBEAT.equals(ns) && payload.contains("\"PING\"")) {
                writeCastMsg(out, RECEIVER_ID, NS_HEARTBEAT, "{\"type\":\"PONG\"}");
                continue;
            }
            return new CastMsg(ns, payload);
        }
    }

    private String findYouTubeTransportId(JSONObject statusJson) {
        JSONObject st = statusJson.optJSONObject("status");
        JSONArray apps = st != null ? st.optJSONArray("applications") : null;
        if (apps == null) return null;
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app != null && YOUTUBE_APP_ID.equals(app.optString("appId"))) {
                String tid = app.optString("transportId", "");
                if (!tid.isEmpty()) return tid;
            }
        }
        return null;
    }

    private String findRunningSessionId(JSONObject statusJson) {
        JSONObject st = statusJson.optJSONObject("status");
        JSONArray apps = st != null ? st.optJSONArray("applications") : null;
        if (apps == null) return null;
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.optJSONObject(i);
            if (app != null && !app.optBoolean("isIdleScreen", false)) {
                String sid = app.optString("sessionId", "");
                if (!sid.isEmpty()) return sid;
            }
        }
        return null;
    }

    public MediaStatus getMediaStatus(String host, int port) throws Exception {
        try (SSLSocket sock = openCastSocket(host, port, 5000)) {
            DataInputStream inp = new DataInputStream(sock.getInputStream());
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            writeCastMsg(out, RECEIVER_ID, NS_CONNECTION, "{\"type\":\"CONNECT\"}");
            writeCastMsg(out, RECEIVER_ID, NS_RECEIVER, "{\"type\":\"GET_STATUS\",\"requestId\":1}");

            String tid = null;
            double volLevel = -1.0;
            boolean volMuted = false;
            String volType = "attenuation";

            for (int i = 0; i < 6; i++) {
                CastMsg msg = readCastMsg(inp, out);
                if (NS_RECEIVER.equals(msg.namespace) && msg.payload.contains("RECEIVER_STATUS")) {
                    JSONObject rJson = new JSONObject(msg.payload);
                    JSONObject rStatus = rJson.optJSONObject("status");
                    if (rStatus != null) {
                        JSONObject vol = rStatus.optJSONObject("volume");
                        if (vol != null) {
                            volLevel = vol.optDouble("level", -1.0);
                            volMuted = vol.optBoolean("muted", false);
                            volType = vol.optString("controlType", "attenuation");
                        }
                    }
                    tid = findYouTubeTransportId(rJson);
                    break;
                }
            }
            if (tid == null) {
                return new MediaStatus("IDLE", "", null, 0.0, volLevel, volMuted, volType);
            }

            writeCastMsg(out, tid, NS_CONNECTION, "{\"type\":\"CONNECT\"}");
            writeCastMsg(out, tid, NS_MEDIA, "{\"type\":\"GET_STATUS\",\"requestId\":2}");

            for (int i = 0; i < 6; i++) {
                CastMsg msg = readCastMsg(inp, out);
                if (NS_MEDIA.equals(msg.namespace) && msg.payload.contains("MEDIA_STATUS")) {
                    JSONArray arr = new JSONObject(msg.payload).optJSONArray("status");
                    JSONObject st = arr != null ? arr.optJSONObject(0) : null;
                    if (st == null) return new MediaStatus("IDLE", "", null, 0.0, volLevel, volMuted, volType);
                    String pState = st.optString("playerState", "IDLE");
                    JSONObject media = st.optJSONObject("media");
                    String cid = media != null ? media.optString("contentId", "") : "";
                    if (cid.length() != 11) cid = null;
                    JSONObject meta = media != null ? media.optJSONObject("metadata") : null;
                    String title = meta != null ? meta.optString("title", "") : "";
                    double pos = st.optDouble("currentTime", 0.0);
                    return new MediaStatus(pState, title, cid, pos, volLevel, volMuted, volType);
                }
            }
            return new MediaStatus("IDLE", "", null, 0.0, volLevel, volMuted, volType);
        }
    }

    public double setCastVolume(String host, int port, double level) throws Exception {
        double clamped = Math.max(0.0, Math.min(1.0, level));
        try (SSLSocket sock = openCastSocket(host, port, 5000)) {
            DataInputStream inp = new DataInputStream(sock.getInputStream());
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            writeCastMsg(out, RECEIVER_ID, NS_CONNECTION, "{\"type\":\"CONNECT\"}");
            String payload = String.format(Locale.US,
                    "{\"type\":\"SET_VOLUME\",\"volume\":{\"level\":%.2f},\"requestId\":10}", clamped);
            writeCastMsg(out, RECEIVER_ID, NS_RECEIVER, payload);
            for (int i = 0; i < 4; i++) {
                CastMsg msg = readCastMsg(inp, out);
                if (NS_RECEIVER.equals(msg.namespace) && msg.payload.contains("RECEIVER_STATUS")) {
                    JSONObject st = new JSONObject(msg.payload).optJSONObject("status");
                    JSONObject vol = st != null ? st.optJSONObject("volume") : null;
                    if (vol != null) return vol.optDouble("level", clamped);
                    break;
                }
            }
            return clamped;
        }
    }

    public boolean setCastMuted(String host, int port, boolean muted) throws Exception {
        try (SSLSocket sock = openCastSocket(host, port, 5000)) {
            DataInputStream inp = new DataInputStream(sock.getInputStream());
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            writeCastMsg(out, RECEIVER_ID, NS_CONNECTION, "{\"type\":\"CONNECT\"}");
            String payload = "{\"type\":\"SET_VOLUME\",\"volume\":{\"muted\":" + (muted ? "true" : "false") + "},\"requestId\":11}";
            writeCastMsg(out, RECEIVER_ID, NS_RECEIVER, payload);
            for (int i = 0; i < 4; i++) {
                CastMsg msg = readCastMsg(inp, out);
                if (NS_RECEIVER.equals(msg.namespace) && msg.payload.contains("RECEIVER_STATUS")) {
                    JSONObject st = new JSONObject(msg.payload).optJSONObject("status");
                    JSONObject vol = st != null ? st.optJSONObject("volume") : null;
                    if (vol != null) return vol.optBoolean("muted", muted);
                    break;
                }
            }
            return muted;
        }
    }

    public String launchYouTubeAndGetScreenId(String host, int port) throws Exception {
        try (SSLSocket sock = openCastSocket(host, port, 10000)) {
            DataInputStream inp = new DataInputStream(sock.getInputStream());
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            writeCastMsg(out, RECEIVER_ID, NS_CONNECTION, "{\"type\":\"CONNECT\"}");
            writeCastMsg(out, RECEIVER_ID, NS_RECEIVER, "{\"type\":\"GET_STATUS\",\"requestId\":1}");

            String tid = null;
            for (int i = 0; i < 5; i++) {
                CastMsg msg = readCastMsg(inp, out);
                if (NS_RECEIVER.equals(msg.namespace) && msg.payload.contains("RECEIVER_STATUS")) {
                    tid = findYouTubeTransportId(new JSONObject(msg.payload));
                    break;
                }
            }
            if (tid == null) {
                writeCastMsg(out, RECEIVER_ID, NS_RECEIVER, "{\"type\":\"LAUNCH\",\"appId\":\"" + YOUTUBE_APP_ID + "\",\"requestId\":2}");
                for (int i = 0; i < 12; i++) {
                    CastMsg msg = readCastMsg(inp, out);
                    if (NS_RECEIVER.equals(msg.namespace) && msg.payload.contains("RECEIVER_STATUS")) {
                        tid = findYouTubeTransportId(new JSONObject(msg.payload));
                        if (tid != null) break;
                    }
                }
            }
            if (tid == null) throw new IOException("Could not launch YouTube on TV");
            writeCastMsg(out, tid, NS_CONNECTION, "{\"type\":\"CONNECT\"}");
            writeCastMsg(out, tid, NS_MDX, "{\"type\":\"getMdxSessionStatus\"}");

            for (int i = 0; i < 10; i++) {
                CastMsg msg = readCastMsg(inp, out);
                if (NS_MDX.equals(msg.namespace) && msg.payload.contains("mdxSessionStatus")) {
                    JSONObject data = new JSONObject(msg.payload).optJSONObject("data");
                    String screenId = data != null ? data.optString("screenId", "") : "";
                    if (!screenId.isEmpty()) return screenId;
                }
            }
            throw new IOException("No YouTube screenId from TV");
        }
    }

    public void wakeViaCast(String host, int port) throws Exception {
        try (SSLSocket sock = openCastSocket(host, port, 8000)) {
            DataInputStream inp = new DataInputStream(sock.getInputStream());
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            writeCastMsg(out, RECEIVER_ID, NS_CONNECTION, "{\"type\":\"CONNECT\"}");
            writeCastMsg(out, RECEIVER_ID, NS_RECEIVER, "{\"type\":\"LAUNCH\",\"appId\":\"" + YOUTUBE_APP_ID + "\",\"requestId\":1}");
            try { readCastMsg(inp, out); } catch (Exception ignored) {}
        }
    }

    public void stopCastApp(String host, int port) throws Exception {
        try (SSLSocket sock = openCastSocket(host, port, 6000)) {
            DataInputStream inp = new DataInputStream(sock.getInputStream());
            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            writeCastMsg(out, RECEIVER_ID, NS_CONNECTION, "{\"type\":\"CONNECT\"}");
            writeCastMsg(out, RECEIVER_ID, NS_RECEIVER, "{\"type\":\"GET_STATUS\",\"requestId\":1}");
            String sid = null;
            for (int i = 0; i < 5; i++) {
                CastMsg msg = readCastMsg(inp, out);
                if (NS_RECEIVER.equals(msg.namespace) && msg.payload.contains("RECEIVER_STATUS")) {
                    sid = findRunningSessionId(new JSONObject(msg.payload));
                    break;
                }
            }
            if (sid != null) {
                writeCastMsg(out, RECEIVER_ID, NS_RECEIVER, "{\"type\":\"STOP\",\"sessionId\":\"" + sid + "\",\"requestId\":2}");
                try { readCastMsg(inp, out); } catch (Exception ignored) {}
            }
        }
    }

    // MARK: - YouTube Lounge HTTP

    private static final Pattern P_SID = Pattern.compile("\"c\",\"(.*?)\",");
    private static final Pattern P_GSESSION = Pattern.compile("\"S\",\"(.*?)\"\\]");

    public void playYouTubeOnScreen(String screenId, String videoId, int startSeconds) throws Exception {
        String tokenResp = postForm("https://www.youtube.com/api/lounge/pairing/get_lounge_token_batch",
                new String[][]{{"screen_ids", screenId}}, null, null);
        JSONArray screens = new JSONObject(tokenResp).optJSONArray("screens");
        String loungeToken = (screens != null && screens.length() > 0)
                ? screens.getJSONObject(0).optString("loungeToken", "") : "";
        if (loungeToken.isEmpty()) throw new IOException("Empty lounge token");

        String[][] bindForm = new String[][]{
                {"device", "REMOTE_CONTROL"},
                {"id", "aaaaaaaaaaaaaaaaaaaaaaaaaa"},
                {"name", "AndroidTVAndCastUtilities"},
                {"mdx-version", "3"},
                {"pairing_type", "cast"},
                {"app", "android-phone-13.14.55"}
        };
        String bindResp = postForm("https://www.youtube.com/api/lounge/bc/bind",
                bindForm, new String[][]{{"RID", "0"}, {"VER", "8"}, {"CVER", "1"}}, loungeToken);
        Matcher ms = P_SID.matcher(bindResp);
        Matcher mg = P_GSESSION.matcher(bindResp);
        if (!ms.find() || !mg.find()) throw new IOException("YouTube Lounge bind failed");
        String sid = ms.group(1);
        String gsession = mg.group(1);

        String[][] playForm = new String[][]{
                {"req0_listId", ""},
                {"req0__sc", "setPlaylist"},
                {"req0_currentTime", String.valueOf(Math.max(0, startSeconds))},
                {"req0_currentIndex", "-1"},
                {"req0_audioOnly", "false"},
                {"req0_videoId", videoId},
                {"count", "1"}
        };
        postForm("https://www.youtube.com/api/lounge/bc/bind",
                playForm,
                new String[][]{{"SID", sid}, {"gsessionid", gsession}, {"RID", "1"}, {"VER", "8"}, {"CVER", "1"}},
                loungeToken);
    }

    private String postForm(String baseUrl, String[][] form, String[][] query, String loungeToken) throws Exception {
        StringBuilder urlSb = new StringBuilder(baseUrl);
        if (query != null && query.length > 0) {
            urlSb.append("?");
            for (int i = 0; i < query.length; i++) {
                if (i > 0) urlSb.append("&");
                urlSb.append(enc(query[i][0])).append("=").append(enc(query[i][1]));
            }
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(urlSb.toString()).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Origin", "https://www.youtube.com/");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        if (loungeToken != null) conn.setRequestProperty("X-YouTube-LoungeId-Token", loungeToken);

        StringBuilder bodySb = new StringBuilder();
        for (int i = 0; i < form.length; i++) {
            if (i > 0) bodySb.append("&");
            bodySb.append(enc(form[i][0])).append("=").append(enc(form[i][1]));
        }
        byte[] payload = bodySb.toString().getBytes(StandardCharsets.UTF_8);
        conn.getOutputStream().write(payload);
        int code = conn.getResponseCode();
        InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String resp = readAll(stream);
        conn.disconnect();
        if (code < 200 || code >= 300) throw new IOException("YouTube Lounge HTTP " + code);
        return resp;
    }

    public void sendToPhone(String host, int port, String videoId, int startSeconds) throws Exception {
        String formattedHost = (host.contains(":") && !host.startsWith("[")) ? "[" + host + "]" : host;
        HttpURLConnection conn = (HttpURLConnection) new URL("http://" + formattedHost + ":" + port + "/play").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        byte[] payload = new JSONObject().put("video_id", videoId).put("start", Math.max(0, startSeconds))
                .toString().getBytes(StandardCharsets.UTF_8);
        conn.getOutputStream().write(payload);
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code < 200 || code >= 300) throw new IOException("Phone receiver HTTP " + code);
    }

    private String enc(String v) throws Exception {
        return URLEncoder.encode(v, "UTF-8").replace("+", "%20");
    }

    private String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        br.close();
        return sb.toString();
    }

    // MARK: - Android TV Remote Protocol v2 (Ports 6467 / 6466)

    public boolean isPaired(String deviceId) {
        SharedPreferences p = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> set = p.getStringSet(KEY_PAIRED, new HashSet<>());
        return set != null && set.contains(deviceId);
    }

    private void markPaired(String deviceId) {
        SharedPreferences p = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> next = new HashSet<>(p.getStringSet(KEY_PAIRED, new HashSet<>()));
        next.add(deviceId);
        p.edit().putStringSet(KEY_PAIRED, next).apply();
    }

    private synchronized ClientIdentity loadOrCreateIdentity() throws Exception {
        if (cachedIdentity != null) return cachedIdentity;
        File keyFile = new File(appContext.getFilesDir(), "atv_client_key.pkcs8");
        File certFile = new File(appContext.getFilesDir(), "atv_client_cert.der");
        PrivateKey privKey;
        X509Certificate cert;

        if (keyFile.exists() && certFile.exists()) {
            privKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(readFileBytes(keyFile)));
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(readFileBytes(certFile)));
        } else {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048, new SecureRandom());
            KeyPair kp = kpg.generateKeyPair();
            byte[] certDer = buildSelfSignedCert(kp.getPrivate(), kp.getPublic().getEncoded());
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certDer));
            privKey = kp.getPrivate();
            writeFileBytes(keyFile, privKey.getEncoded());
            writeFileBytes(certFile, certDer);
        }

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("atv", privKey, new char[0], new java.security.cert.Certificate[]{cert});
        cachedIdentity = new ClientIdentity(cert, ks);
        return cachedIdentity;
    }

    private byte[] buildSelfSignedCert(PrivateKey privKey, byte[] spkiDer) throws Exception {
        byte[] sigAlg = CastWire.derSequence(
                CastWire.derOid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B),
                CastWire.derNull()
        );
        byte[] version = CastWire.derExplicit(0, CastWire.derInteger(new byte[]{0x02}));
        byte[] serialBytes = new byte[8];
        new SecureRandom().nextBytes(serialBytes);
        serialBytes[0] = (byte) ((serialBytes[0] & 0x7F) | 0x01);
        byte[] serial = CastWire.derInteger(serialBytes);
        byte[] dn = CastWire.derSequence(CastWire.derSet(CastWire.derSequence(
                CastWire.derOid(0x55, 0x04, 0x03),
                CastWire.derUtf8("AndroidTVAndCastUtilities")
        )));
        byte[] validity = CastWire.derSequence(
                CastWire.derUtcTime("240101000000Z"),
                CastWire.derUtcTime("491231235959Z")
        );
        byte[] tbs = CastWire.derSequence(version, serial, sigAlg, dn, validity, dn, spkiDer);
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privKey);
        sig.update(tbs);
        return CastWire.derSequence(tbs, sigAlg, CastWire.derBitString(sig.sign()));
    }

    private SSLSocket openMutualTls(String host, int port, int timeoutMs, X509Certificate[] serverCertOut) throws Exception {
        ClientIdentity id = loadOrCreateIdentity();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(id.keyStore, new char[0]);
        X509TrustManager tm = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {
                if (chain != null && chain.length > 0 && serverCertOut != null) {
                    serverCertOut[0] = chain[0];
                }
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(kmf.getKeyManagers(), new TrustManager[]{tm}, new SecureRandom());
        SSLSocket sock = (SSLSocket) ctx.getSocketFactory().createSocket();
        sock.connect(new InetSocketAddress(host, port), timeoutMs);
        sock.setSoTimeout(timeoutMs);
        sock.startHandshake();
        return sock;
    }

    private void writeAtvFramed(SSLSocket sock, byte[] payload) throws IOException {
        sock.getOutputStream().write(CastWire.frameVarint(payload));
        sock.getOutputStream().flush();
    }

    private byte[] readAtvFramed(SSLSocket sock) throws IOException {
        DataInputStream inp = new DataInputStream(sock.getInputStream());
        int length = 0;
        int shift = 0;
        while (true) {
            int b = inp.readUnsignedByte();
            length |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift >= 32) throw new IOException("Malformed varint length");
        }
        if (length <= 0 || length > 1_048_576) throw new IOException("Invalid ATV frame length " + length);
        byte[] buf = new byte[length];
        inp.readFully(buf);
        return buf;
    }

    private void assertPairingOk(byte[] msg) throws IOException {
        for (CastWire.Field f : CastWire.parseFields(msg)) {
            if (f.number == 2 && !f.isBytes && f.varint != 200L) {
                throw new IOException("TV rejected pairing (status " + f.varint + ")");
            }
        }
    }

    private byte[] pairingEnvelope(int payloadField, byte[] payload) {
        return CastWire.concat(
                CastWire.varintField(1, 2),
                CastWire.varintField(2, 200),
                CastWire.bytesField(payloadField, payload)
        );
    }

    public void beginPairing(String deviceId, String host) throws Exception {
        cancelPairing();
        if (host == null || host.isEmpty()) throw new IOException("TV IP unavailable");
        X509Certificate[] serverCertHolder = new X509Certificate[1];
        SSLSocket sock = openMutualTls(host, ATV_PAIRING_PORT, 8000, serverCertHolder);
        try {
            byte[] req = CastWire.concat(
                    CastWire.stringField(1, "com.project.autoswitch.ui"),
                    CastWire.stringField(2, "AndroidTV & Cast Utilities")
            );
            writeAtvFramed(sock, pairingEnvelope(10, req));
            assertPairingOk(readAtvFramed(sock));

            byte[] enc = CastWire.concat(CastWire.varintField(1, 3), CastWire.varintField(2, 6));
            byte[] opt = CastWire.concat(CastWire.bytesField(1, enc), CastWire.varintField(3, 1));
            writeAtvFramed(sock, pairingEnvelope(20, opt));
            assertPairingOk(readAtvFramed(sock));

            byte[] cfg = CastWire.concat(CastWire.bytesField(1, enc), CastWire.varintField(2, 1));
            writeAtvFramed(sock, pairingEnvelope(30, cfg));
            assertPairingOk(readAtvFramed(sock));

            activePairingSocket = sock;
            activeServerCert = serverCertHolder[0];
            activePairingDeviceId = deviceId;
        } catch (Exception e) {
            try { sock.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    public void completePairing(String pinHex) throws Exception {
        if (activePairingSocket == null || activeServerCert == null || activePairingDeviceId == null) {
            throw new IOException("No active pairing session");
        }
        String cleaned = pinHex == null ? "" : pinHex.trim().toUpperCase(Locale.US);
        if (!cleaned.matches("^[0-9A-F]{6}$")) {
            throw new IOException("Enter the 6-character hex code shown on your TV");
        }
        byte[] pinBytes = new byte[3];
        for (int i = 0; i < 3; i++) {
            pinBytes[i] = (byte) Integer.parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
        }
        RSAPublicKey clientPub = (RSAPublicKey) loadOrCreateIdentity().cert.getPublicKey();
        RSAPublicKey serverPub = (RSAPublicKey) activeServerCert.getPublicKey();

        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(CastWire.stripLeadingZero(clientPub.getModulus().toByteArray()));
        sha.update(CastWire.stripLeadingZero(clientPub.getPublicExponent().toByteArray()));
        sha.update(CastWire.stripLeadingZero(serverPub.getModulus().toByteArray()));
        sha.update(CastWire.stripLeadingZero(serverPub.getPublicExponent().toByteArray()));
        sha.update(pinBytes, 1, 2);
        byte[] digest = sha.digest();
        if (digest[0] != pinBytes[0]) {
            throw new IOException("PIN did not match — check the TV screen and retry");
        }
        writeAtvFramed(activePairingSocket, pairingEnvelope(40, CastWire.bytesField(1, digest)));
        assertPairingOk(readAtvFramed(activePairingSocket));
        markPaired(activePairingDeviceId);
        cancelPairing();
    }

    public void cancelPairing() {
        if (activePairingSocket != null) {
            try { activePairingSocket.close(); } catch (Exception ignored) {}
        }
        activePairingSocket = null;
        activeServerCert = null;
        activePairingDeviceId = null;
    }

    public Boolean sendPower(String deviceId, String host, PowerAction action) throws Exception {
        if (host == null || host.isEmpty()) throw new IOException("TV IP unavailable");
        if (!isPaired(deviceId)) throw new IOException("TV needs one-time PIN pairing");
        try (SSLSocket sock = openMutualTls(host, ATV_REMOTE_PORT, 6000, null)) {
            Boolean isPoweredOn = null;
            boolean configured = false;
            boolean activeSet = false;

            for (int step = 0; step < 8; step++) {
                byte[] msg = readAtvFramed(sock);
                for (CastWire.Field f : CastWire.parseFields(msg)) {
                    if (f.number == 1) {
                        byte[] info = CastWire.concat(
                                CastWire.stringField(1, Build.MODEL),
                                CastWire.stringField(2, Build.MANUFACTURER),
                                CastWire.varintField(3, 1),
                                CastWire.stringField(4, "1"),
                                CastWire.stringField(5, "com.screencontrol.cast"),
                                CastWire.stringField(6, "3.0")
                        );
                        byte[] cfg = CastWire.concat(CastWire.varintField(1, 622), CastWire.bytesField(2, info));
                        writeAtvFramed(sock, CastWire.bytesField(1, cfg));
                        configured = true;
                    } else if (f.number == 2) {
                        writeAtvFramed(sock, CastWire.bytesField(2, CastWire.varintField(1, 622)));
                        activeSet = true;
                    } else if (f.number == 8 && f.isBytes && f.bytes != null) {
                        long val1 = 0L;
                        for (CastWire.Field pf : CastWire.parseFields(f.bytes)) {
                            if (pf.number == 1 && !pf.isBytes) { val1 = pf.varint; break; }
                        }
                        writeAtvFramed(sock, CastWire.bytesField(9, CastWire.varintField(1, val1)));
                    } else if (f.number == 40 && f.isBytes && f.bytes != null) {
                        boolean started = false;
                        for (CastWire.Field sf : CastWire.parseFields(f.bytes)) {
                            if (sf.number == 1 && !sf.isBytes) { started = (sf.varint != 0L); break; }
                        }
                        isPoweredOn = started;
                    }
                }
                if (configured && activeSet && isPoweredOn != null) break;
            }

            Long keyCode;
            Boolean expectedState;
            if (action == PowerAction.POWER_ON) {
                keyCode = Boolean.FALSE.equals(isPoweredOn) ? KEYCODE_POWER : KEYCODE_WAKEUP;
                expectedState = true;
            } else if (action == PowerAction.POWER_OFF) {
                keyCode = Boolean.TRUE.equals(isPoweredOn) ? KEYCODE_POWER : KEYCODE_SLEEP;
                expectedState = false;
            } else {
                keyCode = KEYCODE_POWER;
                expectedState = isPoweredOn != null ? !isPoweredOn : null;
            }

            if (keyCode != null) {
                byte[] inject = CastWire.concat(CastWire.varintField(1, keyCode), CastWire.varintField(2, 3));
                writeAtvFramed(sock, CastWire.bytesField(10, inject));
                try { Thread.sleep(250); } catch (InterruptedException ignored) {}
            }
            return expectedState;
        }
    }

    public void sendRemoteKey(String deviceId, String host, long keyCode) throws Exception {
        if (host == null || host.isEmpty()) throw new IOException("TV IP unavailable");
        if (!isPaired(deviceId)) throw new IOException("TV not paired for remote keys");
        try (SSLSocket sock = openMutualTls(host, ATV_REMOTE_PORT, 5000, null)) {
            boolean configured = false;
            boolean activeSet = false;
            for (int step = 0; step < 6; step++) {
                byte[] msg = readAtvFramed(sock);
                for (CastWire.Field f : CastWire.parseFields(msg)) {
                    if (f.number == 1) {
                        byte[] info = CastWire.concat(
                                CastWire.stringField(1, Build.MODEL),
                                CastWire.stringField(2, Build.MANUFACTURER),
                                CastWire.varintField(3, 1),
                                CastWire.stringField(4, "1"),
                                CastWire.stringField(5, "com.screencontrol.cast"),
                                CastWire.stringField(6, "2.6")
                        );
                        byte[] cfg = CastWire.concat(CastWire.varintField(1, 622), CastWire.bytesField(2, info));
                        writeAtvFramed(sock, CastWire.bytesField(1, cfg));
                        configured = true;
                    } else if (f.number == 2) {
                        writeAtvFramed(sock, CastWire.bytesField(2, CastWire.varintField(1, 622)));
                        activeSet = true;
                    } else if (f.number == 8 && f.isBytes && f.bytes != null) {
                        long val1 = 0L;
                        for (CastWire.Field pf : CastWire.parseFields(f.bytes)) {
                            if (pf.number == 1 && !pf.isBytes) { val1 = pf.varint; break; }
                        }
                        writeAtvFramed(sock, CastWire.bytesField(9, CastWire.varintField(1, val1)));
                    }
                }
                if (configured && activeSet) break;
            }
            byte[] inject = CastWire.concat(CastWire.varintField(1, keyCode), CastWire.varintField(2, 3));
            writeAtvFramed(sock, CastWire.bytesField(10, inject));
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        }
    }

    private byte[] readFileBytes(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private void writeFileBytes(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }
}
