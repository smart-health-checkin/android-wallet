package org.smarthealthit.checkin.wallet

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.smarthealthit.checkin.theme.enableSmartEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.DigitalCredential
import androidx.credentials.ExperimentalDigitalCredentialApi
import androidx.credentials.GetDigitalCredentialOption
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.CallingAppInfo
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderGetCredentialRequest
import androidx.credentials.provider.SigningInfoCompat
import androidx.credentials.registry.provider.selectedEntryId
import androidx.lifecycle.lifecycleScope
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * The activity Credential Manager launches via PendingIntent when the user
 * picks our SMART Health Check-in entry.
 *
 * Stage C scope:
 *   - Read ProviderGetCredentialRequest.
 *   - Find the org-iso-mdoc option and parse the SMART request out of
 *     `ItemsRequest.requestInfo["org.smarthealthit.checkin.request"]`.
 *   - Drive the existing Compose holder review UI (DemoApp + ConsentScreen).
 *   - On submit, package the SMART-shaped response JSON as a direct mdoc
 *     DeviceResponse and return it as a DigitalCredential.
 */
@OptIn(ExperimentalDigitalCredentialApi::class)
class HandlerActivity : ComponentActivity() {
    companion object {
        private const val TAG = "SHCHandler"
    }

    private var screenState by mutableStateOf<ScreenState>(ScreenState.Loading("Reading request", "Decoding the SMART Health Check-in request."))
    private var verifiedRequest: VerifiedRequest? = null
    private var callerIdentity by mutableStateOf<CallerIdentity?>(null)
    private var directMdocRequest: DirectMdocRequest? = null
    private var providerRequest: ProviderGetCredentialRequest? = null
    private var itemResolutions: List<RequestItemResolution> = emptyList()
    private val selectedItems = mutableStateMapOf<String, Boolean>()
    private val selectedCandidates = mutableStateMapOf<String, Set<String>>()
    private val questionnaireAnswers = mutableStateMapOf<String, Any>()
    private val referencePatient: String by lazy {
        getSharedPreferences(ReferencePatients.PREFS, MODE_PRIVATE)
            .getString(ReferencePatients.PREF_KEY, ReferencePatients.ARIA) ?: ReferencePatients.ARIA
    }
    private val walletStoreMode: String by lazy {
        if (ImportedHealthRecordsRepository.load(filesDir) != null) "imported-health-skillz" else "reference-patient-$referencePatient"
    }
    private val walletStore: SmartHealthWalletStore by lazy {
        ImportedHealthRecordsRepository.load(filesDir)?.let(::ImportedFhirWalletStore)
            ?: ImportedFhirWalletStore(
                ReferencePatients.fromBundle(
                    org.json.JSONObject(assets.open(ReferencePatients.assetPath(referencePatient)).bufferedReader().use { it.readText() }),
                    ReferencePatients.labels[referencePatient] ?: "Reference patient",
                ),
                sourceLabel = "the synthetic reference patient ${ReferencePatients.labels[referencePatient] ?: referencePatient}",
            )
    }
    private var runId: String = "run-${Instant.now().toEpochMilli()}"

    private data class OriginResolution(
        val origin: String,
        val source: String,
        val isOriginPopulated: Boolean,
        val allowlistFingerprintCount: Int,
        val allowlistJson: String?,
        val error: String?,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableSmartEdgeToEdge()

        setContent {
            WalletTheme {
                DemoApp(
                    state = screenState,
                    caller = callerIdentity,
                    selectedItems = selectedItems,
                    selectedCandidates = selectedCandidates,
                    questionnaireAnswers = questionnaireAnswers,
                    onItemSelected = { id, selected -> selectedItems[id] = selected },
                    onCandidateSelected = ::setCandidateSelected,
                    onAnswerChanged = ::setQuestionnaireAnswer,
                    onShare = { submit(declined = false) },
                    onDecline = { submit(declined = true) },
                    onClose = { finishWithCancel() },
                )
            }
        }

        try {
            val req = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
            if (req == null) {
                screenState = ScreenState.Error("No Credential Manager request found in the launch intent.")
                return
            }
            handleRequest(req)
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate retrieve failed", t)
            screenState = ScreenState.Error(t.message ?: t::class.java.simpleName)
        }
    }

