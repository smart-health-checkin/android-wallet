package org.smarthealthit.checkin.wallet

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.fail
import org.junit.Test

/**
 * The spec's conformance cases (github.com/smart-health-checkin/spec,
 * conformance/), fetched at a pinned ref by scripts/fetch-conformance.sh.
 * Every claimed case must pass except those in conformance/known-failures.json,
 * which must still fail: a listed case that passes fails this test until it is
 * removed from the list.
 *
 * wallet-response cases only build the credential here and write it to
 * build/conformance-wallet/; scripts/verify-wallet-conformance.ts then checks
 * it with the reference verifier (the client library).
 */
class ConformanceTest {
    private val root = File("../spec-conformance")
    private val config = JSONObject(File("../conformance/known-failures.json").readText())
    private val walletOut = File("build/conformance-wallet").apply { mkdirs() }

    @Test
    fun specConformanceCases() {
        val manifest = JSONObject(File(root, "manifest.json").readText())
        val claims = config.getJSONArray("claims").toStrings().toSet()
        val known = config.getJSONObject("knownFailures")
        val unexpectedFailures = mutableListOf<String>()
        val unexpectedPasses = mutableListOf<String>()
        var ran = 0
        val cases = manifest.getJSONArray("cases")
        for (n in 0 until cases.length()) {
            val c = cases.getJSONObject(n)
            val id = c.getString("id")
            if (c.getString("status") == "pending" || c.getString("capability") !in claims) continue
            ran++
            val passed = try { run(c) } catch (e: Throwable) { false }
            if (known.has(id)) {
                if (passed) unexpectedPasses += id
            } else if (!passed) {
                unexpectedFailures += "$id: ${c.getString("description")}"
            }
        }
        if (unexpectedFailures.isNotEmpty() || unexpectedPasses.isNotEmpty()) {
            fail(buildString {
                append("Conformance ($ran cases run):\n")
                if (unexpectedFailures.isNotEmpty()) append("Unexpected failures:\n  ${unexpectedFailures.joinToString("\n  ")}\n")
                if (unexpectedPasses.isNotEmpty()) append("Now passing, remove from conformance/known-failures.json:\n  ${unexpectedPasses.joinToString("\n  ")}\n")
            })
        }
    }

    private fun input(c: JSONObject, key: String) = File(root, c.getJSONObject("inputs").getString(key))
    private fun text(c: JSONObject, key: String) = input(c, key).readText()
    private fun expectedValid(c: JSONObject) = c.getJSONObject("expected").getBoolean("valid")
    private fun output(c: JSONObject, key: String) = File(root, c.getJSONObject("expected").getJSONObject("outputs").getString(key))

    /** Did the wallet reach the expected verdict (and outputs) for the case? */
    private fun run(c: JSONObject): Boolean {
        val valid = expectedValid(c)
        return when (c.getString("capability")) {
            "request-json" -> verdict(valid) {
                SmartRequestAdapter.build("https://clinic.example", "nonce", JSONObject(text(c, "request")))
                true
            }
            "request-cbor" -> verdict(valid) {
                val parsed = DirectMdocRequestParser.parseRequestJson(text(c, "navigatorArgument"), "https://clinic.example")
                val smart = parsed.itemsRequest.smartRequestJson ?: error("no SMART request")
                SmartRequestAdapter.build("https://clinic.example", "nonce", smart)
                !valid || smart.similar(JSONObject(output(c, "smartRequest").readText()))
            }
            "transcript" -> {
                val t = DirectMdocRequestParser.buildSessionTranscript(text(c, "encryptionInfo").trim(), text(c, "origin").trim())
                t.contentEquals(output(c, "sessionTranscript").readBytes())
            }
            "wallet-response" -> {
                val origin = text(c, "origin").trim()
                val request = DirectMdocRequestParser.parseRequestJson(text(c, "navigatorArgument"), origin)
                val response = SmartHealthMdocResponder.buildCredentialResponse(request, JSONObject(text(c, "smartResponse")))
                File(walletOut, c.getString("id").replace('/', '_') + ".json").writeText(response.credentialJson)
                true
            }
            else -> error("no runner for ${c.getString("capability")}")
        }
    }

    private fun verdict(expectedValid: Boolean, check: () -> Boolean): Boolean =
        try { check() == expectedValid } catch (e: Throwable) { !expectedValid }

    private fun JSONArray.toStrings() = (0 until length()).map { getString(it) }
}
