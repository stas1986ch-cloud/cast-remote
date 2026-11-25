/**
 * @file ProtobufWire.kt
 * @summary Lightweight Protocol Buffers wire encoder and decoder shared by Cast v2 and Android TV Remote v2.
 * @description Implements varint, length-delimited bytes/strings, and top-level field parsing with zero external protobuf runtime dependencies.
 * @testing Verified via Kotlin compilation in Android CI and parity with the verified Swift/Python wire test vectors.
 */
package com.screencontrol.cast

import java.io.ByteArrayOutputStream

object ProtobufWire {
    sealed class FieldValue {
        data class Varint(val value: Long) : FieldValue()
        data class Bytes(val value: ByteArray) : FieldValue()
    }

    fun encodeVarint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write((v and 0x7FL).toInt())
        return out.toByteArray()
    }

    fun decodeVarint(data: ByteArray, offsetRef: IntArray): Long? {
        var result = 0L
        var shift = 0
        var idx = offsetRef[0]
        while (idx < data.size) {
            val b = data[idx].toInt() and 0xFF
            idx++
            result = result or ((b.toLong() and 0x7FL) shl shift)
            if ((b and 0x80) == 0) {
                offsetRef[0] = idx
                return result
            }
            shift += 7
            if (shift >= 64) return null
        }
        return null
    }

    fun varintField(fieldNumber: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeVarint(((fieldNumber shl 3) or 0).toLong()))
        out.write(encodeVarint(value))
        return out.toByteArray()
    }

    fun bytesField(fieldNumber: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeVarint(((fieldNumber shl 3) or 2).toLong()))
        out.write(encodeVarint(payload.size.toLong()))
        out.write(payload)
        return out.toByteArray()
    }

    fun stringField(fieldNumber: Int, text: String): ByteArray =
        bytesField(fieldNumber, text.toByteArray(Charsets.UTF_8))

    fun frameVarint(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeVarint(payload.size.toLong()))
        out.write(payload)
        return out.toByteArray()
    }

    fun parseFields(data: ByteArray): List<Pair<Int, FieldValue>> {
        val out = mutableListOf<Pair<Int, FieldValue>>()
        val offset = intArrayOf(0)
        while (offset[0] < data.size) {
            val tag = decodeVarint(data, offset) ?: break
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07L).toInt()
            when (wireType) {
                0 -> {
                    val v = decodeVarint(data, offset) ?: return out
                    out.add(fieldNumber to FieldValue.Varint(v))
                }
                1 -> {
                    if (offset[0] + 8 > data.size) return out
                    offset[0] += 8
                }
                2 -> {
                    val len = decodeVarint(data, offset)?.toInt() ?: return out
                    if (len < 0 || offset[0] + len > data.size) return out
                    val slice = data.copyOfRange(offset[0], offset[0] + len)
                    offset[0] += len
                    out.add(fieldNumber to FieldValue.Bytes(slice))
                }
                5 -> {
                    if (offset[0] + 4 > data.size) return out
                    offset[0] += 4
                }
                else -> return out
            }
        }
        return out
    }
}