    private fun handleRequest(req: ProviderGetCredentialRequest) {
        providerRequest = req
        val callingAppInfo = req.callingAppInfo
        val originResolution = resolveOrigin(callingAppInfo)
        val origin = originResolution.origin
        val selectedEntryId = runCatching { req.selectedEntryId }.getOrNull()
        Log.i(
            TAG,
            "request origin=$origin source=${originResolution.source} " +
                "originPopulated=${originResolution.isOriginPopulated} " +
                "pkg=${callingAppInfo.packageName} selectedEntryId=$selectedEntryId " +
                "originError=${originResolution.error}",
        )
        identifyCaller(callingAppInfo, originResolution)

        // Whether the caller accepts a large response as a file. This sample
        // only logs it; see ResponseDelivery.
        val delivery = ResponseDelivery.describe(req)
        Log.i(TAG, "response delivery mode=${delivery.label} heapMaxMB=${delivery.heapMaxMB}")

        val mdocOption = req.credentialOptions
            .filterIsInstance<GetDigitalCredentialOption>()
            .firstOrNull()
        if (mdocOption == null) {
            screenState = ScreenState.Error("No GetDigitalCredentialOption in the request.")
            return
        }

        val parsed = runCatching {
            DirectMdocRequestParser.parseRequestJson(mdocOption.requestJson, origin)
        }
            .onFailure { Log.e(TAG, "DeviceRequest decode failed", it) }
            .getOrNull()
        if (parsed == null) {
            screenState = ScreenState.Error("DeviceRequest is not a SMART Health Check-in request.")
            return
        }
        directMdocRequest = parsed

        if (parsed.warnings.isNotEmpty()) {
            // Spec §8.4: these don't stop the Wallet; they're reported ([RCV-1]).
            Log.w(TAG, "request transport warnings: ${parsed.warnings.joinToString(", ")}")
        }
        val smartJson = parsed.itemsRequest.smartRequestJson
        if (smartJson == null) {
            screenState = ScreenState.Error(
                "No SMART request JSON found in requestInfo."
            )
            return
        }

        // Persist the raw request for adb-pulled debugging.
        saveDebugBundle(
            origin = origin,
            packageName = callingAppInfo.packageName,
            selectedEntryId = selectedEntryId,
            originResolution = originResolution,
            outerJson = runCatching { JSONObject(mdocOption.requestJson).toString(2) }.getOrElse { mdocOption.requestJson },
            deviceRequestB64u = parsed.deviceRequestBase64Url,
            encryptionInfoB64u = parsed.encryptionInfoBase64Url,
            smartJson = smartJson,
            deviceRequestBytes = parsed.deviceRequestBytes,
            encryptionInfoBytes = parsed.encryptionInfoBytes,
            sessionTranscriptBytes = parsed.sessionTranscriptBytes,
            itemsRequestTag24Bytes = parsed.itemsRequest.itemsRequestTag24Bytes,
            readerAuthBytes = parsed.itemsRequest.readerAuthBytes,
            readerAuth = parsed.readerAuth,
            requestCarrierDebug = parsed.itemsRequest.requestCarrierDebug,
        )

        screenState = ScreenState.Loading("Loading request forms", "Fetching any questionnaires the request refers to.")
        lifecycleScope.launch {
            val hydratedSmartJson = runCatching {
                SmartQuestionnaireFetcher.hydrateQuestionnaireUrls(smartJson)
            }.onFailure { Log.e(TAG, "questionnaireUrl fetch failed", it) }
                .getOrElse {
                    screenState = ScreenState.Error(it.message ?: it::class.java.simpleName)
                    return@launch
                }
            appendToDebugBundle("smart-request.hydrated.json", hydratedSmartJson.toString(2))
            prepareConsent(origin, hydratedSmartJson, parsed.readerAuth, parsed.itemsRequest.requestCarrierDebug)
        }
    }

    /**
     * What the consent screen shows as who is asking. A browser caller is its web
     * origin. An app caller is just "an app": Credential Manager gives only its
     * package name and signing certificate, which the Technical details show. This
     * is display only: the session transcript keeps the `android:apk-key-hash:` origin.
     */
    private fun identifyCaller(callingAppInfo: CallingAppInfo, originResolution: OriginResolution) {
        callerIdentity = if (originResolution.source == "web-origin") {
            CallerIdentity.Website(originResolution.origin)
        } else {
            CallerIdentity.App(originResolution.origin, callingAppInfo.packageName)
        }
    }

