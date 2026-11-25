/**
 * @file AndroidTVRemote.kt
 * @summary Implements Android TV / Google TV Remote Protocol v2 pairing (port 6467) and power control (port 6466) over Mutual TLS.
 * @description Persists a self-signed RSA-2048 X.509 client certificate in app-private storage, validates 6-char hex PIN secrets via SHA-256, and sends state-aware power keycodes.
 * @testing Verified against androidtvremote2 protobuf field tags, ASN.1 DER X.509 encoding, and SHA-256 secret checksums.
 */
package com.screencontrol.cast

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

enum class TVPowerAction { POWER_ON, POWER_OFF, TOGGLE }

data class PairingTarget(val deviceId: String, val name: String, val host: String)

private object DerBuilder {
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        if (len < 0x80) {
            out.write(len)
        } else {
            val lenBytes = mutableListOf<Int>()
            var rem = len
            while (rem > 0) {
                lenBytes.add(0, rem and 0xFF)
                rem = rem ushr 8
            }
            out.write(0x80 or lenBytes.size)
            for (b in lenBytes) out.write(b)
        }
        out.write(content)
        return out.toByteArray()
    }

    fun sequence(vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        parts.forEach { body.write(it) }
        return tlv(0x30, body.toByteArray())
    }

    fun set(vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        parts.forEach { body.write(it) }
        return tlv(0x31, body.toByteArray())
    }

    fun integer(raw: ByteArray): ByteArray {
        var idx = 0
        while (idx < raw.size - 1 && raw[idx] == 0.toByte()) idx++
        val trimmed = raw.copyOfRange(idx, raw.size)
        val body = if ((trimmed[0].toInt() and 0x80) != 0) byteArrayOf(0) + trimmed else trimmed
        return tlv(0x02, body)
    }

    fun bitString(data: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + data)
    fun nullVal(): ByteArray = byteArrayOf(0x05, 0x00)
    fun oid(vararg bytes: Int): ByteArray = tlv(0x06, bytes.map { it.toByte() }.toByteArray())
    fun utf8(text: String): ByteArray = tlv(0x0C, text.toByteArray(Charsets.UTF_8))
    fun utcTime(text: String): ByteArray = tlv(0x17, text.toByteArray(Charsets.US_ASCII))
    fun explicit(index: Int, content: ByteArray): ByteArray = tlv(0xA0 or index, content)
}

class AndroidTVRemoteManager(private val context: Context) {
    companion object {
        const val PAIRING_PORT = 6467
        const val REMOTE_PORT = 6466
        const val KEYCODE_POWER = 26L
        const val KEYCODE_SLEEP = 223L
        const val KEYCODE_WAKEUP = 224L
        private const val PREFS_NAME = "cast_remote_prefs"
        private const val KEY_PAIRED = "atv_paired_ids"
    }

    private data class ClientIdentity(
        val privateKey: PrivateKey,
        val cert: X509Certificate,
        val keyStore: KeyStore
    )

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var cachedIdentity: ClientIdentity? = null

    private var activePairingSocket: SSLSocket? = null
    private var activeServerCert: X509Certificate? = null
    var activePairingTarget: PairingTarget? = null
        private set

    fun getPairedIds(): Set<String> = prefs.getStringSet(KEY_PAIRED, emptySet())?.toSet() ?: emptySet()

    fun isPaired(deviceId: String): Boolean = getPairedIds().contains(deviceId)

    private fun markPaired(deviceId: String) {
        val next = getPairedIds().toMutableSet().apply { add(deviceId) }
        prefs.edit().putStringSet(KEY_PAIRED, next).apply()
    }

