package org.smarthealthit.checkin.verifier

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.CredentialManager
import androidx.credentials.DigitalCredential
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetDigitalCredentialOption
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.smarthealthit.checkin.theme.SmartTheme
import org.smarthealthit.checkin.theme.enableSmartEdgeToEdge
import org.smarthealthit.checkin.wallet.DirectMdocRequestParser
import org.smarthealthit.checkin.wallet.MdocCbor
import org.smarthealthit.checkin.wallet.SmartMdocBase64
import org.smarthealthit.checkin.wallet.SmartMdocCrypto
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.AEADBadTagException

/**
 * Example: a native Android app as the Verifier for a SMART Health Check-in.
 *
 *  - "Check in through the browser" runs the web flow in a Custom Tab and gets
 *    the result back over a message channel ([BrowserCheckin]). It reaches
 *    every wallet a web page can: the phone's wallets and web wallets.
 *  - "Check in with a wallet on this phone" calls Credential Manager directly,
 *    with no browser. It reaches only the phone's wallets.
 *
 * Launch extras (used by the automated test): `request` (SMART request JSON)
 * and `registry` (a wallet registry URL for the bridge page's picker).
 */
@OptIn(ExperimentalDigitalCredentialApi::class)
class VerifierActivity : ComponentActivity() {
    companion object {
        const val TAG = "SHCVerifier"
        val BRIDGE_URL: Uri = Uri.parse("https://smart-health-checkin.org/client/demo/native-bridge.html")
        const val DEFAULT_REGISTRY = "https://smart-health-checkin.org/connectathon/wallets.json"
    }