    /**
     * The origin for the session transcript (spec [TR-2]). A browser on the
     * privileged-caller allowlist reports the web page's origin. Any other app
     * caller gets none from Android, so the origin is
     * `android:apk-key-hash:<base64url SHA-256 of its signing certificate>`, the
     * format Android's holder documentation, Multipaz, and Google Wallet use.
     */
    private fun resolveOrigin(callingAppInfo: CallingAppInfo): OriginResolution {
        val allowlist = runCatching { buildCallerAllowlist(callingAppInfo) }.getOrNull()
        val webOrigin = allowlist?.let {
            runCatching { callingAppInfo.getOrigin(it.json) }
                .onFailure { e -> Log.w(TAG, "callingAppInfo.getOrigin failed", e) }
                .getOrNull()
        }
        val populated = runCatching { callingAppInfo.isOriginPopulated() }.getOrDefault(false)
        if (webOrigin != null) {
            return OriginResolution(
                origin = webOrigin.trimEnd('/'),
                source = "web-origin",
                isOriginPopulated = populated,
                allowlistFingerprintCount = allowlist.fingerprintCount,
                allowlistJson = allowlist.json,
                error = null,
            )
        }
        return OriginResolution(
            origin = AppCallerOrigin.of(callingAppInfo.signingInfoCompat),
            source = "android-apk-key-hash",
            isOriginPopulated = populated,
            allowlistFingerprintCount = allowlist?.fingerprintCount ?: 0,
            allowlistJson = allowlist?.json,
            error = "getOrigin returned no web origin: an app caller",
        )
    }

    private data class CallerAllowlist(val json: String, val fingerprintCount: Int)

    private fun buildCallerAllowlist(callingAppInfo: CallingAppInfo): CallerAllowlist {
        // AndroidX reveals a browser-supplied web origin only when the calling app's
        // package and signing certificate are on this allowlist. This development
        // build puts whatever app called it on the list, so it will accept any app's
        // claim to be speaking for a web origin. That's convenient for testing across
        // browsers and wrong for production: a real wallet keeps a list of the browsers
        // it trusts (package names and their official signing-certificate fingerprints).
        val fingerprints = originVerificationFingerprints(callingAppInfo.signingInfoCompat)
        val signatures = JSONArray()
        fingerprints.forEach { fingerprint ->
            signatures.put(JSONObject().put("cert_fingerprint_sha256", fingerprint))
        }
        val allowlist = JSONObject()
            .put(
                "apps",
                JSONArray().put(
                    JSONObject()
                        .put("type", "android")
                        .put(
                            "info",
                            JSONObject()
                                .put("package_name", callingAppInfo.packageName)
                                .put("signatures", signatures),
                        ),
                ),
            )
        return CallerAllowlist(
            json = allowlist.toString(),
            fingerprintCount = fingerprints.size,
        )
    }

    private fun originVerificationFingerprints(signingInfo: SigningInfoCompat): List<String> {
        val signatures = if (signingInfo.hasMultipleSigners && signingInfo.apkContentsSigners.isNotEmpty()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory.take(1)
        }
        return signatures
            .map { signatureFingerprint(it.toByteArray()) }
            .distinct()
    }

