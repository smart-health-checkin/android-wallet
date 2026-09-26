package org.smarthealthit.checkin.verifier

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.DigitalCredential
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetDigitalCredentialOption
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.smarthealthit.checkin.wallet.DirectMdocRequestParser
import org.smarthealthit.checkin.wallet.MdocCbor
import org.smarthealthit.checkin.wallet.SmartMdocBase64
import org.smarthealthit.checkin.wallet.SmartMdocCrypto
import java.security.MessageDigest
import java.util.UUID

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

    private lateinit var output: TextView
    private lateinit var browserCheckin: BrowserCheckin
    private var startedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browserCheckin = BrowserCheckin(this, BRIDGE_URL, ::onBrowserResult).also { it.bind() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 160, 32, 32)
        }
        root.addView(Button(this).apply {
            text = "Check in through the browser"
            contentDescription = "browser-checkin"
            setOnClickListener { checkInThroughBrowser() }
        })
        root.addView(Button(this).apply {
            text = "Check in with a wallet on this phone"
            contentDescription = "direct-checkin"
            setOnClickListener { checkInDirect() }
        })
        output = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 11f
        }
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        show("Ready.")
    }

    /** The SMART request to send: the `request` extra, or the bundled example, with a fresh id. */
    private fun smartRequest(): JSONObject {
        val json = intent.getStringExtra("request") ?: assets.open("smart-request.json").bufferedReader().readText()
        return JSONObject(json).put("id", "verifier-app-${UUID.randomUUID()}")
    }

    // ------------------------------------------------------------ through the browser

    private fun checkInThroughBrowser() {
        startedAt = SystemClock.elapsedRealtime()
        show("Opening the bridge page…")
        browserCheckin.start(smartRequest(), intent.getStringExtra("registry") ?: DEFAULT_REGISTRY)
    }

    private fun onBrowserResult(result: BrowserCheckin.Result) {
        val ms = SystemClock.elapsedRealtime() - startedAt
        // Come back to the front; this closes the Custom Tab above us.
        startActivity(Intent(this, VerifierActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        when (result) {
            is BrowserCheckin.Result.Completed -> {
                val response = result.json.getJSONObject("response")
                show("Received through the browser in $ms ms from ${result.json.optString("wallet")}: " +
                    "${result.chars} chars in ${result.parts} part(s)\n\n" + summarize(response))
                Log.i(TAG, "RESULT path=browser ok=true ms=$ms chars=${result.chars} parts=${result.parts} wallet=${result.json.optString("wallet")}")
            }
            BrowserCheckin.Result.Declined -> {
                show("Nothing was shared.")
                Log.i(TAG, "RESULT path=browser ok=false declined=true ms=$ms")
            }
            is BrowserCheckin.Result.Failed -> {
                show("Failed: ${result.message}")
                Log.i(TAG, "RESULT path=browser ok=false ms=$ms message=${result.message}")
            }
        }
    }

    // ------------------------------------------------------------ directly, with no browser

    private fun checkInDirect() {
        val built = OrgIsoMdocRequestBuilder.build(smartRequest().toString())
        val request = GetCredentialRequest(listOf(GetDigitalCredentialOption(built.requestJson)))
        show("Asking the phone's wallets…")
        val t0 = SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            try {
                val credential = CredentialManager.create(this@VerifierActivity).getCredential(this@VerifierActivity, request).credential
                if (credential !is DigitalCredential) return@launch show("Unexpected credential type ${credential.type}")
                val smartResponse = openDirectResponse(credential.credentialJson, built)
                val ms = SystemClock.elapsedRealtime() - t0
                show("Received directly in $ms ms\n\n" + summarize(JSONObject(smartResponse)))
                Log.i(TAG, "RESULT path=direct ok=true ms=$ms chars=${smartResponse.length}")
            } catch (e: GetCredentialException) {
                show("Failed: ${e.type} ${e.message}")
                Log.i(TAG, "RESULT path=direct ok=false type=${e.type}")
            } catch (t: Throwable) {
                show("Failed: $t")
                Log.e(TAG, "direct check-in failed", t)
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

    // ------------------------------------------------------------ display

    private fun summarize(response: JSONObject): String {
        val statuses = response.getJSONArray("requestStatus")
        val artifacts = response.getJSONArray("artifacts")
        return buildString {
            append("Items:\n")
            for (i in 0 until statuses.length()) statuses.getJSONObject(i).let { append("  ${it.getString("item")}: ${it.getString("status")}\n") }
            append("Artifacts: ${artifacts.length()}\n")
            for (i in 0 until artifacts.length()) artifacts.getJSONObject(i).let {
                append("  ${it.getString("id")} ${it.getString("mediaType")} → ${it.getJSONArray("fulfills").join(", ")}\n")
            }
        }
    }

    private fun show(text: String) {
        output.text = text
    }
}
