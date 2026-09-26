package org.smarthealthit.checkin.wallet

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Minimal CBOR reader/writer.
 *
 * Just enough to walk an mdoc DeviceRequest emitted by Chrome / Safari for the
 * `org-iso-mdoc` Digital Credentials API protocol and to emit the demo
 * DeviceResponse bytes — see
 * `spec.md` §8 + Appendix A for the captured shape.
 *
 * Supports: unsigned integers (0..2^32-1), negative integers, byte strings,
 * text strings, arrays, maps, tags, booleans, and null.
 *
 * Indefinite-length items (length 31) and 64-bit lengths intentionally
 * unsupported — Chrome / Safari use definite, 32-bit-or-shorter forms in
 * captured requests.
 */
object MdocCbor {
    /** CBOR tag 24 — RFC 8949 §3.4.5.1 — "Encoded CBOR data item". */
    const val TAG_ENCODED_CBOR: Long = 24

    data class CborTag(val tag: Long, val value: Any?)
    data class CborRaw(val bytes: ByteArray)

    /**
     * Decode the entire byte array as a single CBOR value. A map with a
     * duplicate key keeps the last value and calls [onDuplicateKey], so a
     * receiver can warn ([ENC-5]).
     */
    fun decode(bytes: ByteArray, onDuplicateKey: (() -> Unit)? = null): Any? {
        val cursor = Cursor(bytes, 0, onDuplicateKey)
        val value = decodeValue(cursor)
        require(cursor.pos == bytes.size) { "trailing CBOR bytes: ${bytes.size - cursor.pos}" }
        return value
    }

    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        encodeValue(out, value)
        return out.toByteArray()
    }

    fun tag24Bytes(encodedInner: ByteArray): ByteArray {
        return encode(CborTag(TAG_ENCODED_CBOR, encodedInner))
    }

    /**
     * Decode a tag-24 wrapped CBOR item: returns the inner CBOR value
     * (after stripping the tag and the byte-string wrapper).
     */
    fun decodeTag24(value: Any?, onDuplicateKey: (() -> Unit)? = null): Any? {
        require(value is CborTag && value.tag == TAG_ENCODED_CBOR) {
            "expected CBOR tag 24, got $value"
        }
        val inner = value.value
        require(inner is ByteArray) { "tag 24 must wrap a byte string" }
        return decode(inner, onDuplicateKey)
    }

    private class Cursor(val bytes: ByteArray, var pos: Int, val onDuplicateKey: (() -> Unit)? = null) {
        fun read(): Int {
            require(pos < bytes.size) { "unexpected end of CBOR" }
            return bytes[pos++].toInt() and 0xff
        }
        fun readN(n: Int): ByteArray {
            require(n >= 0 && pos + n <= bytes.size) { "unexpected end of CBOR byte string" }
            val out = bytes.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
        fun readUInt(ai: Int): Long {
            return when {
                ai < 24 -> ai.toLong()
                ai == 24 -> read().toLong()
                ai == 25 -> ((read() shl 8) or read()).toLong()
                ai == 26 -> ((read().toLong() shl 24) or
                            (read().toLong() shl 16) or
                            (read().toLong() shl 8) or
                            read().toLong())
                ai == 27 -> error("64-bit CBOR lengths unsupported")
                else -> error("unsupported additional info $ai")
            }
        }
    }

    private fun decodeValue(c: Cursor): Any? {
        val head = c.read()
        val major = head ushr 5
        val ai = head and 0x1f
        return when (major) {
            0 -> c.readUInt(ai) // uint
            1 -> -1L - c.readUInt(ai) // negative int
            2 -> c.readN(c.readUInt(ai).toInt()) // bstr
            3 -> String(c.readN(c.readUInt(ai).toInt()), StandardCharsets.UTF_8)
            4 -> {
                val len = c.readUInt(ai).toInt()
                val out = ArrayList<Any?>(len)
                repeat(len) { out += decodeValue(c) }
                out
            }
            5 -> {
                val len = c.readUInt(ai).toInt()
                val out = LinkedHashMap<Any?, Any?>(len * 2)
                repeat(len) {
                    val k = decodeValue(c)
                    val v = decodeValue(c)
                    if (out.containsKey(k)) c.onDuplicateKey?.invoke()
                    out[k] = v
                }
                out
            }
            6 -> {
                val tag = c.readUInt(ai)
                CborTag(tag, decodeValue(c))
            }
            7 -> when (ai) {
                20 -> false
                21 -> true
                22 -> null
                23 -> null // undefined
                else -> error("unsupported simple value $ai")
            }
            else -> error("unsupported major type $major")
        }
    }

    private fun encodeValue(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(0xf6)
            false -> out.write(0xf4)
            true -> out.write(0xf5)
            is CborRaw -> out.write(value.bytes)
            is CborTag -> {
                writeTypeAndLength(out, 6, value.tag)
                encodeValue(out, value.value)
            }
            is ByteArray -> {
                writeTypeAndLength(out, 2, value.size.toLong())
                out.write(value)
            }
            is String -> {
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                writeTypeAndLength(out, 3, bytes.size.toLong())
                out.write(bytes)
            }
            is Int -> encodeInteger(out, value.toLong())
            is Long -> encodeInteger(out, value)
            is UInt -> encodeInteger(out, value.toLong())
            is List<*> -> {
                writeTypeAndLength(out, 4, value.size.toLong())
                value.forEach { encodeValue(out, it) }
            }
            is Map<*, *> -> {
                writeTypeAndLength(out, 5, value.size.toLong())
                value.forEach { (k, v) ->
                    encodeValue(out, k)
                    encodeValue(out, v)
                }
            }
            else -> error("unsupported CBOR value ${value::class.java.name}")
        }
    }

    private fun encodeInteger(out: ByteArrayOutputStream, value: Long) {
        if (value >= 0) {
            writeTypeAndLength(out, 0, value)
        } else {
            writeTypeAndLength(out, 1, -1L - value)
        }
    }

    private fun writeTypeAndLength(out: ByteArrayOutputStream, major: Int, length: Long) {
        require(length >= 0) { "negative CBOR length" }
        when {
            length < 24 -> out.write((major shl 5) or length.toInt())
            length <= 0xff -> {
                out.write((major shl 5) or 24)
                out.write(length.toInt())
            }
            length <= 0xffff -> {
                out.write((major shl 5) or 25)
                out.write(((length ushr 8) and 0xffL).toInt())
                out.write((length and 0xffL).toInt())
            }
            length <= 0xffffffffL -> {
                out.write((major shl 5) or 26)
                out.write(((length ushr 24) and 0xffL).toInt())
                out.write(((length ushr 16) and 0xffL).toInt())
                out.write(((length ushr 8) and 0xffL).toInt())
                out.write((length and 0xffL).toInt())
            }
            else -> error("64-bit CBOR lengths unsupported")
        }
    }
}

