package org.smarthealthit.checkin.wallet

import java.security.interfaces.ECPublicKey
import org.json.JSONObject

data class DirectMdocRequest(
    val origin: String,
    val protocol: String,
    val data: JSONObject,
    val deviceRequestBase64Url: String,
    val encryptionInfoBase64Url: String,
    val deviceRequestBytes: ByteArray,
    val encryptionInfoBytes: ByteArray,
    val itemsRequest: DecodedItemsRequest,
    val encryptionInfo: DirectMdocEncryptionInfo,
    val sessionTranscriptBytes: ByteArray,
    val readerAuth: ReaderAuthVerification,
    /** Transport warnings found while reading the request (spec §8.4, [RCV-1]). */
    val warnings: List<String> = emptyList(),
)

data class DirectMdocEncryptionInfo(
    val nonce: ByteArray,
    val recipientPublicKey: ECPublicKey,
    val recipientPublicKeyCose: Map<*, *>,
)

/**
 * Reads an `org-iso-mdoc` Digital Credentials API request as spec §8.4 says:
 * it fails only where a step says fail (nothing decodable, no request for our
 * docType, no usable recipient key) and otherwise records a warning and goes on.
 */
object DirectMdocRequestParser {
    private const val PROTOCOL_ORG_ISO_MDOC = "org-iso-mdoc"

    fun parseRequestJson(requestJson: String, origin: String): DirectMdocRequest {
        val outerJson = JSONObject(requestJson)
        val warnings = linkedSetOf<String>()
        val data = findOrgIsoMdocData(outerJson, warnings)
            ?: error("No org-iso-mdoc request found.")
        return parseData(data, origin, warnings)
    }

    fun parseData(data: JSONObject, origin: String, warnings: MutableSet<String> = linkedSetOf()): DirectMdocRequest {
        val deviceRequestBase64Url = data.optString("deviceRequest")
        require(deviceRequestBase64Url.isNotBlank()) { "data.deviceRequest is missing." }
        val encryptionInfoBase64Url = data.optString("encryptionInfo")
        require(encryptionInfoBase64Url.isNotBlank()) { "data.encryptionInfo is missing." }
        // [WRQ-2]: padding is a warning as long as the value decodes.
        if (deviceRequestBase64Url.contains('=') || encryptionInfoBase64Url.contains('=')) warnings += "base64url-padding"

        val deviceRequestBytes = SmartMdocBase64.decodeUrl(deviceRequestBase64Url.trimEnd('='))
        val encryptionInfoBytes = SmartMdocBase64.decodeUrl(encryptionInfoBase64Url.trimEnd('='))
        val itemsRequest = DeviceRequestParser.parseBytes(deviceRequestBytes, warnings)
            ?: error("DeviceRequest is not a SMART Health Check-in request.")
        val encryptionInfo = parseEncryptionInfo(encryptionInfoBytes, warnings)
        // [TR-1]: the transcript uses the exact string the Verifier sent.
        val sessionTranscriptBytes = buildSessionTranscript(encryptionInfoBase64Url, origin)
        // [WRQ-9]: reader authentication never makes the Wallet fail.
        val readerAuth = runCatching {
            verifyReaderAuth(itemsRequest = itemsRequest, sessionTranscriptBytes = sessionTranscriptBytes)
        }.getOrElse { ReaderAuthVerification(present = true, signatureValid = false, certificateSubject = null) }

        return DirectMdocRequest(
            origin = origin,
            protocol = PROTOCOL_ORG_ISO_MDOC,
            data = data,
            deviceRequestBase64Url = deviceRequestBase64Url,
            encryptionInfoBase64Url = encryptionInfoBase64Url,
            deviceRequestBytes = deviceRequestBytes,
            encryptionInfoBytes = encryptionInfoBytes,
            itemsRequest = itemsRequest.copy(
                requestCarrierDebug = itemsRequest.requestCarrierDebug.copy(warnings = warnings.toList()),
            ),
            encryptionInfo = encryptionInfo,
            sessionTranscriptBytes = sessionTranscriptBytes,
            readerAuth = readerAuth,
            warnings = warnings.toList(),
        )
    }

    /**
     * The `data` of the request entry to answer: the `org-iso-mdoc` one, or
     * failing that any entry carrying a deviceRequest, with a "protocol" warning.
     */
    fun findOrgIsoMdocData(outerJson: JSONObject, warnings: MutableSet<String> = linkedSetOf()): JSONObject? {
        if (outerJson.has("deviceRequest")) return outerJson
        val requests = outerJson.optJSONArray("requests")
            ?: outerJson.optJSONObject("digital")?.optJSONArray("requests")
            ?: return null
        val entries = (0 until requests.length()).mapNotNull { requests.optJSONObject(it) }
        entries.firstOrNull { it.optString("protocol") == PROTOCOL_ORG_ISO_MDOC }?.optJSONObject("data")?.let { return it }
        val other = entries.firstOrNull { it.optJSONObject("data")?.has("deviceRequest") == true } ?: return null
        warnings += "protocol"
        return other.optJSONObject("data")
    }

    private fun parseEncryptionInfo(bytes: ByteArray, warnings: MutableSet<String>): DirectMdocEncryptionInfo {
        val decoded = MdocCbor.decode(bytes) { warnings += "cbor-duplicate-key" }
        // [WRQ-7]: shape problems warn; only an unusable recipient key fails.
        val list = decoded as? List<*>
        if (list == null || list.size != 2 || list[0] != "dcapi") warnings += "encryption-info"
        val fields = list?.getOrNull(1) as? Map<*, *> ?: error("encryptionInfo has no recipient key")
        val nonce = fields["nonce"] as? ByteArray
        if (nonce == null) warnings += "encryption-info"
        val cose = fields["recipientPublicKey"] as? Map<*, *> ?: error("encryptionInfo.recipientPublicKey missing")
        require(cose[1L] == 2L && cose[-1L] == 1L) { "recipientPublicKey is not an EC2 P-256 key" }
        require((cose[-2L] as? ByteArray)?.size == 32 && (cose[-3L] as? ByteArray)?.size == 32) {
            "recipientPublicKey x and y must be 32 bytes"
        }
        return DirectMdocEncryptionInfo(
            nonce = nonce ?: ByteArray(0),
            recipientPublicKey = SmartMdocCrypto.publicKeyFromCose(cose),
            recipientPublicKeyCose = cose,
        )
    }

    fun buildSessionTranscript(encryptionInfoBase64Url: String, origin: String): ByteArray {
        val dcapiInfo = MdocCbor.encode(listOf(encryptionInfoBase64Url, origin))
        val handover = listOf("dcapi", SmartMdocCrypto.sha256(dcapiInfo))
        return MdocCbor.encode(listOf(null, null, handover))
    }

    private fun verifyReaderAuth(
        itemsRequest: DecodedItemsRequest,
        sessionTranscriptBytes: ByteArray,
    ): ReaderAuthVerification {
        val readerAuthBytes = itemsRequest.readerAuthBytes ?: return ReaderAuthVerification.ABSENT
        val detachedPayload = SmartMdocCrypto.readerAuthenticationBytes(
            sessionTranscriptBytes = sessionTranscriptBytes,
            itemsRequestTag24Bytes = itemsRequest.itemsRequestTag24Bytes,
        )
        val verified = SmartMdocCrypto.verifyDetachedCoseSign1(
            coseSign1Bytes = readerAuthBytes,
            detachedPayload = detachedPayload,
        )
        return ReaderAuthVerification(
            present = true,
            signatureValid = verified.signatureValid,
            certificateSubject = verified.certificate.subjectX500Principal.name,
        )
    }
}
