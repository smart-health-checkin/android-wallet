package org.smarthealthit.checkin.wallet

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Wallet behaviors the spec rewrite settled (plan decisions D10, D17; spec §5.4, [CAN-5]). */
class WalletAlignmentTest {
    @Test
    fun appCallerOriginIsTheApkKeyHash() {
        // [TR-2]: android:apk-key-hash: + base64url (no padding) SHA-256 of the DER certificate.
        // Expected value computed independently: base64url(sha256("not a real certificate")).
        assertEquals(
            "android:apk-key-hash:ie7mtsdUFQjXU6Q-Kq-SZ-gfHI5DAhRnaXQsk9KTB3w",
            AppCallerOrigin.fromCertificate("not a real certificate".toByteArray()),
        )
    }

    @Test
    fun decliningEverythingIsAResponseWithEveryItemDeclined() {
        // [HOLD-4]: after review, declining everything returns a response, not an error.
        val request = request(item("a", RequestKind.Clinical), item("b", RequestKind.Coverage))
        val response = SmartCheckinResponseFactory.build(
            request = request,
            selectedItems = mapOf("a" to false, "b" to false),
            questionnaireAnswers = emptyMap(),
            walletStore = store(),
        )

        assertEquals(0, response.getJSONArray("artifacts").length())
        assertEquals(listOf("declined", "declined"), statuses(response).values.toList())
    }

    @Test
    fun unsupportedItemsStayUnsupportedEvenWhenSelected() {
        val bad = item("x", RequestKind.Unknown).copy(unsupportedReason = "Selector kind \"x.y\" is not supported")
        val response = SmartCheckinResponseFactory.build(
            request = request(bad),
            selectedItems = mapOf("x" to true),
            questionnaireAnswers = emptyMap(),
            walletStore = store(),
        )

        assertEquals("unsupported", statuses(response)["x"])
    }

    @Test
    fun anUnrecognizedProfileIsUnavailableNotUnsupported() {
        // A profile the wallet has no category for is still matched against its records by meta.profile.
        val unknown = item("labs", RequestKind.Unknown, JSONObject().put("kind", "selection.fhir")
            .put("profiles", JSONArray().put("http://example.org/StructureDefinition/some-lab")))
        val response = SmartCheckinResponseFactory.build(
            request = request(unknown),
            selectedItems = mapOf("labs" to true),
            questionnaireAnswers = emptyMap(),
            walletStore = store(),
        )

        assertEquals("unavailable", statuses(response)["labs"])
    }

    @Test
    fun aVersionedProfileNeedsThatExactVersionInMetaProfile() {
        // [CAN-5]: a record without meta.profile can't fulfill a versioned profile.
        val versioned = item("conditions", RequestKind.Clinical, JSONObject().put("kind", "selection.fhir")
            .put("profiles", JSONArray().put("http://hl7.org/fhir/us/core/StructureDefinition/us-core-condition-problems-health-concerns|7.0.0")))
        val response = SmartCheckinResponseFactory.build(
            request = request(versioned),
            selectedItems = mapOf("conditions" to true),
            questionnaireAnswers = emptyMap(),
            walletStore = store(),
        )

        assertEquals("unavailable", statuses(response)["conditions"])
    }

    private fun store() = ImportedFhirWalletStore(
        ImportedHealthRecords(
            importedAt = "now",
            providers = listOf(
                ImportedProviderRecords(
                    provider = "Test",
                    patientDisplayName = null,
                    patientBirthDate = null,
                    fetchedAt = null,
                    fhir = mapOf(
                        "Condition" to listOf(JSONObject().put("resourceType", "Condition").put("id", "c1")),
                        "Coverage" to listOf(JSONObject().put("resourceType", "Coverage").put("id", "cov1")),
                    ),
                ),
            ),
        ),
    )

    private fun item(id: String, kind: RequestKind, content: JSONObject = JSONObject().put("kind", "selection.fhir")) =
        RequestItem(id = id, title = id, subtitle = "", kind = kind, meta = JSONObject().put("id", id).put("content", content))

    private fun request(vararg items: RequestItem) = VerifiedRequest(
        requestId = "r1",
        verifierOrigin = "https://clinic.example",
        clientId = "",
        requestUri = "",
        responseUri = "",
        state = "",
        nonce = "",
        completion = "dc-api",
        clientMetadata = JSONObject(),
        dcqlQuery = JSONObject(),
        items = items.toList(),
    )

    private fun statuses(response: JSONObject): Map<String, String> {
        val rows = response.getJSONArray("requestStatus")
        return (0 until rows.length()).associate { rows.getJSONObject(it).getString("item") to rows.getJSONObject(it).getString("status") }
    }
}
