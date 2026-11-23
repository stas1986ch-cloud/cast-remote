/**
 * @file YouTubeLoungeService.kt
 * @summary Implements the YouTube Lounge HTTP protocol to launch and replace playback on Chromecast YouTube receivers.
 * @description Fetches a lounge token from a TV screenId, binds a session (SID/gsessionid), and sends RFC-3986 form-encoded setPlaylist requests with stale-SID retry.
 * @testing Verified against casttube protocol sequencing and HTTP form-encoding boundary cases.
 */
package com.stas.castremote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class YouTubeLoungeService(private val screenId: String) {
    companion object {
        private const val BASE = "https://www.youtube.com/"
        private const val BIND_URL = BASE + "api/lounge/bc/bind"
        private const val LOUNGE_TOKEN_URL = BASE + "api/lounge/pairing/get_lounge_token_batch"

        private val BIND_DATA = listOf(
            "device" to "REMOTE_CONTROL",
            "id" to "aaaaaaaaaaaaaaaaaaaaaaaaaa",
            "name" to "CastRemoteAndroid",
            "mdx-version" to "3",
            "pairing_type" to "cast",
            "app" to "android-phone-13.14.55"
        )
        private val SID_RE = Regex("\"c\",\"(.*?)\",")
        private val GSESSION_RE = Regex("\"S\",\"(.*?)\"\\]")
    }

    private var loungeToken: String? = null
    private var gsessionId: String? = null
    private var sid: String? = null
    private var rid = 0
    private var reqCount = 0

    suspend fun playVideo(videoId: String, listId: String = "", startTime: String = "0") = withContext(Dispatchers.IO) {
        getLoungeToken()
        bind()
        val params = listOf(
            "_listId" to listId,
            "__sc" to "setPlaylist",
            "_currentTime" to startTime,
            "_currentIndex" to "-1",
            "_audioOnly" to "false",
            "_videoId" to videoId,
            "count" to "1"
        )
        try {
            postSession(formatSessionParams(params))
        } catch (_: NeedsRetryException) {
            postSession(formatSessionParams(params))
        }
    }

    private fun getLoungeToken() {
        val body = postForm(
            urlStr = LOUNGE_TOKEN_URL,
            form = listOf("screen_ids" to screenId),
            queryParams = emptyList(),
            useLoungeHeader = false
        )
        val json = JSONObject(body)
        val screens = json.optJSONArray("screens") ?: throw IOException("Missing screens in lounge token response")
        val token = screens.optJSONObject(0)?.optString("loungeToken", "").orEmpty()
        if (token.isEmpty()) throw IOException("Empty loungeToken")
        loungeToken = token
    }

    private fun bind() {
        rid = 0
        reqCount = 0
        val body = postForm(
            urlStr = BIND_URL,
            form = BIND_DATA,
            queryParams = listOf("RID" to "0", "VER" to "8", "CVER" to "1"),
            useLoungeHeader = true
        )
        sid = SID_RE.find(body)?.groupValues?.getOrNull(1) ?: throw IOException("Missing SID in bind response")
        gsessionId = GSESSION_RE.find(body)?.groupValues?.getOrNull(1) ?: throw IOException("Missing gsessionid in bind response")
    }

    private fun postSession(form: List<Pair<String, String>>) {
        val curSid = sid ?: throw IOException("Not bound")
        val curGsession = gsessionId ?: throw IOException("Not bound")
        val query = listOf(
            "SID" to curSid,
            "gsessionid" to curGsession,
            "RID" to rid.toString(),
            "VER" to "8",
            "CVER" to "1"
        )
        try {
            postForm(BIND_URL, form, query, useLoungeHeader = true)
            reqCount += 1
        } catch (e: HttpStatusException) {
            if (e.code == 404 || e.code == 400) {
                bind()
                throw NeedsRetryException()
            }
            throw e
        }
    }

    private fun formatSessionParams(params: List<Pair<String, String>>): List<Pair<String, String>> {
        val prefix = "req$reqCount"
        return params.map { (k, v) -> (if (k.startsWith("_")) prefix + k else k) to v }
    }

    private fun postForm(
        urlStr: String,
        form: List<Pair<String, String>>,
        queryParams: List<Pair<String, String>>,
        useLoungeHeader: Boolean
    ): String {
        val fullUrl = if (queryParams.isEmpty()) {
            urlStr
        } else {
            val qs = queryParams.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
            "$urlStr?$qs"
        }
        val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Origin", "https://www.youtube.com/")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            if (useLoungeHeader && !loungeToken.isNullOrEmpty()) {
                setRequestProperty("X-YouTube-LoungeId-Token", loungeToken)
            }
        }
        val payload = form.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }.toByteArray(Charsets.UTF_8)
        conn.outputStream.use { it.write(payload) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) throw HttpStatusException(code, text)
        rid += 1
        return text
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private class NeedsRetryException : Exception()
    private class HttpStatusException(val code: Int, body: String) : IOException("HTTP $code: ${body.take(120)}")
}