/**
 * Decoded shape of `data.deviceRequest` for our doctype + namespace.
 *   ItemsRequest = { docType, nameSpaces, requestInfo }
 * The SMART request travels only in requestInfo["org.smarthealthit.checkin.request"].
 */
data class DecodedItemsRequest(
    val docType: String,
    val namespaces: Map<String, Map<String, Any?>>,
    val smartRequestJson: JSONObject?,
    val requestCarrierDebug: SmartRequestCarrierDebug,
    val itemsRequestTag24Bytes: ByteArray,
    val readerAuthBytes: ByteArray?,
)

/**
 * Walks a DeviceRequest the way spec §8.4 does: fail only where a step says
 * fail, and add a warning code to [warnings] for everything else ([RCV-1]).
 * Warning codes match the spec's conformance cases.
 */
object DeviceRequestParser {
    private const val EXPECTED_DOC_TYPE = "org.smarthealthit.checkin.1"
    private const val EXPECTED_NAMESPACE = "org.smarthealthit.checkin"
    private const val EXPECTED_ELEMENT = "smart_health_checkin_response"
    private const val SMART_REQUEST_INFO_KEY = "org.smarthealthit.checkin.request"

    /** Decode a base64url `deviceRequest`. Returns null if it has no request for our doctype. */
    fun parse(deviceRequestB64u: String, warnings: MutableSet<String> = linkedSetOf()): DecodedItemsRequest? {
        val bytes = SmartMdocBase64.decodeUrl(deviceRequestB64u)
        return parseBytes(bytes, warnings)
    }

    fun parseBytes(deviceRequestBytes: ByteArray, warnings: MutableSet<String> = linkedSetOf()): DecodedItemsRequest? {
        val dup = { warnings += "cbor-duplicate-key"; Unit }
        val outer = MdocCbor.decode(deviceRequestBytes, dup) as? Map<*, *> ?: return null
        // [WRQ-3]
        if (outer["version"] != "1.0") warnings += "device-request-version"
        val docRequests = outer["docRequests"] as? List<*> ?: return null
        // [WRQ-4]: the DocRequest(s) for our docType; other docTypes are ignored.
        val ours = docRequests.mapNotNull { docReq ->
            val docMap = docReq as? Map<*, *> ?: return@mapNotNull null
            val itemsRequestTag = docMap["itemsRequest"] ?: return@mapNotNull null
            val inner = runCatching { MdocCbor.decodeTag24(itemsRequestTag, dup) as? Map<*, *> }.getOrNull()
                ?: return@mapNotNull null
            if (inner["docType"] != EXPECTED_DOC_TYPE) return@mapNotNull null
            Triple(docMap, itemsRequestTag, inner)
        }
        if (ours.size > 1) warnings += "doc-requests"
        val (docMap, itemsRequestTag, inner) = ours.firstOrNull() ?: return null

        // [WRQ-5]
        val nsMap = inner["nameSpaces"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val namespaces = nsMap.entries.associate { (k, v) ->
            (k as? String ?: "") to ((v as? Map<*, *>)?.entries?.associate { (ek, ev) -> (ek as? String ?: "") to ev } ?: emptyMap())
        }
        val elements = namespaces[EXPECTED_NAMESPACE]
        if (elements == null || !elements.containsKey(EXPECTED_ELEMENT)) warnings += "items-request"
        else if (elements[EXPECTED_ELEMENT] !is Boolean) warnings += "intent-to-retain"

        val requestInfo = inner["requestInfo"] as? Map<*, *>
        val smartTstr = requestInfo?.get(SMART_REQUEST_INFO_KEY) as? String ?: return null
        val smartJson = runCatching { JSONObject(smartTstr) }.getOrElse {
            error("SMART request JSON is not valid JSON: ${it.message}")
        }
        return DecodedItemsRequest(
            docType = EXPECTED_DOC_TYPE,
            namespaces = namespaces,
            smartRequestJson = smartJson,
            requestCarrierDebug = SmartRequestCarrierDebug(
                source = "requestInfo",
                requestInfoPresent = true,
                warnings = warnings.toList(),
            ),
            itemsRequestTag24Bytes = MdocCbor.encode(itemsRequestTag),
            readerAuthBytes = docMap["readerAuth"]?.let { MdocCbor.encode(it) },
        )
    }
}
