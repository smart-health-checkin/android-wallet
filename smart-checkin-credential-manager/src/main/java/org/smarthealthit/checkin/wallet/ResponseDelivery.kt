package org.smarthealthit.checkin.wallet

import android.content.Intent
import android.os.ResultReceiver
import androidx.core.os.BundleCompat
import androidx.credentials.provider.ProviderGetCredentialRequest

/**
 * How the wallet's response reaches the caller.
 *
 * Return the response with the three-argument
 * [androidx.credentials.provider.PendingIntentHandler.setGetCredentialResponse]
 * (`androidx.credentials` 1.7 or later). When the caller put a `ResultReceiver`
 * in the request under [EXTRA_LARGE_PAYLOAD_RESULT_RECEIVER] (current Chrome
 * does), androidx writes a large response to an unlinked temp file and hands
 * the caller a file descriptor, so responses of any size get through.
 * Otherwise the response rides in the result `Intent`. The deprecated
 * two-argument overload always uses the `Intent`.
 *
 * The three-argument overload picks the path by itself, so a wallet needs
 * nothing from this class to be correct. It lets a wallet see which path a
 * request will use, and afterwards which path the response took. This sample
 * app only logs it.
 *
 * The extra keys are `@RestrictTo(LIBRARY)` in androidx, i.e. this reads a
 * de-facto handshake rather than a public API; they have been stable across
 * androidx 1.7.0-alpha01..alpha03 and Chrome pins the same strings.
 */
object ResponseDelivery {
    /** Present in an option's `requestData` when the caller accepts large responses out of band. */
    const val EXTRA_LARGE_PAYLOAD_RESULT_RECEIVER =
        "androidx.credentials.provider.EXTRA_LARGE_PAYLOAD_RESULT_RECEIVER"

    /** Set on the result Intent by androidx when the response actually went out of band. */
    const val EXTRA_PASS_IT_BY_RESULT_RECEIVER =
        "androidx.credentials.provider.EXTRA_PASS_IT_BY_RESULT_RECEIVER"

    /** Below this parcel size androidx keeps the response on the Intent even in large-payload mode. */
    const val LARGE_PAYLOAD_PIVOT_BYTES = 200 * 1024

    /** Reported as [ResponseDeliveryMode.budgetChars] on the Intent path. Logged only; nothing enforces it. */
    const val LEGACY_BUDGET_CHARS = 200_000

    /** Reported as [ResponseDeliveryMode.budgetChars] on the file path. Logged only; nothing enforces it. */
    const val LARGE_PAYLOAD_BUDGET_CHARS = 20_000_000

    data class ResponseDeliveryMode(
        /** The caller offered a large-payload `ResultReceiver`; androidx hands large responses over as a file. */
        val callerAcceptsLargePayloads: Boolean,
        /** Wallet's managed-heap cap for this process, in MB (`Runtime.maxMemory()`). */
        val heapMaxMB: Long,
    ) {
        /** A rough `credentialJson` length for this path. Logged only; nothing enforces it. */
        val budgetChars: Int
            get() = if (callerAcceptsLargePayloads) LARGE_PAYLOAD_BUDGET_CHARS else LEGACY_BUDGET_CHARS

        val label: String
            get() = if (callerAcceptsLargePayloads) "large-payload" else "intent-extra (legacy)"
    }

    /** Inspect the request the provider activity was launched with. */
    fun describe(request: ProviderGetCredentialRequest?): ResponseDeliveryMode =
        ResponseDeliveryMode(
            callerAcceptsLargePayloads = callerAcceptsLargePayloads(request),
            heapMaxMB = Runtime.getRuntime().maxMemory() / (1024 * 1024),
        )

    fun callerAcceptsLargePayloads(request: ProviderGetCredentialRequest?): Boolean =
        request?.credentialOptions?.any { option ->
            BundleCompat.getParcelable(
                option.requestData,
                EXTRA_LARGE_PAYLOAD_RESULT_RECEIVER,
                ResultReceiver::class.java,
            ) != null
        } == true

    /** After `setGetCredentialResponse`: did androidx actually send this response out of band? */
    fun wentOutOfBand(resultIntent: Intent): Boolean = resultIntent.hasExtra(EXTRA_PASS_IT_BY_RESULT_RECEIVER)
}
