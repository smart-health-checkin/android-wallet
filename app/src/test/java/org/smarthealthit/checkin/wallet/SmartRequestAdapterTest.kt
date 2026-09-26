package org.smarthealthit.checkin.wallet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRequestAdapterTest {
    @Test
    fun rejectsMalformedRequiredFields() {
        assertFailsWithMessage(
            """{"type":"smart-health-checkin-request","version":"1","items":[]}""",
            "id missing",
        )
        assertFailsWithMessage(
            """{"type":"smart-health-checkin-request","version":"1","id":"r1","items":[{"title":"Missing id","content":{"kind":"selection.fhir"},"accept":["application/fhir+json"]}]}""",
            "items[0].id missing",
        )
        assertFailsWithMessage(
            """{"type":"smart-health-checkin-request","version":"1","id":"r1","items":[{"id":"patient","title":"Patient","content":{"kind":"selection.fhir"},"accept":[]}]}""",
            "items[0].accept",
        )
    }

    @Test
    fun answersSelectionsWithNoFilters() {
        // [SEL-7]: no filters means the wallet and Holder decide; it is not unsupported.
        val request = parse(
            """{"type":"smart-health-checkin-request","version":"1","id":"r1","items":[{"id":"anything","title":"Any FHIR resources","content":{"kind":"selection.fhir"},"accept":["application/fhir+json"]}]}""",
        )

        assertEquals(RequestKind.Clinical, request.items.single().kind)
        assertNull(request.items.single().unsupportedReason)
    }

    @Test
    fun rejectsANumberVersion() {
        // [REQ-2]: the number 1 is not the string "1".
        assertFailsWithMessage(
            """{"type":"smart-health-checkin-request","version":1,"id":"r1","items":[]}""",
            "version",
        )
    }

    @Test
    fun ignoresPre10SelectorMembers() {
        // D13 / [JSON-3]: canonical and resource are unknown members, ignored.
        val item = parse(
            """{"type":"smart-health-checkin-request","version":"1","id":"r1","items":[{"id":"intake","title":"Intake","content":{"kind":"form.fhir","questionnaireCanonical":"https://example.org/Questionnaire/q","canonical":"x","resource":{}},"accept":["application/fhir+json"]}]}""",
        ).items.single()

        assertEquals(RequestKind.Questionnaire, item.kind)
        assertNull(item.unsupportedReason)
    }

    @Test
    fun badSelectorMakesOnlyThatItemUnsupported() {
        // [FORM-1], [SEL-8], [SEL-10]: the request stands; the other item is served.
        val request = parse(
            """{"type":"smart-health-checkin-request","version":"1","id":"r1","items":[
              {"id":"intake","title":"Intake","content":{"kind":"form.fhir","questionnaireCanonical":"https://example.org/Questionnaire/q","profiles":["http://example.org/p"]},"accept":["application/fhir+json"]},
              {"id":"patient","title":"Patient","content":{"kind":"selection.fhir","profilesFrom":"http://hl7.org/fhir/us/core"},"accept":["application/fhir+json"]},
              {"id":"ok","title":"Conditions","content":{"kind":"selection.fhir","resourceTypes":["Condition"]},"accept":["application/fhir+json"]}
            ]}""",
        )

        assertNotNull(request.items[0].unsupportedReason)
        assertNotNull(request.items[1].unsupportedReason)
        assertNull(request.items[2].unsupportedReason)
    }

    @Test
    fun routesByCanonicalProfilesInsteadOfKeywordSubstrings() {
        val request = parse(
            """{"type":"smart-health-checkin-request","version":"1","id":"r1","items":[{"id":"insuranceplan","title":"Coverage","content":{"kind":"selection.fhir","profiles":["http://hl7.org/fhir/us/insurance-card/StructureDefinition/C4DIC-Coverage|1.0.0"]},"accept":["application/fhir+json"]}]}""",
        )

        assertEquals(RequestKind.Coverage, request.items.single().kind)
    }

    @Test
    fun routesUsCoreProfileFamilyArraysAsClinical() {
        val request = parse(
            """{"type":"smart-health-checkin-request","version":"1","id":"r1","items":[{"id":"clinical-history","title":"US Core clinical resources","summary":"US Core resources, including patient demographics, problems, medications, and allergies.","content":{"kind":"selection.fhir","profilesFrom":["http://hl7.org/fhir/us/core"],"profiles":["http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient","http://hl7.org/fhir/us/core/StructureDefinition/us-core-medicationrequest"]},"accept":["application/fhir+json"]}]}""",
        )

        val item = request.items.single()
        assertEquals(RequestKind.Clinical, item.kind)
        assertEquals("US Core clinical resources", item.title)
        assertEquals(
            "US Core resources, including patient demographics, problems, medications, and allergies.",
            item.subtitle,
        )
    }

    @Test
    fun stripsCanonicalVersionBeforeQuestionnaireFetch() {
        assertEquals(
            "https://example.org/fhir/Questionnaire/intake",
            SmartQuestionnaireFetcher.canonicalUrlForFetch("https://example.org/fhir/Questionnaire/intake|1.2.3"),
        )
    }

    private fun parse(raw: String): VerifiedRequest =
        SmartRequestAdapter.build(
            verifierOrigin = "https://clinic.example",
            nonce = "",
            smartRequest = JSONObject(raw),
        )

    private fun assertFailsWithMessage(raw: String, expectedMessage: String) {
        val failure = runCatching { parse(raw) }.exceptionOrNull()
        assertNotNull("Expected request parsing to fail", failure)
        assertTrue(
            "Expected '${failure?.message}' to contain '$expectedMessage'",
            failure?.message?.contains(expectedMessage) == true,
        )
    }
}