    private fun signatureFingerprint(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    private fun prepareConsent(
        origin: String,
        smartJson: JSONObject,
        readerAuth: ReaderAuthVerification,
        requestCarrierDebug: SmartRequestCarrierDebug,
    ) {
        val request = runCatching {
            SmartRequestAdapter.build(
                verifierOrigin = origin,
                nonce = "", // TODO: pull from EncryptionInfo when wired
                smartRequest = smartJson,
                readerAuth = readerAuth,
                requestCarrierDebug = requestCarrierDebug,
            )
        }.onFailure { Log.e(TAG, "SMART request validation failed", it) }
            .getOrElse {
                screenState = ScreenState.Error(it.message ?: it::class.java.simpleName)
                return
            }
        verifiedRequest = request
        itemResolutions = runCatching { walletStore.resolveItems(request.items) }
            .onFailure { Log.e(TAG, "request item resolution failed", it) }
            .getOrElse {
                screenState = ScreenState.Error(it.message ?: it::class.java.simpleName)
                return
            }
        Log.i(TAG, "wallet store mode=$walletStoreMode resolvedItems=${itemResolutions.size}")
        selectedItems.clear()
        selectedCandidates.clear()
        questionnaireAnswers.clear()
        itemResolutions.forEach { resolution ->
            selectedItems[resolution.itemId] = true
            selectedCandidates[resolution.itemId] = resolution.candidates
                .filter { it.selectedByDefault }
                .map { it.id }
                .toSet()
        }
        val prefills = runCatching { walletStore.prefillQuestionnaireAnswers(request.items) }
            .onFailure { Log.e(TAG, "questionnaire prefill failed", it) }
            .getOrElse {
                screenState = ScreenState.Error(it.message ?: it::class.java.simpleName)
                return
            }
        questionnaireAnswers.putAll(prefills)
        screenState = ScreenState.Consent(request, itemResolutions)
    }

    private fun setCandidateSelected(itemId: String, candidateId: String, selected: Boolean) {
        val current = selectedCandidates[itemId].orEmpty()
        selectedCandidates[itemId] = if (selected) current + candidateId else current - candidateId
    }

    private fun setQuestionnaireAnswer(key: String, value: Any?) {
        if (value == null) {
            questionnaireAnswers.remove(key)
        } else if (value is String && value.isBlank()) {
            questionnaireAnswers.remove(key)
        } else if (value is Collection<*> && value.isEmpty()) {
            questionnaireAnswers.remove(key)
        } else {
            questionnaireAnswers[key] = value
        }
    }

    private fun submit(declined: Boolean) {
        val req = verifiedRequest ?: return
        val mdocRequest = directMdocRequest ?: run {
            screenState = ScreenState.Error("No decoded mdoc request is available.")
            return
        }
        val selectedSummary = JSONObject()
        selectedItems.forEach { (k, v) -> selectedSummary.put(k, v) }
        val candidateSummary = JSONObject()
        selectedCandidates.forEach { (itemId, candidates) -> candidateSummary.put(itemId, JSONArray(candidates.toList())) }
        val summary = JSONObject()
            .put("declined", declined)
            .put("selectedItems", selectedSummary)
            .put("selectedCandidates", candidateSummary)
            .put("answerCount", questionnaireAnswers.size)
            .put("origin", req.verifierOrigin)
            .put("itemCount", req.items.size)
            .put("walletStoreMode", walletStoreMode)
        appendToDebugBundle("submit.json", summary.toString(2))

        // [HOLD-4]: the Holder reviewed the request and declined everything, so the
        // Verifier gets a normal response with every item declined. Only closing the
        // wallet without reviewing (finishWithCancel) ends the call with an error.
        if (declined) req.items.forEach { selectedItems[it.id] = false }

        screenState = ScreenState.Submitting("Building response", "Packaging selected data as an encrypted mdoc response.")
        // Building, signing, and encrypting a large record takes seconds, so it
        // runs off the main thread; only handing the result back runs on it.
        lifecycleScope.launch {
            val built = withContext(Dispatchers.Default) {
                val smartResponse = runCatching {
                    SmartCheckinResponseFactory.build(
                        request = req,
                        selectedItems = selectedItems.toMap(),
                        questionnaireAnswers = questionnaireAnswers.toMap(),
                        walletStore = walletStore,
                        resolutions = itemResolutions,
                        selectedCandidates = selectedCandidates.toMap(),
                    )
                }.onFailure { Log.e(TAG, "SMART response build failed", it) }
                    .getOrElse { return@withContext Result.failure(it) }
                appendToDebugBundle("smart-response.json", smartResponse.toString(2))
                val probePad = PayloadProbe.applyPadding(this@HandlerActivity, smartResponse)

                val walletResponse = runCatching {
                    SmartHealthMdocWallet.buildCredentialResponse(
                        request = mdocRequest,
                        smartResponse = smartResponse,
                    )
                }.onFailure { Log.e(TAG, "DeviceResponse build failed", it) }
                    .getOrElse { return@withContext Result.failure(it) }
                if (probePad == 0) {
                    // Fixture-capture artifacts; skipped under the payload probe, where they
                    // would re-serialize and write hundreds of MB per trial.
                    appendToDebugBundle("wallet-response.digital-credential.json", JSONObject(walletResponse.credentialJson).toString(2))
                    appendToDebugBundle("credential.json", JSONObject(walletResponse.credentialJson).toString(2))
                    appendToDebugBundle("smart-response.expected.json", smartResponse.toString(2))
                    appendBinaryArtifact("device-response.cbor", walletResponse.deviceResponseBytes)
                    appendBinaryArtifact("dcapi-response.cbor", walletResponse.dcapiResponseBytes)
                    appendBinaryArtifact("hpke-enc.bin", walletResponse.hpkeEnc)
                    appendBinaryArtifact("hpke-ciphertext.bin", walletResponse.hpkeCipherText)
                    appendBinaryArtifact("issuer-signed-item-tag24.cbor", walletResponse.issuerSignedItemTag24Bytes)
                    appendBinaryArtifact("value-digest.bin", walletResponse.valueDigest)
                    appendBinaryArtifact("mso.cbor", walletResponse.msoBytes)
                    appendBinaryArtifact("issuer-auth.cbor", walletResponse.issuerAuthBytes)
                    appendBinaryArtifact("device-authentication.cbor", walletResponse.deviceAuthenticationBytes)
                }
                Result.success(walletResponse to probePad)
            }
            built.onSuccess { (walletResponse, probePad) -> deliver(walletResponse, probePad) }
                .onFailure { screenState = ScreenState.Error(it.message ?: it::class.java.simpleName) }
        }
    }

    private fun deliver(walletResponse: DirectMdocWalletResponse, probePad: Int) {
        val resultData = Intent()
        val response = GetCredentialResponse(DigitalCredential(walletResponse.credentialJson))
        // The three-argument overload lets androidx (>= 1.7.0-alpha01) hand a
        // large response to the caller through a ResultReceiver + file
        // descriptor instead of the Binder-bound Intent extra, when the caller
        // (Chrome) offered one, so responses of any size get through. The
        // two-argument overload is deprecated and always uses the Intent
        // extra; PayloadProbe can force it for testing.
        val providerReq = providerRequest
        val legacyPath = PayloadProbe.forceLegacyPath(this) || providerReq == null
        if (legacyPath) {
            @Suppress("DEPRECATION")
            PendingIntentHandler.setGetCredentialResponse(resultData, response)
        } else {
            PendingIntentHandler.setGetCredentialResponse(resultData, response, providerReq)
        }
        PayloadProbe.logSizes(
            pad = probePad,
            path = if (legacyPath) "legacy-2arg" else "large-payload-3arg",
            receiverOffered = ResponseDelivery.callerAcceptsLargePayloads(providerReq),
            passedByReceiver = ResponseDelivery.wentOutOfBand(resultData),
            credentialJson = walletResponse.credentialJson,
            resultData = resultData,
        )
        setResult(Activity.RESULT_OK, resultData)
        try {
            finish()
        } catch (t: Throwable) {
            PayloadProbe.logFinishFailure(probePad, t)
            throw t
        }
    }

    private fun finishWithCancel() {
        val resultData = Intent()
        PendingIntentHandler.setGetCredentialException(
            resultData,
            GetCredentialUnknownException("Wallet UI closed without a selection."),
        )
        setResult(Activity.RESULT_OK, resultData)
        finish()
    }

    private fun debugRunDir(): File {
        val parent = File(filesDir, "handler-runs/$runId")
        parent.mkdirs()
        return parent
    }

    private fun saveDebugBundle(
        origin: String,
        packageName: String,
        selectedEntryId: String?,
        originResolution: OriginResolution,
        outerJson: String,
        deviceRequestB64u: String,
        encryptionInfoB64u: String,
        smartJson: JSONObject,
        deviceRequestBytes: ByteArray,
        encryptionInfoBytes: ByteArray,
        sessionTranscriptBytes: ByteArray,
        itemsRequestTag24Bytes: ByteArray,
        readerAuthBytes: ByteArray?,
        readerAuth: ReaderAuthVerification,
        requestCarrierDebug: SmartRequestCarrierDebug,
    ) {
        val dir = debugRunDir()
        val manifest = JSONObject()
            .put("runId", runId)
            .put("at", Instant.now().toString())
            .put("origin", origin)
            .put("packageName", packageName)
            .put("selectedEntryId", selectedEntryId ?: JSONObject.NULL)
            .put("originSource", originResolution.source)
            .put("originIsPopulated", originResolution.isOriginPopulated)
            .put("originAllowlistFingerprintCount", originResolution.allowlistFingerprintCount)
            .put("originError", originResolution.error ?: JSONObject.NULL)
            .put("protocol", Registration.PROTOCOL)
            .put("deviceRequestB64uSize", deviceRequestB64u.length)
            .put("deviceRequestByteSize", deviceRequestBytes.size)
            .put("encryptionInfoB64uSize", encryptionInfoB64u.length)
            .put("encryptionInfoByteSize", encryptionInfoBytes.size)
            .put("sessionTranscriptByteSize", sessionTranscriptBytes.size)
            .put("readerAuthPresent", readerAuth.present)
            .put("readerAuthSignatureValid", if (readerAuth.present) readerAuth.signatureValid else JSONObject.NULL)
            .put("readerAuthCertificateSubject", readerAuth.certificateSubject ?: JSONObject.NULL)
            .put("requestCarrierSource", requestCarrierDebug.source)
            .put("requestInfoPresent", requestCarrierDebug.requestInfoPresent)
            .put("transportWarnings", JSONArray(requestCarrierDebug.warnings))
        if (originResolution.allowlistJson != null) {
            manifest.put("originAllowlist", JSONObject(originResolution.allowlistJson))
        }
        File(dir, "manifest.json").writeText(manifest.toString(2))
        File(dir, "metadata.json").writeText(manifest.toString(2))
        File(dir, "credential-manager-request.json").writeText(outerJson)
        File(dir, "navigator-credentials-get.arg.json").writeText(outerJson)
        File(dir, "request.json").writeText(outerJson)
        File(dir, "device-request.b64u").writeText("$deviceRequestB64u\n")
        File(dir, "encryption-info.b64u").writeText("$encryptionInfoB64u\n")
        writeBinaryArtifact(dir, "device-request.cbor", deviceRequestBytes)
        writeBinaryArtifact(dir, "encryption-info.cbor", encryptionInfoBytes)
        writeBinaryArtifact(dir, "session-transcript.cbor", sessionTranscriptBytes)
        writeBinaryArtifact(dir, "items-request-tag24.cbor", itemsRequestTag24Bytes)
        if (readerAuthBytes != null) {
            writeBinaryArtifact(dir, "reader-auth.cbor", readerAuthBytes)
            writeBinaryArtifact(
                dir,
                "reader-auth-detached-payload.cbor",
                SmartMdocCrypto.readerAuthenticationBytes(
                    sessionTranscriptBytes = sessionTranscriptBytes,
                    itemsRequestTag24Bytes = itemsRequestTag24Bytes,
                ),
            )
        }
        File(dir, "smart-request.json").writeText(smartJson.toString(2))
        File(dir, "smart-request.expected.json").writeText(smartJson.toString(2))
        Log.i(TAG, "wrote debug bundle to ${dir.absolutePath}")
    }

    private fun appendToDebugBundle(name: String, contents: String) {
        runCatching {
            File(debugRunDir(), name).writeText(contents)
        }.onFailure { Log.w(TAG, "failed to append $name", it) }
    }

    private fun appendBinaryArtifact(name: String, contents: ByteArray) {
        runCatching {
            writeBinaryArtifact(debugRunDir(), name, contents)
        }.onFailure { Log.w(TAG, "failed to append $name", it) }
    }

    private fun writeBinaryArtifact(dir: File, name: String, contents: ByteArray) {
        File(dir, name).writeBytes(contents)
        File(dir, "$name.hex").writeText("${hex(contents)}\n")
        File(dir, "$name.b64u").writeText("${SmartMdocBase64.encodeUrl(contents)}\n")
    }

    private fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val alphabet = "0123456789abcdef"
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xff
            chars[i * 2] = alphabet[v ushr 4]
            chars[i * 2 + 1] = alphabet[v and 0x0f]
        }
        return String(chars)
    }
}
