package org.smarthealthit.checkin.verifier

import android.app.Activity
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 *     library) in a Custom Tab, in a new Custom Tabs session.
 *  2. Ask Chrome for a message channel to that page. Chrome grants it only if
 *     the site's /.well-known/assetlinks.json names this app with
 *     `delegate_permission/common.use_as_origin`.
 *  3. Send the page the SMART request. The page answers `started`, runs the
 *     check-in like any web page (the phone's wallet or a web wallet), decrypts
 *     and validates the response, and sends it back in parts, since each channel
 *     message crosses Android IPC and is capped at about 1 MB.
 *  4. Reassemble the parts, check the hash, and hand the result to [onResult].
 *
 * Each check-in uses its own session: Chrome keeps one message channel per
 * session, and a second channel requested on a used session reports ready but
 * never reaches the new page. If the page hasn't answered `started` within
 * [START_TIMEOUT_MS], the check-in fails instead of waiting forever.
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
    private val main = Handler(Looper.getMainLooper())
    private var connection: CustomTabsServiceConnection? = null
    private var client: CustomTabsClient? = null
    private var run: Run? = null

    /** Connect to the browser's Custom Tabs service. Call early (e.g. in onCreate); [start] also connects if needed. */
    fun bind() {
        if (connection != null) return
        val browser = CustomTabsClient.getPackageName(activity, null) ?: return
        val c = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                client.warmup(0L)
                this@BrowserCheckin.client = client
                run?.takeIf { it.waitingForClient }?.open(client)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                client = null
                connection = null
            }
        }
        if (CustomTabsClient.bindCustomTabsService(activity, browser, c)) connection = c
    }

    /** Disconnect from the browser (e.g. in onDestroy). */
    fun unbind() {
        run?.finish(null)
        connection?.let { runCatching { activity.unbindService(it) } }
        connection = null
        client = null
    }

    /** Open the bridge page and send it `{"type":"checkin","request":…,"registry":…}` once the channel is up. */
    fun start(smartRequest: JSONObject, registryUrl: String?) {
        run?.finish(null) // A new check-in replaces one still in progress.
        val message = JSONObject()
            .put("type", "checkin")
            .put("request", smartRequest)
            .apply { if (registryUrl != null) put("registry", registryUrl) }
            .toString()
        val r = Run(message)
        run = r
        main.postDelayed(r.timeout, START_TIMEOUT_MS)
        val c = client
        if (c != null) return r.open(c)
        r.waitingForClient = true
        bind()
        if (connection == null) r.finish(Result.Failed("No browser on this device supports Custom Tabs"))
    }

    /** One check-in: its own session, callback, and state. Callbacks arrive on the main thread. */
    private inner class Run(val message: String) {
        var waitingForClient = false
        var session: CustomTabsSession? = null
        var originVerified = false
        var pageLoaded = false
        var channelRequested = false
        var channelReady = false
        var started = false
        var done = false

        // Reassembly state for the result.
        var expectedParts = 0
        var expectedSha256 = ""
        var parts = arrayOfNulls<String>(0)

        val timeout = Runnable {
            if (started) return@Runnable
            val stage = when {
                !originVerified -> "Chrome hasn't verified $bridgeOrigin for this app"
                !pageLoaded -> "the bridge page hasn't loaded"
                !channelReady -> "the message channel isn't open"
                else -> "the page didn't answer the request"
            }
            finish(Result.Failed("The bridge page didn't start the check-in within ${START_TIMEOUT_MS / 1000} s: $stage"))
        }

        fun open(client: CustomTabsClient) {
            waitingForClient = false
            if (done) return
            val s = client.newSession(callback) ?: return finish(Result.Failed("The browser didn't create a Custom Tabs session"))
            session = s
            // Chrome checks the site's Digital Asset Links; the answer arrives in onRelationshipValidationResult.
            if (!s.validateRelationship(CustomTabsService.RELATION_USE_AS_ORIGIN, bridgeOrigin, null)) {
                return finish(Result.Failed("The browser didn't accept the request to verify $bridgeOrigin"))
            }
            CustomTabsIntent.Builder(s).build().launchUrl(activity, bridgeUrl)
        }

        val callback = object : CustomTabsCallback() {
            override fun onRelationshipValidationResult(relation: Int, requestedOrigin: Uri, result: Boolean, extras: Bundle?) {
                if (done || originVerified) return
                Log.i(TAG, "origin $requestedOrigin verified: $result")
                if (!result) return finish(Result.Failed("$requestedOrigin doesn't list this app in /.well-known/assetlinks.json"))
                originVerified = true
                openChannelWhenReady()
            }

            override fun onNavigationEvent(navigationEvent: Int, extras: Bundle?) {
                if (done || started) return
                when (navigationEvent) {
                    NAVIGATION_FINISHED -> { pageLoaded = true; openChannelWhenReady() }
                    NAVIGATION_FAILED -> finish(Result.Failed("The bridge page didn't load"))
                }
            }

            override fun onMessageChannelReady(extras: Bundle?) {
                if (done || channelReady) return
                channelReady = true
                // The page receives this first message on `window`, together with the channel's port.
                val posted = session?.postMessage(message, null)
                Log.i(TAG, "message channel ready; request posted: $posted")
                if (posted != CustomTabsService.RESULT_SUCCESS) finish(Result.Failed("Couldn't send the request to the bridge page (result $posted)"))
            }

            override fun onPostMessage(message: String, extras: Bundle?) {
                if (!done) handle(JSONObject(message))
            }
        }

        // The channel needs both a verified origin and a loaded page; they can arrive in either order.
        fun openChannelWhenReady() {
            if (!originVerified || !pageLoaded || channelRequested) return
            channelRequested = true
            val ok = session?.requestPostMessageChannel(bridgeOrigin, bridgeOrigin, Bundle()) ?: false
            Log.i(TAG, "message channel requested: $ok")
            if (!ok) finish(Result.Failed("The browser refused a message channel to $bridgeOrigin"))
        }

        fun handle(message: JSONObject) {
            when (message.optString("type")) {
                "started" -> {
                    Log.i(TAG, "the bridge page started the check-in")
                    started = true
                    main.removeCallbacks(timeout)
                }
                "result-begin" -> {
                    Log.i(TAG, "result-begin parts=${message.getInt("total")} chars=${message.optInt("chars")}")
                    started = true
                    main.removeCallbacks(timeout)
                    expectedParts = message.getInt("total")
                    expectedSha256 = message.getString("sha256")
                    parts = arrayOfNulls(expectedParts)
                }
                "result-part" -> parts[message.getInt("i")] = message.getString("data")
                "result-end" -> {
                    if (parts.any { it == null }) return finish(Result.Failed("Missing parts of the result"))
                    val text = parts.joinToString("")
                    val sha256 = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    if (sha256 != expectedSha256) return finish(Result.Failed("The result's hash doesn't match"))
                    Log.i(TAG, "result-end: ${text.length} chars reassembled, hash matches")
                    finish(Result.Completed(JSONObject(text), text.length, expectedParts))
                }
                "declined" -> finish(Result.Declined)
                "failed" -> finish(Result.Failed(message.optString("message", "The check-in failed")))
                else -> Log.w(TAG, "Unknown message from the bridge page: ${message.optString("type")}")
            }
        }

        /** Ends this check-in; later callbacks from its session are ignored. `null` ends it without a result. */
        fun finish(result: Result?) {
            if (done) return
            done = true
            main.removeCallbacks(timeout)
            if (run === this) run = null
            if (result != null) main.post { onResult(result) }
        }
    }

    private companion object {
        const val TAG = "SHCBrowserCheckin"
        const val START_TIMEOUT_MS = 30_000L
    }
}
