package org.smarthealthit.checkin.verifier

import android.app.Activity
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Runs a SMART Health Check-in through the web flow and brings the result back
 * into this app, with no server in between.
 *
 *  1. Open the bridge page (a page on your own domain that uses the client
 *     library) in a Custom Tab.
 *  2. Ask Chrome for a message channel to that page. Chrome grants it only if
 *     the site's /.well-known/assetlinks.json names this app with
 *     `delegate_permission/common.use_as_origin`.
 *  3. Send the page the SMART request. The page runs the check-in like any web
 *     page (the phone's wallet or a web wallet), decrypts and validates the
 *     response, and sends it back in parts, since each channel message crosses
 *     Android IPC and is capped at about 1 MB.
 *  4. Reassemble the parts, check the hash, and hand the result to [onResult].
 *
 * The page's side of the exchange is documented in native-bridge.ts in the
 * client library's demos.
 */
class BrowserCheckin(
    private val activity: Activity,
    private val bridgeUrl: Uri,
    private val onResult: (Result) -> Unit,
) {
    sealed class Result {
        /** `json` is `{"response": <SMART response>, "wallet": "<wallet id>"}`. */
        data class Completed(val json: JSONObject, val chars: Int, val parts: Int) : Result()
        object Declined : Result()
        data class Failed(val message: String) : Result()
    }

    private val bridgeOrigin: Uri = Uri.parse("${bridgeUrl.scheme}://${bridgeUrl.authority}")
    private var session: CustomTabsSession? = null
    private var pendingMessage: String? = null
    private var originValidated = false
    private var pageLoaded = false
    private var channelRequested = false

    // Reassembly state for one result.
    private var expectedParts = 0
    private var expectedSha256 = ""
    private var parts = arrayOfNulls<String>(0)

    /** Connect to the browser's Custom Tabs service. Call once, early (e.g. in onCreate). */
    fun bind() {
        val browser = CustomTabsClient.getPackageName(activity, null) ?: return
        CustomTabsClient.bindCustomTabsService(activity, browser, object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                client.warmup(0L)
                session = client.newSession(callback)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                session = null
            }
        })
    }

    /** Open the bridge page and send it `{"type":"checkin","request":…,"registry":…}` once the channel is up. */
    fun start(smartRequest: JSONObject, registryUrl: String?) {
        val session = session ?: return onResult(Result.Failed("The browser's Custom Tabs service isn't connected"))
        pendingMessage = JSONObject()
            .put("type", "checkin")
            .put("request", smartRequest)
            .apply { if (registryUrl != null) put("registry", registryUrl) }
            .toString()
        originValidated = false
        pageLoaded = false
        channelRequested = false
        // Chrome checks the site's Digital Asset Links; the answer arrives in onRelationshipValidationResult.
        session.validateRelationship(CustomTabsService.RELATION_USE_AS_ORIGIN, bridgeOrigin, null)
        CustomTabsIntent.Builder(session).build().launchUrl(activity, bridgeUrl)
    }

    private val callback = object : CustomTabsCallback() {
        override fun onRelationshipValidationResult(relation: Int, requestedOrigin: Uri, result: Boolean, extras: Bundle?) {
            Log.i(TAG, "origin $requestedOrigin verified: $result")
            if (!result) return deliver(Result.Failed("$requestedOrigin doesn't list this app in /.well-known/assetlinks.json"))
            originValidated = true
            openChannelWhenReady()
        }

        override fun onNavigationEvent(navigationEvent: Int, extras: Bundle?) {
            if (navigationEvent != NAVIGATION_FINISHED) return
            pageLoaded = true
            openChannelWhenReady()
        }
        override fun onMessageChannelReady(extras: Bundle?) {
            Log.i(TAG, "message channel ready")
            // The page receives this first message on `window`, together with the channel's port.
            pendingMessage?.let { session?.postMessage(it, null) }
            pendingMessage = null
        }

        override fun onPostMessage(message: String, extras: Bundle?) {
            handle(JSONObject(message))
        }
    }

    // The channel needs both a verified origin and a loaded page; they can arrive in either order.
    private fun openChannelWhenReady() {
        if (!originValidated || !pageLoaded || channelRequested) return
        channelRequested = true
        val ok = session?.requestPostMessageChannel(bridgeOrigin, bridgeOrigin, Bundle()) ?: false
        Log.i(TAG, "message channel requested: $ok")
    }

    private fun handle(message: JSONObject) {
        when (message.optString("type")) {
            "result-begin" -> {
                Log.i(TAG, "result-begin parts=${message.getInt("total")} chars=${message.optInt("chars")}")
                expectedParts = message.getInt("total")
                expectedSha256 = message.getString("sha256")
                parts = arrayOfNulls(expectedParts)
            }
            "result-part" -> parts[message.getInt("i")] = message.getString("data")
            "result-end" -> {
                if (parts.any { it == null }) return deliver(Result.Failed("Missing parts of the result"))
                val text = parts.joinToString("")
                val sha256 = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                if (sha256 != expectedSha256) return deliver(Result.Failed("The result's hash doesn't match"))
                Log.i(TAG, "result-end: ${text.length} chars reassembled, hash matches")
                deliver(Result.Completed(JSONObject(text), text.length, expectedParts))
            }
            "declined" -> deliver(Result.Declined)
            "failed" -> deliver(Result.Failed(message.optString("message", "The check-in failed")))
            else -> Log.w(TAG, "Unknown message from the bridge page: ${message.optString("type")}")
        }
    }

    private fun deliver(result: Result) = activity.runOnUiThread { onResult(result) }

    private companion object {
        const val TAG = "SHCBrowserCheckin"
    }
}
