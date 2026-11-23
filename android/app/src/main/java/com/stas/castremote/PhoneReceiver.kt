/**
 * @file PhoneReceiver.kt
 * @summary Advertises this Android device as a Cast Remote receiver on TCP 8766 and sends YouTube links to peer phones.
 * @description Uses NsdManager (_castremote._tcp) and a lightweight ServerSocket handling POST /play and GET /ping, compatible with the iOS app.
 * @testing Verified for safe HTTP Content-Length reading, IPv6 URL bracketing, and safe YouTube Intent launching.
 */
package com.stas.castremote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

class PhoneReceiver(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onIncomingVideo: (String) -> Unit
) {
    companion object {
        const val PORT = 8766
        const val SERVICE_TYPE = "_castremote._tcp."
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var regListener: NsdManager.RegistrationListener? = null

    var isReceiving: Boolean = false
        private set
    var receiveName: String = ""
        private set

    fun startReceiving(): Boolean {
        if (isReceiving) return true
        val name = "Cast Remote ${Build.MODEL}"
        val sock = try {
            ServerSocket(PORT)
        } catch (_: Exception) {
            return false
        }
        serverSocket = sock
        isReceiving = true
        receiveName = name

        serverJob = scope.launch(Dispatchers.IO) {
            while (isActive && !sock.isClosed) {
                try {
                    val client = sock.accept()
                    launch(Dispatchers.IO) { handleClient(client) }
                } catch (_: Exception) {
                    break
                }
            }
        }

        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        regListener = listener
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
        return true
    }

    fun stopReceiving() {
        isReceiving = false
        receiveName = ""
        regListener?.let { runCatching { nsdManager.unregisterService(it) } }
        regListener = null
        serverJob?.cancel()
        serverJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 4000
            val inp = BufferedInputStream(sock.getInputStream())
            val headerBytes = readUntilHeadersEnd(inp) ?: return
            val headerText = String(headerBytes, Charsets.UTF_8)
            val contentLength = parseContentLength(headerText)
            val bodyBytes = if (contentLength in 1..65536) {
                ByteArray(contentLength).also { buf ->
                    var read = 0
                    while (read < contentLength) {
                        val n = inp.read(buf, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                }
            } else ByteArray(0)

            val response = when {
                headerText.startsWith("POST /play") -> {
                    val json = runCatching { JSONObject(String(bodyBytes, Charsets.UTF_8)) }.getOrNull()
                    val videoId = json?.optString("video_id", "").orEmpty().trim()
                    val start = maxOf(0, json?.optInt("start", 0) ?: 0)
                    if (videoId.matches(Regex("^[A-Za-z0-9_-]{1,32}$"))) {
                        onIncomingVideo(videoId)
                        openYouTube(videoId, start)
                    }
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
                }
                headerText.startsWith("GET /ping") ->
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
                else ->
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            }
            sock.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
            sock.getOutputStream().flush()
        }
    }

    private fun readUntilHeadersEnd(inp: BufferedInputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var matched = 0
        val sep = byteArrayOf(13, 10, 13, 10)
        while (out.size() < 16384) {
            val b = inp.read()
            if (b == -1) return null
            out.write(b)
            matched = if (b.toByte() == sep[matched]) matched + 1 else if (b.toByte() == sep[0]) 1 else 0
            if (matched == 4) return out.toByteArray()
        }
        return null
    }

    private fun parseContentLength(headers: String): Int {
        for (line in headers.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("content-length", ignoreCase = true)) {
                return line.substring(idx + 1).trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
            }
        }
        return 0
    }

    private fun openYouTube(videoId: String, start: Int) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId&t=${start}s")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    suspend fun sendToPhone(host: String, port: Int, videoId: String, start: Int) = withContext(Dispatchers.IO) {
        val formattedHost = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
        val conn = (URL("http://$formattedHost:$port/play").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 5000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        val payload = JSONObject().put("video_id", videoId).put("start", start).toString().toByteArray(Charsets.UTF_8)
        conn.outputStream.use { it.write(payload) }
        val code = conn.responseCode
        conn.disconnect()
        if (code !in 200..299) throw IOException("Phone receiver HTTP $code")
    }
}
