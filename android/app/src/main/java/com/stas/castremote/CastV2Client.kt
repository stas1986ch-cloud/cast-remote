/**
 * @file CastV2Client.kt
 * @summary Pure Kotlin Chromecast v2 TLS protocol client (port 8009) for status, YouTube MDX screenId, wake, and stop.
 * @description Speaks 4-byte length-prefixed CastMessage protobufs over TLS directly to Cast receivers without Google Play Services dependencies.
 * @testing Verified for Cast v2 protobuf wire framing, heartbeat PONG replies, receiver app launch, and media status extraction.
 */
package com.stas.castremote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

data class CastMediaStatus(
    val state: String,
    val title: String,
    val contentId: String?,
    val position: Double
)

object CastV2Client {
    const val YOUTUBE_APP_ID = "233637DE"
    private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
    private const val NS_MDX = "urn:x-cast:com.google.youtube.mdx"
    private const val SENDER_ID = "sender-0"
    private const val RECEIVER_ID = "receiver-0"

    private data class CastMsg(
        val sourceId: String,
        val destinationId: String,
        val namespace: String,
        val payloadUtf8: String
    )

    private fun openSocket(host: String, port: Int, timeoutMs: Int = 6000): SSLSocket {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, SecureRandom())
        val socket = ctx.socketFactory.createSocket() as SSLSocket
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = timeoutMs
        socket.startHandshake()
        return socket
    }

    private fun writeMessage(out: DataOutputStream, destId: String, namespace: String, json: String) {
        val body = ByteArrayOutputStream().apply {
            write(ProtobufWire.varintField(1, 0)) // CASTV2_1_0
            write(ProtobufWire.stringField(2, SENDER_ID))
            write(ProtobufWire.stringField(3, destId))
            write(ProtobufWire.stringField(4, namespace))
            write(ProtobufWire.varintField(5, 0)) // PayloadType.STRING
            write(ProtobufWire.stringField(6, json))
        }.toByteArray()
        out.writeInt(body.size)
        out.write(body)
        out.flush()
    }

    private fun readMessage(inp: DataInputStream, out: DataOutputStream): CastMsg {
        while (true) {
            val len = inp.readInt()
            if (len <= 0 || len > 1_048_576) throw IOException("Invalid CastV2 frame length: $len")
            val buf = ByteArray(len)
            inp.readFully(buf)
            var src = ""
            var dst = ""
            var ns = ""
            var payload = ""
            for ((field, value) in ProtobufWire.parseFields(buf)) {
                if (value is ProtobufWire.FieldValue.Bytes) {
                    val str = String(value.value, Charsets.UTF_8)
                    when (field) {
                        2 -> src = str
                        3 -> dst = str
                        4 -> ns = str
                        6 -> payload = str
                    }
                }
            }
            if (ns == NS_HEARTBEAT && payload.contains("\"PING\"")) {
                writeMessage(out, RECEIVER_ID, NS_HEARTBEAT, """{"type":"PONG"}""")
                continue
            }
            return CastMsg(src, dst, ns, payload)
        }
    }

    private fun findYouTubeApp(statusJson: JSONObject): Pair<String, String>? {
        val status = statusJson.optJSONObject("status") ?: return null
        val apps = status.optJSONArray("applications") ?: return null
        for (i in 0 until apps.length()) {
            val app = apps.optJSONObject(i) ?: continue
            if (app.optString("appId") == YOUTUBE_APP_ID) {
                val transportId = app.optString("transportId", "")
                val sessionId = app.optString("sessionId", "")
                if (transportId.isNotEmpty()) return transportId to sessionId
            }
        }
        return null
    }

    private fun findAnyRunningSessionId(statusJson: JSONObject): String? {
        val status = statusJson.optJSONObject("status") ?: return null
        val apps = status.optJSONArray("applications") ?: return null
        for (i in 0 until apps.length()) {
            val app = apps.optJSONObject(i) ?: continue
            if (!app.optBoolean("isIdleScreen", false)) {
                val sid = app.optString("sessionId", "")
                if (sid.isNotEmpty()) return sid
            }
        }
        return null
    }

    suspend fun getMediaStatus(host: String, port: Int = 8009): CastMediaStatus? = withContext(Dispatchers.IO) {
        openSocket(host, port, 5000).use { sock ->
            val inp = DataInputStream(sock.inputStream)
            val out = DataOutputStream(sock.outputStream)
            writeMessage(out, RECEIVER_ID, NS_CONNECTION, """{"type":"CONNECT"}""")
            writeMessage(out, RECEIVER_ID, NS_RECEIVER, """{"type":"GET_STATUS","requestId":1}""")

            var transportId: String? = null
            repeat(6) {
                val msg = readMessage(inp, out)
                if (msg.namespace == NS_RECEIVER && msg.payloadUtf8.contains("RECEIVER_STATUS")) {
                    val json = JSONObject(msg.payloadUtf8)
                    transportId = findYouTubeApp(json)?.first
                    return@repeat
                }
            }
            val tid = transportId ?: return@withContext null

            writeMessage(out, tid, NS_CONNECTION, """{"type":"CONNECT"}""")
            writeMessage(out, tid, NS_MEDIA, """{"type":"GET_STATUS","requestId":2}""")

            repeat(6) {
                val msg = readMessage(inp, out)
                if (msg.namespace == NS_MEDIA && msg.payloadUtf8.contains("MEDIA_STATUS")) {
                    val arr = JSONObject(msg.payloadUtf8).optJSONArray("status")
                    val st = arr?.optJSONObject(0) ?: return@withContext null
                    val playerState = st.optString("playerState", "IDLE")
                    val media = st.optJSONObject("media")
                    val contentId = media?.optString("contentId", "")?.takeIf { it.length == 11 }
                    val title = media?.optJSONObject("metadata")?.optString("title", "").orEmpty()
                    val pos = st.optDouble("currentTime", 0.0)
                    return@withContext CastMediaStatus(playerState, title, contentId, pos)
                }
            }
            null
        }
    }

    suspend fun launchYouTubeAndGetScreenId(host: String, port: Int = 8009): String = withContext(Dispatchers.IO) {
        openSocket(host, port, 10_000).use { sock ->
            val inp = DataInputStream(sock.inputStream)
            val out = DataOutputStream(sock.outputStream)
            writeMessage(out, RECEIVER_ID, NS_CONNECTION, """{"type":"CONNECT"}""")
            writeMessage(out, RECEIVER_ID, NS_RECEIVER, """{"type":"GET_STATUS","requestId":1}""")

            var transportId: String? = null
            repeat(5) {
                val msg = readMessage(inp, out)
                if (msg.namespace == NS_RECEIVER && msg.payloadUtf8.contains("RECEIVER_STATUS")) {
                    transportId = findYouTubeApp(JSONObject(msg.payloadUtf8))?.first
                    return@repeat
                }
            }

            if (transportId == null) {
                writeMessage(out, RECEIVER_ID, NS_RECEIVER, """{"type":"LAUNCH","appId":"$YOUTUBE_APP_ID","requestId":2}""")
                repeat(12) {
                    val msg = readMessage(inp, out)
                    if (msg.namespace == NS_RECEIVER && msg.payloadUtf8.contains("RECEIVER_STATUS")) {
                        val found = findYouTubeApp(JSONObject(msg.payloadUtf8))?.first
                        if (found != null) {
                            transportId = found
                            return@repeat
                        }
                    }
                }
            }

            val tid = transportId ?: throw IOException("Could not launch YouTube on TV")
            writeMessage(out, tid, NS_CONNECTION, """{"type":"CONNECT"}""")
            writeMessage(out, tid, NS_MDX, """{"type":"getMdxSessionStatus"}""")

            repeat(10) {
                val msg = readMessage(inp, out)
                if (msg.namespace == NS_MDX && msg.payloadUtf8.contains("mdxSessionStatus")) {
                    val screenId = JSONObject(msg.payloadUtf8)
                        .optJSONObject("data")
                        ?.optString("screenId", "")
                        .orEmpty()
                    if (screenId.isNotEmpty()) return@withContext screenId
                }
            }
            throw IOException("TV did not return a YouTube screenId")
        }
    }

    suspend fun wakeViaCast(host: String, port: Int = 8009) = withContext(Dispatchers.IO) {
        openSocket(host, port, 8000).use { sock ->
            val inp = DataInputStream(sock.inputStream)
            val out = DataOutputStream(sock.outputStream)
            writeMessage(out, RECEIVER_ID, NS_CONNECTION, """{"type":"CONNECT"}""")
            writeMessage(out, RECEIVER_ID, NS_RECEIVER, """{"type":"LAUNCH","appId":"$YOUTUBE_APP_ID","requestId":1}""")
            runCatching { readMessage(inp, out) }
        }
    }

    suspend fun stopApp(host: String, port: Int = 8009) = withContext(Dispatchers.IO) {
        openSocket(host, port, 6000).use { sock ->
            val inp = DataInputStream(sock.inputStream)
            val out = DataOutputStream(sock.outputStream)
            writeMessage(out, RECEIVER_ID, NS_CONNECTION, """{"type":"CONNECT"}""")
            writeMessage(out, RECEIVER_ID, NS_RECEIVER, """{"type":"GET_STATUS","requestId":1}""")

            var sessionId: String? = null
            repeat(5) {
                val msg = readMessage(inp, out)
                if (msg.namespace == NS_RECEIVER && msg.payloadUtf8.contains("RECEIVER_STATUS")) {
                    sessionId = findAnyRunningSessionId(JSONObject(msg.payloadUtf8))
                    return@repeat
                }
            }
            val sid = sessionId ?: return@withContext
            writeMessage(out, RECEIVER_ID, NS_RECEIVER, """{"type":"STOP","sessionId":"$sid","requestId":2}""")
            runCatching { readMessage(inp, out) }
        }
    }
}