    /** The last check-in's outcome, shown in the result card; null before the first. */
    private var outcome by mutableStateOf<Outcome?>(null)
    private lateinit var browserCheckin: BrowserCheckin
    private var startedAt = 0L
    /** The request the last check-in sent, for the items' titles. */
    private var sentRequest: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableSmartEdgeToEdge()
        browserCheckin = BrowserCheckin(this, BRIDGE_URL, ::onBrowserResult).also { it.bind() }
        setContent {
            SmartTheme {
                VerifierScreen(outcome = outcome, onBrowser = ::checkInThroughBrowser, onDirect = ::checkInDirect)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The bars' icons follow the new dark setting.
        enableSmartEdgeToEdge()
    }

    override fun onDestroy() {
        browserCheckin.unbind()
        super.onDestroy()
    }

    /** The SMART request to send: the `request` extra, or the bundled example, with a fresh id. */
    private fun smartRequest(): JSONObject {
        val json = intent.getStringExtra("request") ?: assets.open("smart-request.json").bufferedReader().readText()
        return JSONObject(json).put("id", "verifier-app-${UUID.randomUUID()}").also { sentRequest = it }
    }

    // ------------------------------------------------------------ through the browser

    private fun checkInThroughBrowser() {
        startedAt = SystemClock.elapsedRealtime()
        outcome = Outcome.Working("Opening the check-in page in the browser…")
        browserCheckin.start(smartRequest(), intent.getStringExtra("registry") ?: DEFAULT_REGISTRY)
    }

    private fun onBrowserResult(result: BrowserCheckin.Result) {
        val ms = SystemClock.elapsedRealtime() - startedAt
        // Come back to the front; this closes the Custom Tab above us.
        startActivity(Intent(this, VerifierActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        when (result) {
            is BrowserCheckin.Result.Completed -> {
                val response = result.json.getJSONObject("response")
                outcome = Outcome.Completed(
                    via = "Received through the browser.",
                    details = listOf(
                        "Wallet" to result.json.optString("wallet").ifBlank { "Not named" },
                        "Time" to "${number(ms)} ms",
                        "Size" to "${number(result.chars)} characters in ${result.parts} ${if (result.parts == 1) "part" else "parts"}",
                    ),
                    response = response,
                    request = sentRequest,
                )
                Log.i(TAG, "RESULT path=browser ok=true ms=$ms chars=${result.chars} parts=${result.parts} wallet=${result.json.optString("wallet")}")
            }
            BrowserCheckin.Result.Declined -> {
                outcome = Outcome.Declined("Nothing was shared: the check-in was declined or closed in the browser.")
                Log.i(TAG, "RESULT path=browser ok=false declined=true ms=$ms")
            }
            is BrowserCheckin.Result.Failed -> {
                outcome = Outcome.Failed(result.message)
                Log.i(TAG, "RESULT path=browser ok=false ms=$ms message=${result.message}")
            }
        }
    }

    // ------------------------------------------------------------ directly, with no browser

    private fun checkInDirect() {
        val built = OrgIsoMdocRequestBuilder.build(smartRequest().toString())
        val request = GetCredentialRequest(listOf(GetDigitalCredentialOption(built.requestJson)))
        outcome = Outcome.Working("Asking the wallets on this phone…")
        val t0 = SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            try {
                val credential = CredentialManager.create(this@VerifierActivity).getCredential(this@VerifierActivity, request).credential
                if (credential !is DigitalCredential) {
                    outcome = Outcome.Failed("Credential Manager returned something other than a digital credential.", "Credential type: ${credential.type}")
                    return@launch
                }
                val smartResponse = openDirectResponse(credential.credentialJson, built)
                val ms = SystemClock.elapsedRealtime() - t0
                outcome = Outcome.Completed(
                    via = "Received directly from a wallet on this phone.",
                    details = listOf(
                        "Time" to "${number(ms)} ms",
                        "Size" to "${number(smartResponse.length)} characters",
                    ),
                    response = JSONObject(smartResponse),
                    request = sentRequest,
                )
                Log.i(TAG, "RESULT path=direct ok=true ms=$ms chars=${smartResponse.length}")
            } catch (e: GetCredentialException) {
                outcome = Outcome.Failed(
                    "Credential Manager ended the check-in without a response. The wallet may have been closed, or no wallet on this phone can answer.",
                    listOfNotNull(e.type, e.message).joinToString("\n"),
                )
                Log.i(TAG, "RESULT path=direct ok=false type=${e.type}")
            } catch (t: Throwable) {
                // AEADBadTagException (BAD_DECRYPT): the wallet sealed the answer to a different
                // transcript, usually a wallet older than v0.4.2, from before android:apk-key-hash: origins.
                val plain = if (generateSequence(t) { it.cause }.any { it is AEADBadTagException || it.message?.contains("BAD_DECRYPT") == true }) {
                    "This wallet's answer couldn't be opened by this app. The wallet may be out of date, " +
                        "or it sealed the answer for a different origin."
                } else {
                    "The wallet's answer couldn't be read."
                }
                outcome = Outcome.Failed(plain, t.toString())
                Log.e(TAG, "direct check-in failed", t)
                Log.i(TAG, "RESULT path=direct ok=false error=$t")
            }
        }
    }

    /**
     * Opens the wallet's HPKE-sealed DeviceResponse and returns the SMART response JSON.
     * The transcript's origin is this app's `android:apk-key-hash:` string (spec TR-2),
     * because Android reports no web origin for an app caller. A production app would also
     * verify the mdoc signatures and validate the response against its request (spec §8.5).
     */
    private fun openDirectResponse(credentialJson: String, built: OrgIsoMdocRequestBuilder.BuiltRequest): String {
        val dcapi = MdocCbor.decode(SmartMdocBase64.decodeUrl(JSONObject(credentialJson).getJSONObject("data").getString("response"))) as List<*>
        val fields = dcapi[1] as Map<*, *>
        val transcript = DirectMdocRequestParser.buildSessionTranscript(built.encryptionInfoB64u, appOrigin())
        val opened = SmartMdocCrypto.hpkeOpen(fields["enc"] as ByteArray, fields["cipherText"] as ByteArray, built.recipientKeyPair, transcript)
        val document = ((MdocCbor.decode(opened) as Map<*, *>)["documents"] as List<*>)[0] as Map<*, *>
        val items = ((document["issuerSigned"] as Map<*, *>)["nameSpaces"] as Map<*, *>)[OrgIsoMdocRequestBuilder.NAMESPACE] as List<*>
        return (MdocCbor.decodeTag24(items[0]) as Map<*, *>)["elementValue"] as String
    }

    /** `android:apk-key-hash:` + base64url SHA-256 of this app's signing certificate. */
    private fun appOrigin(): String {
        val cert = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo!!.apkContentsSigners[0].toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(cert)
        return "android:apk-key-hash:" + Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
