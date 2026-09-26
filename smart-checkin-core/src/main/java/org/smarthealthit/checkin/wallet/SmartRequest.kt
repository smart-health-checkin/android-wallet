package org.smarthealthit.checkin.wallet

import org.json.JSONArray
import org.json.JSONObject

/**
 * Hydrates a `VerifiedRequest` (the Compose UI's existing model) from a SMART
 * Health Check-in request JSON pulled out of
 * `requestInfo["org.smarthealthit.checkin.request"]`.
 */
object SmartRequestAdapter {
    fun build(
        verifierOrigin: String,
        nonce: String,
        smartRequest: JSONObject,
        readerAuth: ReaderAuthVerification = ReaderAuthVerification.ABSENT,
        requestCarrierDebug: SmartRequestCarrierDebug = SmartRequestCarrierDebug(
            source = "requestInfo",
            requestInfoPresent = true,
        ),
    ): VerifiedRequest {
        // [REQ-2]: exact strings; a number 1 is not the version "1".
        require(smartRequest.opt("type") == "smart-health-checkin-request") {
            "type must be \"smart-health-checkin-request\""
        }
        require(smartRequest.opt("version") == "1") { "version must be the string \"1\"" }
        val requestId = requiredString(smartRequest, "id", "id")
        return VerifiedRequest(
            requestId = requestId,
            verifierOrigin = verifierOrigin,
            clientId = "",
            requestUri = "",
            responseUri = "",
            state = "",
            nonce = nonce,
            completion = "dc-api",
            clientMetadata = JSONObject(),
            dcqlQuery = JSONObject(),
            rawSmartRequestJson = smartRequest.toString(2),
            readerAuth = readerAuth,
            requestCarrierDebug = requestCarrierDebug,
            items = parseItems(smartRequest.optJSONArray("items")),
        )
    }

    private fun parseItems(requestsArray: JSONArray?): List<RequestItem> {
        require(requestsArray != null) { "items must be an array" }
        val out = ArrayList<RequestItem>(requestsArray.length())
        val ids = LinkedHashSet<String>()
        for (i in 0 until requestsArray.length()) {
            val item = requestsArray.optJSONObject(i) ?: error("items[$i] must be an object")
            val id = requiredString(item, "id", "items[$i].id")
            require(ids.add(id)) { "items[$i].id is duplicated" }
            requiredString(item, "title", "items[$i].title")
            // [REQ-2]: content that isn't an object with a string kind invalidates the request;
            // any other problem inside content affects only this item (§5.4).
            val content = item.optJSONObject("content") ?: error("items[$i].content must be an object")
            val kind = content.opt("kind")
            require(kind is String && kind.isNotEmpty()) { "items[$i].content.kind must be a non-empty string" }
            val accept = requiredStringArray(item.optJSONArray("accept"), "items[$i].accept")
            out += when (kind) {
                "form.fhir" -> parseQuestionnaireItem(id, item, content, accept)
                "selection.fhir" -> parseFhirResourcesItem(id, item, content, accept)
                // An extension selector kind (§5.4.3, [SEL-9]): answered unsupported, others still served.
                else -> unsupported(id, item, accept, "Selector kind \"$kind\" is not supported by this wallet")
            }
        }
        return out
    }

    private fun unsupported(id: String, item: JSONObject, accept: List<String>, reason: String) = RequestItem(
        id = id,
        title = item.optString("title").ifBlank { id },
        subtitle = reason,
        kind = RequestKind.Unknown,
        meta = JSONObject(item.toString()),
        acceptedMediaTypes = accept,
        unsupportedReason = reason,
    )

    private val SELECTION_MEMBERS = listOf("profiles", "profilesFrom", "resourceTypes")
    private val FORM_MEMBERS = listOf("questionnaireCanonical", "questionnaire")

    private fun parseQuestionnaireItem(
        id: String,
        item: JSONObject,
        content: JSONObject,
        accept: List<String>,
    ): RequestItem {
        // [FORM-1]: these make only this item unsupported. Members this spec doesn't
        // define (such as the pre-1.0 canonical and resource) are ignored ([JSON-3]).
        SELECTION_MEMBERS.firstOrNull { content.has(it) }?.let {
            return unsupported(id, item, accept, "A form item can't also carry $it")
        }
        val rawCanonical = content.opt("questionnaireCanonical")
        if (content.has("questionnaireCanonical") && !(rawCanonical is String && rawCanonical.isNotEmpty())) {
            return unsupported(id, item, accept, "questionnaireCanonical must be a non-empty string")
        }
        val rawQuestionnaire = content.opt("questionnaire")
        if (content.has("questionnaire") &&
            !(rawQuestionnaire is JSONObject && rawQuestionnaire.opt("resourceType") == "Questionnaire")
        ) {
            return unsupported(id, item, accept, "questionnaire is not a FHIR Questionnaire")
        }
        val resource = (rawQuestionnaire as? JSONObject)?.let { JSONObject(it.toString()) }
        if (resource == null && rawCanonical == null) {
            return unsupported(id, item, accept, "A form item needs questionnaireCanonical or questionnaire")
        }
        val meta = JSONObject(item.toString())
        if (resource != null) meta.put("questionnaire", resource)
        val canonical = questionnaireCanonical(rawCanonical as? String, resource)
        if (!canonical.isNullOrBlank()) {
            meta.put("questionnaireCanonical", canonical)
            meta.put("questionnaireUrl", canonical)
        }
        return RequestItem(
            id = id,
            title = item.optString("title").ifBlank {
                resource?.optString("title")?.ifBlank { null } ?: "Questionnaire"
            },
            subtitle = item.optString("summary").ifBlank {
                resource?.optString("description")?.ifBlank { null } ?: "Form answers requested by the verifier."
            },
            kind = RequestKind.Questionnaire,
            meta = meta,
            acceptedMediaTypes = accept,
        )
    }