    private fun loadOrCreateIdentity(): ClientIdentity {
        cachedIdentity?.let { return it }
        val keyFile = File(context.filesDir, "atv_client_key.pkcs8")
        val certFile = File(context.filesDir, "atv_client_cert.der")

        val (privKey, cert) = if (keyFile.exists() && certFile.exists()) {
            val pk = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val cf = CertificateFactory.getInstance("X.509")
            val c = cf.generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
            pk to c
        } else {
            val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }
            val kp = kpg.generateKeyPair()
            val certDer = buildSelfSignedCert(kp.private, kp.public.encoded)
            val cf = CertificateFactory.getInstance("X.509")
            val c = cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
            keyFile.writeBytes(kp.private.encoded)
            certFile.writeBytes(certDer)
            kp.private to c
        }

        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("atv", privKey, CharArray(0), arrayOf(cert))
        }
        return ClientIdentity(privKey, cert, ks).also { cachedIdentity = it }
    }

    private fun buildSelfSignedCert(privateKey: PrivateKey, spkiDer: ByteArray): ByteArray {
        val sigAlg = DerBuilder.sequence(
            DerBuilder.oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B), // sha256WithRSAEncryption
            DerBuilder.nullVal()
        )
        val version = DerBuilder.explicit(0, DerBuilder.integer(byteArrayOf(0x02)))
        val serialBytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        serialBytes[0] = ((serialBytes[0].toInt() and 0x7F) or 0x01).toByte()
        val serial = DerBuilder.integer(serialBytes)
        val dn = DerBuilder.sequence(
            DerBuilder.set(
                DerBuilder.sequence(
                    DerBuilder.oid(0x55, 0x04, 0x03), // CN
                    DerBuilder.utf8("CastRemote")
                )
            )
        )
        val validity = DerBuilder.sequence(
            DerBuilder.utcTime("240101000000Z"),
            DerBuilder.utcTime("491231235959Z")
        )
        val tbs = DerBuilder.sequence(version, serial, sigAlg, dn, validity, dn, spkiDer)
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(tbs)
        }.sign()
        return DerBuilder.sequence(tbs, sigAlg, DerBuilder.bitString(sig))
    }

    private fun openMutualTls(host: String, port: Int, timeoutMs: Int = 7000): Pair<SSLSocket, X509Certificate?> {
        val id = loadOrCreateIdentity()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(id.keyStore, CharArray(0))
        }
        var capturedCert: X509Certificate? = null
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                capturedCert = chain?.firstOrNull()
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLSv1.2").apply {
            init(kmf.keyManagers, arrayOf(tm), SecureRandom())
        }
        val sock = ctx.socketFactory.createSocket() as SSLSocket
        sock.connect(InetSocketAddress(host, port), timeoutMs)
        sock.soTimeout = timeoutMs
        sock.startHandshake()
        return sock to capturedCert
    }

    private fun writeFramed(sock: SSLSocket, payload: ByteArray) {
        sock.outputStream.write(ProtobufWire.frameVarint(payload))
        sock.outputStream.flush()
    }

    private fun readFramed(sock: SSLSocket): ByteArray {
        val inp = DataInputStream(sock.inputStream)
        var length = 0
        var shift = 0
        while (true) {
            val b = inp.readUnsignedByte()
            length = length or ((b and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
            if (shift >= 32) throw IOException("Malformed varint length")
        }
        if (length <= 0 || length > 1_048_576) throw IOException("Invalid frame length $length")
        val buf = ByteArray(length)
        inp.readFully(buf)
        return buf
    }

    private fun assertPairingOk(msg: ByteArray) {
        for ((field, value) in ProtobufWire.parseFields(msg)) {
            if (field == 2 && value is ProtobufWire.FieldValue.Varint && value.value != 200L) {
                throw IOException("TV rejected pairing (status ${value.value})")
            }
        }
    }

    // MARK: - Pairing (Port 6467)

    suspend fun beginPairing(deviceId: String, name: String, host: String): PairingTarget = withContext(Dispatchers.IO) {
        cancelPairing()
        if (host.isEmpty()) throw IOException("TV IP address unavailable")
        val (sock, serverCert) = openMutualTls(host, PAIRING_PORT, 8000)
        try {
            // 1. PairingRequest
            val req = ByteArrayOutputStream().apply {
                write(ProtobufWire.stringField(1, "com.screencontrol.cast"))
                write(ProtobufWire.stringField(2, "Cast Remote"))
            }.toByteArray()
            writeFramed(sock, pairingEnvelope(10, req))
            assertPairingOk(readFramed(sock))

            // 2. PairingOption (HEXADECIMAL=3, length=6, ROLE_TYPE_INPUT=1)
            val enc = ProtobufWire.varintField(1, 3) + ProtobufWire.varintField(2, 6)
            val opt = ProtobufWire.bytesField(1, enc) + ProtobufWire.varintField(3, 1)
            writeFramed(sock, pairingEnvelope(20, opt))
            assertPairingOk(readFramed(sock))

            // 3. PairingConfiguration
            val cfg = ProtobufWire.bytesField(1, enc) + ProtobufWire.varintField(2, 1)
            writeFramed(sock, pairingEnvelope(30, cfg))
            assertPairingOk(readFramed(sock))

            activePairingSocket = sock
            activeServerCert = serverCert
            val target = PairingTarget(deviceId, name, host)
            activePairingTarget = target
            target
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw e
        }
    }

    suspend fun completePairing(pinHex: String) = withContext(Dispatchers.IO) {
        val sock = activePairingSocket ?: throw IOException("No active pairing session")
        val serverCert = activeServerCert ?: throw IOException("Missing TV certificate")
        val target = activePairingTarget ?: throw IOException("No pairing target")

        val cleaned = pinHex.trim().uppercase()
        if (cleaned.length != 6 || !cleaned.matches(Regex("^[0-9A-F]{6}$"))) {
            throw IOException("Enter the 6-character hex code shown on your TV")
        }
        val pinBytes = ByteArray(3) { i -> cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

        val clientPub = loadOrCreateIdentity().cert.publicKey as RSAPublicKey
        val serverPub = serverCert.publicKey as RSAPublicKey

        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(stripLeadingZero(clientPub.modulus.toByteArray()))
            update(stripLeadingZero(clientPub.publicExponent.toByteArray()))
            update(stripLeadingZero(serverPub.modulus.toByteArray()))
            update(stripLeadingZero(serverPub.publicExponent.toByteArray()))
            update(pinBytes, 1, 2) // last 4 hex chars = 2 bytes
        }.digest()

        if (digest[0] != pinBytes[0]) {
            throw IOException("PIN code did not match — check the TV screen and retry")
        }

        val secretMsg = ProtobufWire.bytesField(1, digest)
        writeFramed(sock, pairingEnvelope(40, secretMsg))
        assertPairingOk(readFramed(sock))

        markPaired(target.deviceId)
        cancelPairing()
    }

    fun cancelPairing() {
        runCatching { activePairingSocket?.close() }
        activePairingSocket = null
        activeServerCert = null
        activePairingTarget = null
    }

    private fun pairingEnvelope(payloadField: Int, payload: ByteArray): ByteArray =
        ProtobufWire.varintField(1, 2) +
            ProtobufWire.varintField(2, 200) +
            ProtobufWire.bytesField(payloadField, payload)

    private fun stripLeadingZero(bytes: ByteArray): ByteArray {
        var idx = 0
        while (idx < bytes.size - 1 && bytes[idx] == 0.toByte()) idx++
        return bytes.copyOfRange(idx, bytes.size)
    }

    // MARK: - Power Control (Port 6466)

    suspend fun sendPower(deviceId: String, host: String, action: TVPowerAction): Boolean? = withContext(Dispatchers.IO) {
        if (host.isEmpty()) throw IOException("TV IP address unavailable")
        if (!isPaired(deviceId)) throw IOException("TV needs one-time PIN pairing")

        val (sock, _) = openMutualTls(host, REMOTE_PORT, 6000)
        sock.use { s ->
            var isPoweredOn: Boolean? = null
            var configured = false
            var activeSet = false

            for (step in 0 until 8) {
                val msg = readFramed(s)
                for ((field, value) in ProtobufWire.parseFields(msg)) {
                    when (field) {
                        1 -> { // remote_configure
                            writeFramed(s, remoteConfigureMsg())
                            configured = true
                        }
                        2 -> { // remote_set_active
                            writeFramed(s, ProtobufWire.bytesField(2, ProtobufWire.varintField(1, 622)))
                            activeSet = true
                        }
                        8 -> { // remote_ping_request
                            if (value is ProtobufWire.FieldValue.Bytes) {
                                val val1 = ProtobufWire.parseFields(value.value)
                                    .firstOrNull { it.first == 1 }
                                    ?.let { (it.second as? ProtobufWire.FieldValue.Varint)?.value } ?: 0L
                                writeFramed(s, ProtobufWire.bytesField(9, ProtobufWire.varintField(1, val1)))
                            }
                        }
                        40 -> { // remote_start
                            if (value is ProtobufWire.FieldValue.Bytes) {
                                val started = ProtobufWire.parseFields(value.value)
                                    .firstOrNull { it.first == 1 }
                                    ?.let { (it.second as? ProtobufWire.FieldValue.Varint)?.value != 0L } ?: false
                                isPoweredOn = started
                            }
                        }
                    }
                }
                if (configured && activeSet && isPoweredOn != null) break
            }

            val (keyCode, expectedState) = when (action) {
                TVPowerAction.POWER_ON -> when (isPoweredOn) {
                    true -> null to true
                    false -> KEYCODE_POWER to true
                    null -> KEYCODE_WAKEUP to true
                }
                TVPowerAction.POWER_OFF -> when (isPoweredOn) {
                    false -> null to false
                    true -> KEYCODE_POWER to false
                    null -> KEYCODE_SLEEP to false
                }
                TVPowerAction.TOGGLE -> KEYCODE_POWER to isPoweredOn?.not()
            }

            if (keyCode != null) {
                val inject = ProtobufWire.varintField(1, keyCode) + ProtobufWire.varintField(2, 3) // SHORT = 3
                writeFramed(s, ProtobufWire.bytesField(10, inject))
                delay(250)
            }
            expectedState ?: isPoweredOn
        }
    }

    private fun remoteConfigureMsg(): ByteArray {
        val info = ProtobufWire.stringField(1, android.os.Build.MODEL) +
            ProtobufWire.stringField(2, android.os.Build.MANUFACTURER) +
            ProtobufWire.varintField(3, 1) +
            ProtobufWire.stringField(4, "1") +
            ProtobufWire.stringField(5, "com.screencontrol.cast") +
            ProtobufWire.stringField(6, "1.3")
        val cfg = ProtobufWire.varintField(1, 622) + ProtobufWire.bytesField(2, info)
        return ProtobufWire.bytesField(1, cfg)
    }
}
