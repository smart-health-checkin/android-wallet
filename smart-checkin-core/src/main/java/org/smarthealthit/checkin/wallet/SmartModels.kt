package org.smarthealthit.checkin.wallet

import org.json.JSONObject

data class ReaderAuthVerification(
    val present: Boolean,
    val signatureValid: Boolean,
    val certificateSubject: String?,
) {
    companion object {
        val ABSENT = ReaderAuthVerification(
            present = false,
            signatureValid = false,
            certificateSubject = null,
        )
    }
}

data class VerifiedRequest(
    val requestId: String = "",
    val verifierOrigin: String,
    val clientId: String,
    val requestUri: String,
    val responseUri: String,
    val state: String,
    val nonce: String,
    val completion: String,
    val clientMetadata: JSONObject,
    val dcqlQuery: JSONObject,
    val rawSmartRequestJson: String = "",
    val readerAuth: ReaderAuthVerification = ReaderAuthVerification.ABSENT,
    val requestCarrierDebug: SmartRequestCarrierDebug = SmartRequestCarrierDebug(),
    val items: List<RequestItem>,
)

/**
 * Where the SMART request came from, and transport findings the wallet reported
 * while reading it (spec §8.4, warnings per [RCV-1]).
 */
data class SmartRequestCarrierDebug(
    val source: String = "none",
    val requestInfoPresent: Boolean = false,
    val warnings: List<String> = emptyList(),
) {
    fun label(): String {
        val found = if (requestInfoPresent) "requestInfo" else "none"
        return if (warnings.isEmpty()) found else "$found; warnings: ${warnings.joinToString(", ")}"
    }
}

data class RequestItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: RequestKind,
    val meta: JSONObject,
    val acceptedMediaTypes: List<String> = listOf("application/fhir+json"),
    /** Set when the wallet can't process this item (spec §5.4); it is answered `unsupported`. */
    val unsupportedReason: String? = null,
)

enum class RequestKind {
    Coverage,
    Plan,
    Clinical,
    Questionnaire,
    Unknown,
}

fun questionnaireTitle(questionnaire: JSONObject?): String? {
    return questionnaire?.optString("title")?.trim()?.ifBlank { null }
}

fun questionnaireTitleForRequestItem(item: RequestItem): String? {
    return if (item.kind == RequestKind.Questionnaire) {
        questionnaireTitle(item.meta.optJSONObject("questionnaire"))
    } else {
        null
    }
}

enum class RequestItemStatusCode(val wireValue: String) {
    Fulfilled("fulfilled"),
    Partial("partial"),
    Unavailable("unavailable"),
    Declined("declined"),
    Unsupported("unsupported"),
    Error("error"),
}

enum class WalletItemAvailability {
    Available,
    PartiallyAvailable,
    Unavailable,
    Unsupported,
    Error,
}

data class WalletCandidate(
    val id: String,
    val label: String,
    val subtitle: String,
    val resourceType: String? = null,
    val sourceName: String? = null,
    val selectedByDefault: Boolean = true,
    val value: JSONObject? = null,
)

data class RequestItemResolution(
    val itemId: String,
    val availability: WalletItemAvailability,
    val candidates: List<WalletCandidate>,
    val matchSummary: String,
    val detail: String? = null,
    val statusIfShared: RequestItemStatusCode = RequestItemStatusCode.Fulfilled,
)

interface SmartHealthWalletStore {
    fun resolveItems(items: List<RequestItem>): List<RequestItemResolution>

    fun buildArtifact(
        item: RequestItem,
        selectedCandidates: List<WalletCandidate>,
        questionnaireAnswers: Map<String, Any>,
    ): SmartHealthWalletArtifact

    fun prefillQuestionnaireAnswers(items: List<RequestItem>): Map<String, Any>
}

data class SmartHealthWalletArtifact(
    val mediaType: String = "application/fhir+json",
    val fhirVersion: String = "4.0.1",
    val value: JSONObject,
)