    private fun parseFhirResourcesItem(
        id: String,
        item: JSONObject,
        content: JSONObject,
        accept: List<String>,
    ): RequestItem {
        // [SEL-8], [SEL-10]: these make only this item unsupported.
        FORM_MEMBERS.firstOrNull { content.has(it) }?.let {
            return unsupported(id, item, accept, "A selection item can't also carry $it")
        }
        for (member in SELECTION_MEMBERS) {
            if (content.has(member) && nonEmptyStrings(content.opt(member)) == null) {
                return unsupported(id, item, accept, "$member must be a non-empty array of strings")
            }
        }
        val profiles = nonEmptyStrings(content.opt("profiles")).orEmpty()
            .map { it.substringBefore('|').lowercase() }
            .toSet()
        val resourceTypes = nonEmptyStrings(content.opt("resourceTypes")).orEmpty().map { it.lowercase() }.toSet()
        val profileCollections = nonEmptyStrings(content.opt("profilesFrom")).orEmpty()
            .map { it.substringBefore('|').lowercase() }
            .toSet()
        val noFilters = SELECTION_MEMBERS.none { content.has(it) }
        val summary = item.optString("summary")
        return when {
            profiles.any { it.endsWith("/structuredefinition/c4dic-coverage") } ||
                "coverage" in resourceTypes -> RequestItem(
                id = id,
                title = item.optString("title").ifBlank { "Digital Insurance Card" },
                subtitle = summary.ifBlank { "Member coverage and payer details." },
                kind = RequestKind.Coverage,
                meta = JSONObject(item.toString()),
                acceptedMediaTypes = accept,
            )
            profiles.any {
                it.endsWith("/structuredefinition/c4dic-insuranceplan") ||
                    it.endsWith("/structuredefinition/sbc-insurance-plan")
            } || "insuranceplan" in resourceTypes -> RequestItem(
                id = id,
                title = item.optString("title").ifBlank { "Plan Benefits Summary" },
                subtitle = summary.ifBlank { "Benefits, cost sharing, and plan limits." },
                kind = RequestKind.Plan,
                meta = JSONObject(item.toString()),
                acceptedMediaTypes = accept,
            )
            // [SEL-7]: no filters means the wallet and Holder decide what's relevant.
            noFilters || profiles.any {
                it.endsWith("/structuredefinition/us-core-patient") ||
                    it.endsWith("/structuredefinition/us-core-condition-problems-health-concerns") ||
                    it.endsWith("/structuredefinition/us-core-allergyintolerance") ||
                    it.endsWith("/structuredefinition/us-core-medicationrequest") ||
                    it.endsWith("/structuredefinition/bundle-uv-ips")
            } || profileCollections.any {
                it == "http://hl7.org/fhir/us/core" ||
                    it == "http://hl7.org/fhir/uv/ips"
            } || resourceTypes.any {
                it in setOf("patient", "bundle", "immunization", "condition", "allergyintolerance", "medicationrequest", "diagnosticreport", "observation")
            } -> RequestItem(
                id = id,
                title = item.optString("title").ifBlank { "Clinical History" },
                subtitle = summary.ifBlank { "Patient summary, conditions, medications, and allergies." },
                kind = RequestKind.Clinical,
                meta = JSONObject(item.toString()),
                acceptedMediaTypes = accept,
            )
            // Profiles or types this wallet has no category for: still matched against its
            // records by meta.profile and resource type, so the answer is unavailable, not unsupported.
            else -> RequestItem(
                id = id,
                title = item.optString("title").ifBlank { id },
                subtitle = summary.ifBlank { "Requested FHIR resources." },
                kind = RequestKind.Unknown,
                meta = JSONObject(item.toString()),
                acceptedMediaTypes = accept,
            )
        }
    }

    /** A JSON array of one or more non-empty strings, else null. */
    private fun nonEmptyStrings(value: Any?): List<String>? {
        if (value !is JSONArray || value.length() == 0) return null
        val out = ArrayList<String>(value.length())
        for (i in 0 until value.length()) {
            val v = value.opt(i)
            if (v !is String || v.isEmpty()) return null
            out += v
        }
        return out
    }

    private fun questionnaireCanonical(direct: String?, resource: JSONObject?): String? {
        if (!direct.isNullOrEmpty()) return direct
        return resource?.let { questionnaire ->
            val url = questionnaire.optString("url")
            if (url.isBlank()) null else {
                val version = questionnaire.optString("version")
                if (version.isBlank()) url else "$url|$version"
            }
        }
    }

    private fun requiredString(obj: JSONObject, key: String, diagnosticPath: String): String {
        val value = obj.opt(key)
        require(value is String && value.isNotBlank()) { "$diagnosticPath missing or not a string" }
        return value
    }

    private fun requiredStringArray(array: JSONArray?, path: String): List<String> {
        require(array != null && array.length() > 0) { "$path must be a non-empty string array" }
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.opt(i)
            require(value is String && value.isNotBlank()) { "$path[$i] must be a non-empty string" }
            out += value
        }
        return out
    }
}
