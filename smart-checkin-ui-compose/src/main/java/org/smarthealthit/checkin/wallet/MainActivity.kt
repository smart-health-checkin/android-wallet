package org.smarthealthit.checkin.wallet

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import org.smarthealthit.checkin.theme.CodeBlock
import org.smarthealthit.checkin.theme.SmartCard
import org.smarthealthit.checkin.theme.SmartHeading
import org.smarthealthit.checkin.theme.SmartPrimaryButton
import org.smarthealthit.checkin.theme.SmartRadius
import org.smarthealthit.checkin.theme.SmartSecondaryButton
import org.smarthealthit.checkin.theme.SmartTextButton
import org.smarthealthit.checkin.theme.SmartTheme
import org.smarthealthit.checkin.theme.SmartTopBar
import org.smarthealthit.checkin.theme.StatusPill
import org.smarthealthit.checkin.theme.StatusTone
import org.smarthealthit.checkin.theme.enableSmartEdgeToEdge
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.time.Instant

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "SHCMain"
    }

    // State held here will be wired into the DC API HandlerActivity in Stage C.
    // For Stage B, MainActivity is just the home screen — registration status,
    // bundled demo-data summary, and a build stamp. The remaining state +
    // helper methods below are intentionally preserved so the HandlerActivity
    // can be added with minimal churn.
    @Suppress("unused")
    private var screenState by mutableStateOf<ScreenState>(ScreenState.Empty)
    @Suppress("unused")
    private var verifiedRequest: VerifiedRequest? = null
    private val selectedItems = mutableStateMapOf<String, Boolean>()
    private val selectedCandidates = mutableStateMapOf<String, Set<String>>()
    private val questionnaireAnswers = mutableStateMapOf<String, Any>()

    private var registration: RegistrationState by mutableStateOf(RegistrationState.Idle)
    private var importedRecords: ImportedHealthRecords? by mutableStateOf(null)
    private var importedSummary: ImportedHealthRecordsSummary? by mutableStateOf(null)
    private var importState: ImportState by mutableStateOf(ImportState.Idle)
    private var referencePatient: String by mutableStateOf(ReferencePatients.ARIA)

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importHealthRecords(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableSmartEdgeToEdge()
        importedRecords = runCatching { ImportedHealthRecordsRepository.load(filesDir) }
            .onFailure { Log.w(TAG, "failed to load imported records", it) }
            .getOrNull()
        importedSummary = importedRecords?.summary()
        referencePatient = getSharedPreferences(ReferencePatients.PREFS, MODE_PRIVATE)
            .getString(ReferencePatients.PREF_KEY, ReferencePatients.ARIA) ?: ReferencePatients.ARIA

        setContent {
            WalletTheme {
                HomeScreen(
                    registration = registration,
                    importedRecords = importedRecords,
                    importedSummary = importedSummary,
                    importState = importState,
                    onRegister = ::registerWithCredentialManager,
                    onImportRecords = ::openImportPicker,
                    onClearImportedRecords = ::clearImportedRecords,
                    referencePatient = referencePatient,
                    onReferencePatientChange = { key ->
                        referencePatient = key
                        getSharedPreferences(ReferencePatients.PREFS, MODE_PRIVATE).edit()
                            .putString(ReferencePatients.PREF_KEY, key).apply()
                    },
                    onClose = { finish() },
                )
            }
        }
        registerWithCredentialManager()
    }

    private fun openImportPicker() {
        importLauncher.launch(
            arrayOf(
                "application/zip",
                "application/json",
                "application/octet-stream",
                "*/*",
            ),
        )
    }

    private fun importHealthRecords(uri: Uri) {
        if (importState is ImportState.Pending) return
        importState = ImportState.Pending
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val displayName = displayNameFor(uri)
                    contentResolver.openInputStream(uri).use { input ->
                        require(input != null) { "Could not open selected file." }
                        ImportedHealthRecordsRepository.importFromStream(filesDir, displayName, input)
                    }
                }
            }
            result
                .onSuccess { records ->
                    importedRecords = records
                    val summary = records.summary()
                    importedSummary = summary
                    importState = ImportState.Success(
                        "Imported ${summary.totalResources} FHIR ${if (summary.totalResources == 1) "resource" else "resources"}.",
                    )
                }
                .onFailure { error ->
                    Log.e(TAG, "Health Skillz import failed", error)
                    importState = ImportState.Failed(error.message ?: error::class.java.simpleName)
                }
        }
    }

    private fun clearImportedRecords() {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { ImportedHealthRecordsRepository.clear(filesDir) }
            }.onFailure { Log.w(TAG, "failed to clear imported records", it) }
            importedRecords = null
            importedSummary = null
            importState = ImportState.Idle
        }
    }

    private fun displayNameFor(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    private fun registerWithCredentialManager() {
        if (registration is RegistrationState.Pending) return
        registration = RegistrationState.Pending
        lifecycleScope.launch {
            registration = when (val r = Registration.register(this@MainActivity)) {
                is RegistrationResult.Success -> RegistrationState.Registered(
                    matcherBytes = r.matcherBytes,
                    credentialsBytes = r.credentialsBytes,
                    mode = r.mode,
                    registeredTypes = r.registeredTypes,
                )
                is RegistrationResult.Failure -> RegistrationState.Failed(r.message)
            }
        }
    }

    @Suppress("unused") // wired in Stage C via HandlerActivity
    private fun prepareConsent(request: VerifiedRequest) {
        verifiedRequest = request
        selectedItems.clear()
        selectedCandidates.clear()
        questionnaireAnswers.clear()

        val resolutions = request.items.map(::sampleResolutionForUi)
        request.items.forEach { item ->
            selectedItems[item.id] = true
            selectedCandidates[item.id] = setOf("sample-${item.id}")
            val questionnaire = item.meta.optJSONObject("questionnaire")
            if (item.kind == RequestKind.Questionnaire && questionnaire != null) {
                seedQuestionnaireAnswers(
                    credentialId = item.id,
                    items = questionnaire.optJSONArray("item"),
                    prefill = demoPrefillFor(questionnaire),
                )
            }
        }

        screenState = ScreenState.Consent(request, resolutions)
    }

    private fun sampleResolutionForUi(item: RequestItem): RequestItemResolution {
        return RequestItemResolution(
            itemId = item.id,
            availability = WalletItemAvailability.Available,
            candidates = listOf(
                WalletCandidate(
                    id = "sample-${item.id}",
                    label = item.title.ifBlank { "Sample data" },
                    subtitle = item.subtitle.ifBlank { "Sample wallet record" },
                    sourceName = "Sample data",
                ),
            ),
            matchSummary = "1 sample record available",
        )
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

    // submit / complete / resolveRequest / verifyRequestObject / extractRequestItems
    // were removed in Stage B. The DC API path replaces them: HandlerActivity
    // (Stage C) reads a ProviderGetCredentialRequest, decodes the org-iso-mdoc
    // DeviceRequest, pulls the SMART request JSON out of
    // ItemsRequest.requestInfo["org.smarthealthit.checkin.request"], builds a SMART-request-shape
    // VerifiedRequest, and calls prepareConsent below. DeviceResponse build +
    // HPKE seal arrive after that.

    @Suppress("unused") // wired in Stage C
    private fun seedQuestionnaireAnswers(credentialId: String, items: JSONArray?, prefill: JSONObject) {
        jsonObjects(items).forEach { item ->
            when (item.optString("type")) {
                "group" -> seedQuestionnaireAnswers(credentialId, item.optJSONArray("item"), prefill)
                "display" -> Unit
                else -> {
                    val linkId = item.optString("linkId")
                    val initial = normalizeInitialValue(item, initialValueForItem(item, prefill))
                    if (linkId.isNotBlank() && initial != null) {
                        questionnaireAnswers[answerKey(credentialId, linkId)] = initial
                    }
                }
            }
        }
    }

    private fun demoPrefillFor(questionnaire: JSONObject): JSONObject {
        val url = questionnaire.optString("url", "")
        if (!url.endsWith("/chronic-migraine-followup")) return JSONObject()
        return try {
            readAssetJson("demo-data/migraine-autofill-values.json")
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun initialValueForItem(item: JSONObject, prefill: JSONObject): Any? {
        val linkId = item.optString("linkId")
        if (prefill.has(linkId)) return prefill.opt(linkId)

        val options = item.optJSONArray("answerOption")
        if (options != null) {
            val selected = jsonObjects(options)
                .filter { it.optBoolean("initialSelected") }
                .map { answerOptionKey(it) }
            if (selected.isNotEmpty()) return if (item.optBoolean("repeats")) selected else selected.first()
        }

        val initial = item.optJSONArray("initial")
        if (initial != null && initial.length() > 0) {
            return questionnaireValueFromObject(initial.optJSONObject(0))
        }

        return null
    }

    private fun normalizeInitialValue(item: JSONObject, value: Any?): Any? {
        if (value == null || value == JSONObject.NULL) return null
        if (!item.optBoolean("repeats")) return value

        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it)?.takeUnless { item -> item == JSONObject.NULL }?.toString() }
            is Collection<*> -> value.mapNotNull { it?.toString() }
            else -> value.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    // buildErrorPayload / buildSuccessPayload / presentationFor were removed in
    // Stage B — they assembled OID4VP vp_token JSON. The Stage C
    // DeviceResponse builder takes their place: it places the SMART response
    // JSON (artifacts + per-item statuses) as the elementValue of the lone
    // IssuerSignedItem in an mdoc Document.

    @Suppress("unused") // wired in Stage C
    private fun dataForItem(item: RequestItem, answerSnapshot: Map<String, Any>): JSONObject? {
        return when (item.kind) {
            RequestKind.Coverage -> readAssetJson("demo-data/carin-coverage.json")
            RequestKind.Plan -> readAssetJson("demo-data/sbc-insurance-plan.json")
            RequestKind.Clinical -> readAssetJson("demo-data/clinical-history-bundle.json")
            RequestKind.Questionnaire -> buildQuestionnaireResponse(item, answerSnapshot)
            RequestKind.Unknown -> null
        }
    }

    private fun buildQuestionnaireResponse(requestItem: RequestItem, answerSnapshot: Map<String, Any>): JSONObject {
        val questionnaire = requestItem.meta.optJSONObject("questionnaire")
        val values = collectQuestionnaireValues(requestItem.id, answerSnapshot)
        val response = JSONObject()
            .put("resourceType", "QuestionnaireResponse")
            .put("status", "completed")
            .put("authored", Instant.now().toString())

        if (questionnaire != null) {
            var questionnaireRef = questionnaire.optString("url", "")
            if (questionnaireRef.isBlank() && questionnaire.optString("id").isNotBlank()) {
                questionnaireRef = "Questionnaire/${questionnaire.optString("id")}"
            }
            if (questionnaireRef.isNotBlank()) response.put("questionnaire", questionnaireRef)
            response.put("item", buildQuestionnaireItems(questionnaire.optJSONArray("item"), values))
        }

        return response
    }

    private fun collectQuestionnaireValues(credentialId: String, answerSnapshot: Map<String, Any>): JSONObject {
        val values = JSONObject()
        val prefix = "$credentialId::"
        answerSnapshot.forEach { (key, value) ->
            if (key.startsWith(prefix)) {
                values.put(key.removePrefix(prefix), jsonValue(value))
            }
        }
        return values
    }

    private fun jsonValue(value: Any): Any {
        return when (value) {
            is Collection<*> -> JSONArray(value)
            else -> value
        }
    }

    private fun buildQuestionnaireItems(sourceItems: JSONArray?, values: JSONObject): JSONArray {
        val out = JSONArray()

        jsonObjects(sourceItems).forEach { source ->
            if (!isEnabled(source, values)) return@forEach

            val type = source.optString("type")
            val target = JSONObject().put("linkId", source.optString("linkId"))
            if (source.optString("text").isNotBlank()) target.put("text", source.optString("text"))

            when (type) {
                "group" -> {
                    val children = buildQuestionnaireItems(source.optJSONArray("item"), values)
                    if (children.length() > 0) {
                        target.put("item", children)
                        out.put(target)
                    }
                }
                "display" -> out.put(target)
                else -> {
                    val answers = answersFor(source, values.opt(source.optString("linkId")))
                    if (answers.length() > 0) {
                        target.put("answer", answers)
                        out.put(target)
                    }
                }
            }
        }

        return out
    }

    private fun isEnabled(item: JSONObject, values: JSONObject): Boolean {
        val enableWhen = item.optJSONArray("enableWhen")
        if (enableWhen == null || enableWhen.length() == 0) return true

        val any = item.optString("enableBehavior") == "any"
        var aggregate = !any

        jsonObjects(enableWhen).forEach { condition ->
            val result = compare(values.opt(condition.optString("question")), condition)
            aggregate = if (any) aggregate || result else aggregate && result
        }

        return aggregate
    }

    private fun compare(actual: Any?, condition: JSONObject): Boolean {
        val operator = condition.optString("operator")
        val expected = when {
            condition.has("answerInteger") -> condition.opt("answerInteger")
            condition.has("answerBoolean") -> condition.opt("answerBoolean")
            condition.has("answerString") -> condition.opt("answerString")
            condition.has("answerCoding") -> condition.optJSONObject("answerCoding")?.optString("code")
            else -> null
        }

        if (operator == "exists") return (actual != null && actual != JSONObject.NULL) == (expected == true)
        if (actual == null || actual == JSONObject.NULL || expected == null || expected == JSONObject.NULL) return false
        if (operator == "=") return valuesContain(actual, expected)
        if (operator == "!=") return !valuesContain(actual, expected)

        return runCatching {
            val left = actual.toString().toDouble()
            val right = expected.toString().toDouble()
            when (operator) {
                ">" -> left > right
                "<" -> left < right
                ">=" -> left >= right
                "<=" -> left <= right
                else -> false
            }
        }.getOrDefault(false)
    }

    private fun answersFor(item: JSONObject, value: Any?): JSONArray {
        val answers = JSONArray()
        if (value == null || value == JSONObject.NULL || value.toString().isBlank()) return answers

        if (value is JSONArray) {
            for (index in 0 until value.length()) {
                val answer = answerForScalar(item, value.opt(index))
                if (answer.length() > 0) answers.put(answer)
            }
            return answers
        }

        answers.put(answerForScalar(item, value))
        return answers
    }

    private fun answerForScalar(item: JSONObject, value: Any?): JSONObject {
        val answer = JSONObject()
        if (value == null || value == JSONObject.NULL) return answer

        when (item.optString("type")) {
            "integer" -> answer.put("valueInteger", value.toString().toInt())
            "decimal" -> answer.put("valueDecimal", value.toString().toDouble())
            "boolean" -> answer.put("valueBoolean", value.toString().toBoolean())
            "date" -> answer.put("valueDate", value.toString())
            "choice", "open-choice" -> {
                val option = findAnswerOption(item.optJSONArray("answerOption"), value.toString())
                val coding = option?.optJSONObject("valueCoding")
                if (coding != null) answer.put("valueCoding", coding) else answer.put("valueString", value.toString())
            }
            else -> answer.put("valueString", value.toString())
        }

        return answer
    }

    private fun findAnswerOption(options: JSONArray?, key: String): JSONObject? {
        return jsonObjects(options).firstOrNull { option ->
            val coding = option.optJSONObject("valueCoding")
            val optionKey = coding?.optString("code", coding.optString("display")) ?: option.optString("valueString")
            key == optionKey
        }
    }

    private fun valuesContain(actual: Any, expected: Any): Boolean {
        if (actual is JSONArray) {
            for (index in 0 until actual.length()) {
                if (actual.opt(index).toString() == expected.toString()) return true
            }
            return false
        }
        return actual.toString() == expected.toString()
    }

    private fun answerOptionKey(option: JSONObject): String {
        val coding = option.optJSONObject("valueCoding")
        if (coding != null) return coding.optString("code", coding.optString("display", coding.toString()))
        if (option.has("valueString")) return option.optString("valueString")
        if (option.has("valueInteger")) return option.optInt("valueInteger").toString()
        if (option.has("valueDate")) return option.optString("valueDate")
        if (option.has("valueTime")) return option.optString("valueTime")
        return option.toString()
    }

    private fun answerOptionLabel(option: JSONObject): String {
        val coding = option.optJSONObject("valueCoding")
        if (coding != null) return coding.optString("display", coding.optString("code", coding.toString()))
        if (option.has("valueString")) return option.optString("valueString")
        if (option.has("valueInteger")) return option.optInt("valueInteger").toString()
        if (option.has("valueDate")) return option.optString("valueDate")
        if (option.has("valueTime")) return option.optString("valueTime")
        return option.toString()
    }

    private fun questionnaireValueFromObject(value: JSONObject?): Any? {
        if (value == null) return null
        if (value.has("valueBoolean")) return value.optBoolean("valueBoolean")
        if (value.has("valueInteger")) return value.optInt("valueInteger")
        if (value.has("valueDecimal")) return value.optDouble("valueDecimal")
        if (value.has("valueDate")) return value.optString("valueDate")
        if (value.has("valueDateTime")) return value.optString("valueDateTime")
        if (value.has("valueTime")) return value.optString("valueTime")
        if (value.has("valueString")) return value.optString("valueString")
        val coding = value.optJSONObject("valueCoding")
        if (coding != null) return coding.optString("code", coding.optString("display", coding.toString()))
        return null
    }

    // encryptResponse / postResponse / getJson / fetchText (×2) and the
    // requireString / isBareOrigin / isLocalDemoHost / audienceIsValid URL
    // helpers were removed in Stage B. The Stage C path replaces them with:
    //   - HPKE seal (baseline DHKEM-P256 + HKDF-SHA256 + AES-128-GCM,
    //     info = SessionTranscript, AAD = empty) producing the response bytes.
    //   - Credential Manager `setGetCredentialResponse` for delivery (no
    //     network call).
    //   - browser-supplied callingAppInfo.origin as a presentation signal (no
    //     well-known fetch, no JWS signature check).

    private fun readAssetJson(path: String): JSONObject {
        return assets.open(path).use { JSONObject(readStream(it)) }
    }

    private fun readStream(input: InputStream?): String {
        if (input == null) return ""
        val builder = StringBuilder()
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                builder.append(line)
                line = reader.readLine()
            }
        }
        return builder.toString()
    }
}

internal sealed interface RegistrationState {
    data object Idle : RegistrationState
    data object Pending : RegistrationState
    data class Registered(
        val matcherBytes: Int,
        val credentialsBytes: Int,
        val mode: String,
        val registeredTypes: String,
    ) : RegistrationState
    data class Failed(val message: String) : RegistrationState
}

internal sealed interface ImportState {
    data object Idle : ImportState
    data object Pending : ImportState
    data class Success(val message: String) : ImportState
    data class Failed(val message: String) : ImportState
}

// HomeScreen is the MainActivity home: registration status, demo-data
// summary, and a build stamp. The DC API HandlerActivity reuses DemoApp +
// the Consent screens below.
@Composable
private fun HomeScreen(
    registration: RegistrationState,
    importedRecords: ImportedHealthRecords?,
    importedSummary: ImportedHealthRecordsSummary?,
    importState: ImportState,
    onRegister: () -> Unit,
    onImportRecords: () -> Unit,
    onClearImportedRecords: () -> Unit,
    referencePatient: String,
    onReferencePatientChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        containerColor = AppColors.Page,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { SmartTopBar(title = "SMART Health Check-in Wallet", subtitle = "Reference wallet") },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "This wallet answers SMART Health Check-in requests that websites and apps send through the Digital Credentials API (org-iso-mdoc).",
                style = MaterialTheme.typography.bodyLarge,
                color = AppColors.Muted,
            )

            ElevatedPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmartHeading("Wallet status", Modifier.weight(1f))
                    when (registration) {
                        RegistrationState.Idle, RegistrationState.Pending -> StatusPill("Registering", StatusTone.Info)
                        is RegistrationState.Registered -> StatusPill("Ready", StatusTone.Ok, Modifier.testTag("wallet-ready"))
                        is RegistrationState.Failed -> StatusPill("Not registered", StatusTone.Bad)
                    }
                }
                Spacer(Modifier.height(8.dp))
                val statusLine = when (registration) {
                    RegistrationState.Idle -> "Preparing Credential Manager registration."
                    RegistrationState.Pending -> "Preparing Credential Manager registration..."
                    is RegistrationState.Registered ->
                        "Ready for check-in requests (${registration.registeredTypes})."
                    is RegistrationState.Failed -> "Credential Manager registration failed: ${registration.message}"
                }
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.Muted,
                )
                if (registration is RegistrationState.Failed) {
                    Spacer(Modifier.height(8.dp))
                    SmartSecondaryButton(onClick = onRegister, modifier = Modifier.testTag("retry-registration")) {
                        Text("Retry")
                    }
                }
            }

            ElevatedPanel {
                SmartHeading("Reference patient")
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "The synthetic patient this wallet answers as when no records are imported. The same patients as the SMART Testing Wallet on the web.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.Muted,
                )
                if (importedSummary != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Imported records are active, so check-in responses use them instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Subtle,
                    )
                }
                Spacer(Modifier.height(8.dp))
                ReferencePatients.labels.forEach { (key, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(SmartRadius.md))
                            .selectable(
                                selected = referencePatient == key,
                                onClick = { onReferencePatientChange(key) },
                                role = Role.RadioButton,
                            )
                            .testTag("patient-$key"),
                    ) {
                        RadioButton(selected = referencePatient == key, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = AppColors.Ink)
                    }
                }
            }

            ElevatedPanel {
                SmartHeading("Imported records")
                Spacer(Modifier.height(8.dp))
                if (importedSummary == null) {
                    Text(
                        text = "No imported records. Check-in responses use the reference patient above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.Muted,
                    )
                } else {
                    Text(
                        text = "Active for wallet responses: ${importedSummary.providerCount} provider${if (importedSummary.providerCount == 1) "" else "s"} · ${importedSummary.totalResources} FHIR resources",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.Ink,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = importedSummary.patientSummary(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.Muted,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = importedSummary.resourceSummary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Muted,
                    )
                }
                when (importState) {
                    ImportState.Idle -> Unit
                    ImportState.Pending -> {
                        Spacer(Modifier.height(10.dp))
                        Text("Importing…", style = MaterialTheme.typography.bodySmall, color = AppColors.Muted)
                    }
                    is ImportState.Success -> {
                        Spacer(Modifier.height(10.dp))
                        Text(importState.message, style = MaterialTheme.typography.bodySmall, color = AppColors.Success)
                    }
                    is ImportState.Failed -> {
                        Spacer(Modifier.height(10.dp))
                        Text("Import failed: ${importState.message}", style = MaterialTheme.typography.bodySmall, color = AppColors.Error)
                    }
                }
                Spacer(Modifier.height(16.dp))
                SmartPrimaryButton(
                    onClick = onImportRecords,
                    enabled = importState !is ImportState.Pending,
                    modifier = Modifier.fillMaxWidth().testTag("import-records"),
                ) {
                    Text(if (importedSummary == null) "Load Health Skillz export" else "Replace imported records")
                }
                if (importedSummary != null) {
                    Spacer(Modifier.height(8.dp))
                    SmartSecondaryButton(
                        onClick = onClearImportedRecords,
                        enabled = importState !is ImportState.Pending,
                        modifier = Modifier.fillMaxWidth().testTag("clear-imported-records"),
                    ) {
                        Text("Use the reference patient")
                    }
                }
            }

            if (importedRecords != null) {
                ImportedRecordsBrowser(importedRecords)
            }

            SmartSecondaryButton(onClick = onClose, modifier = Modifier.fillMaxWidth().testTag("close")) {
                Text("Close")
            }
        }
    }
}

@Composable
private fun ImportedRecordsBrowser(records: ImportedHealthRecords) {
    val expandedProviders = remember(records.importedAt) { mutableStateMapOf<String, Boolean>() }
    val expandedGroups = remember(records.importedAt) { mutableStateMapOf<String, Boolean>() }
    val showAllGroups = remember(records.importedAt) { mutableStateMapOf<String, Boolean>() }
    val expandedJson = remember(records.importedAt) { mutableStateMapOf<String, Boolean>() }
    ElevatedPanel {
        Text(
            text = "Browse wallet data",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Imported records currently used to match check-in requests.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.Muted,
        )
        Spacer(Modifier.height(12.dp))
        records.providers.forEachIndexed { providerIndex, provider ->
            val providerKey = "provider-$providerIndex"
            val expanded = expandedProviders[providerKey] ?: (records.providers.size == 1)
            ProviderRecordsCard(
                providerIndex = providerIndex,
                provider = provider,
                expanded = expanded,
                expandedGroups = expandedGroups,
                showAllGroups = showAllGroups,
                expandedJson = expandedJson,
                onExpandedChange = { expandedProviders[providerKey] = it },
            )
            if (providerIndex < records.providers.lastIndex) Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ProviderRecordsCard(
    providerIndex: Int,
    provider: ImportedProviderRecords,
    expanded: Boolean,
    expandedGroups: SnapshotStateMap<String, Boolean>,
    showAllGroups: SnapshotStateMap<String, Boolean>,
    expandedJson: SnapshotStateMap<String, Boolean>,
    onExpandedChange: (Boolean) -> Unit,
) {
    val totalResources = provider.fhir.values.sumOf { it.size }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(SmartRadius.lg),
        colors = CardDefaults.cardColors(containerColor = AppColors.PanelAlt),
        border = BorderStroke(1.dp, AppColors.Line),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = provider.provider,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.Ink,
                    )
                    Text(
                        text = listOfNotNull(
                            provider.patientDisplayName,
                            provider.patientBirthDate?.let { "DOB $it" },
                            provider.fetchedAt?.let { "Fetched $it" },
                        ).joinToString(" · ").ifBlank { "Imported patient records" },
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Muted,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$totalResources ${recordNoun(totalResources)} · ${providerResourceSummary(provider)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Subtle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SmartTextButton(onClick = { onExpandedChange(!expanded) }) {
                    Text(if (expanded) "Hide" else "Browse")
                }
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                provider.fhir.entries
                    .filter { it.value.isNotEmpty() }
                    .sortedWith(compareByDescending<Map.Entry<String, List<JSONObject>>> { it.value.size }.thenBy { it.key })
                    .forEach { (resourceType, resources) ->
                        ProviderResourceTypeCard(
                            providerIndex = providerIndex,
                            provider = provider,
                            resourceType = resourceType,
                            resources = resources,
                            expandedGroups = expandedGroups,
                            showAllGroups = showAllGroups,
                            expandedJson = expandedJson,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
            }
        }
    }
}

@Composable
private fun ProviderResourceTypeCard(
    providerIndex: Int,
    provider: ImportedProviderRecords,
    resourceType: String,
    resources: List<JSONObject>,
    expandedGroups: SnapshotStateMap<String, Boolean>,
    showAllGroups: SnapshotStateMap<String, Boolean>,
    expandedJson: SnapshotStateMap<String, Boolean>,
) {
    val groupKey = "provider-$providerIndex:$resourceType"
    val expanded = expandedGroups[groupKey] == true
    val showAll = showAllGroups[groupKey] == true
    val visibleResources = if (showAll) resources else resources.take(BROWSER_GROUP_PREVIEW_LIMIT)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(SmartRadius.lg),
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
        border = BorderStroke(1.dp, AppColors.Line),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = resourceTypeLabel(resourceType),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.Ink,
                    )
                    Text(
                        text = "${resources.size} ${recordNoun(resources.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Muted,
                    )
                    Text(
                        text = resourcePreview(provider, resourceType, resources),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Subtle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SmartTextButton(onClick = { expandedGroups[groupKey] = !expanded }) {
                    Text(if (expanded) "Hide" else "Open")
                }
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                visibleResources.forEachIndexed { index, resource ->
                    val originalIndex = resources.indexOf(resource)
                    val candidate = browserCandidate(providerIndex, provider, resourceType, resource, originalIndex)
                    BrowserResourceRow(
                        candidate = candidate,
                        jsonExpanded = expandedJson[candidate.id] == true,
                        onJsonExpandedChange = { expandedJson[candidate.id] = it },
                    )
                    if (index < visibleResources.lastIndex) Spacer(Modifier.height(8.dp))
                }
                if (resources.size > BROWSER_GROUP_PREVIEW_LIMIT) {
                    Spacer(Modifier.height(6.dp))
                    SmartTextButton(onClick = { showAllGroups[groupKey] = !showAll }) {
                        Text(if (showAll) "Show fewer" else "Show all ${resources.size}")
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserResourceRow(
    candidate: WalletCandidate,
    jsonExpanded: Boolean,
    onJsonExpandedChange: (Boolean) -> Unit,
) {
    val candidateJson = candidate.value
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SmartRadius.md))
            .background(AppColors.PanelAlt)
            .border(BorderStroke(1.dp, AppColors.Line), RoundedCornerShape(SmartRadius.md))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = candidate.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = candidateSubtitle(candidate),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                resourceDecisionDetails(candidate)?.let { details ->
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Subtle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (candidateJson != null) {
                SmartTextButton(onClick = { onJsonExpandedChange(!jsonExpanded) }) {
                    Text(if (jsonExpanded) "Hide JSON" else "JSON")
                }
            }
        }
        if (jsonExpanded && candidateJson != null) {
            Spacer(Modifier.height(8.dp))
            CodeBlock(candidateJson.toString(2).replace("\\/", "/"))
        }
    }
}

private const val BROWSER_GROUP_PREVIEW_LIMIT = 20

private fun providerResourceSummary(provider: ImportedProviderRecords): String {
    return provider.fhir.entries
        .filter { it.value.isNotEmpty() }
        .sortedWith(compareByDescending<Map.Entry<String, List<JSONObject>>> { it.value.size }.thenBy { it.key })
        .take(5)
        .joinToString { "${resourceTypeLabel(it.key)} ${it.value.size}" }
}

private fun resourcePreview(
    provider: ImportedProviderRecords,
    resourceType: String,
    resources: List<JSONObject>,
): String {
    return resources
        .take(3)
        .mapIndexed { index, resource -> browserResourceLabel(provider, resourceType, resource, index) }
        .joinToString(prefix = "Examples: ")
}

private fun browserCandidate(
    providerIndex: Int,
    provider: ImportedProviderRecords,
    resourceType: String,
    resource: JSONObject,
    index: Int,
): WalletCandidate {
    val actualResourceType = resource.optString("resourceType").ifBlank { resourceType }
    return WalletCandidate(
        id = "browse:p$providerIndex:$actualResourceType:$index:${resource.optString("id")}",
        label = browserResourceLabel(provider, actualResourceType, resource, index),
        subtitle = browserResourceSubtitle(actualResourceType, resource),
        resourceType = actualResourceType,
        sourceName = provider.provider,
        selectedByDefault = false,
        value = JSONObject(resource.toString()),
    )
}

private fun browserResourceLabel(
    provider: ImportedProviderRecords,
    resourceType: String,
    resource: JSONObject,
    index: Int,
): String {
    val typeSpecific = when (resourceType) {
        "Coverage" -> listOf(
            coverageClassSummary(resource, "plan"),
            coverageClassSummary(resource, "group"),
            codeTextForUi(resource.optJSONObject("type")),
            referenceDisplay(resource.optJSONArray("payor")),
        ).firstOrNull { !it.isNullOrBlank() }
        "MedicationRequest",
        "MedicationStatement",
        "MedicationDispense" -> medicationLabelForUi(provider, resource)
        "Immunization" -> codeTextForUi(resource.optJSONObject("vaccineCode"))
        "DocumentReference" -> codeTextForUi(resource.optJSONObject("type"))
        "Encounter" -> firstCodeTextForUi(resource.optJSONArray("type")) ?: classTextForUi(resource.optJSONObject("class"))
        "CarePlan",
        "CareTeam" -> firstCodeTextForUi(resource.optJSONArray("category"))
        "Goal" -> codeTextForUi(resource.optJSONObject("description"))
        "Specimen" -> codeTextForUi(resource.optJSONObject("type"))
        "Location",
        "Organization" -> resource.optString("name").ifBlank { null }
        else -> codeTextForUi(resource.optJSONObject("code"))
    }
    return listOf(
        firstHumanNameForUi(resource.optJSONArray("name")),
        typeSpecific,
        resource.optString("title").ifBlank { null },
        resource.optString("description").ifBlank { null },
        resource.optString("id").ifBlank { null },
    ).firstOrNull { !it.isNullOrBlank() } ?: "$resourceType ${index + 1}"
}

private fun browserResourceSubtitle(resourceType: String, resource: JSONObject): String {
    val parts = mutableListOf(resourceType)
    resource.optString("status").takeIf(String::isNotBlank)?.let(parts::add)
    codeTextForUi(resource.optJSONObject("clinicalStatus"))?.let(parts::add)
    resource.optString("recordedDate").takeIf(String::isNotBlank)?.let(parts::add)
    resource.optString("effectiveDateTime").takeIf(String::isNotBlank)?.let(parts::add)
    resource.optString("issued").takeIf(String::isNotBlank)?.let(parts::add)
    resource.optString("authoredOn").takeIf(String::isNotBlank)?.let(parts::add)
    resource.optString("occurrenceDateTime").takeIf(String::isNotBlank)?.let(parts::add)
    resource.optString("performedDateTime").takeIf(String::isNotBlank)?.let(parts::add)
    resource.optString("date").takeIf(String::isNotBlank)?.let(parts::add)
    return parts.distinct().joinToString(" · ")
}

private fun medicationLabelForUi(provider: ImportedProviderRecords, resource: JSONObject): String? {
    codeTextForUi(resource.optJSONObject("medicationCodeableConcept"))?.let { return it }
    val medicationReference = resource.optJSONObject("medicationReference")
    medicationReference?.optString("display")?.takeIf(String::isNotBlank)?.let { return it }
    val medication = referencedResourceForUi(provider, medicationReference)
    return medication?.let { codeTextForUi(it.optJSONObject("code")) }
}

private fun referencedResourceForUi(provider: ImportedProviderRecords, reference: JSONObject?): JSONObject? {
    val ref = reference?.optString("reference")?.takeIf(String::isNotBlank) ?: return null
    val parts = ref.split('/')
    if (parts.size < 2) return null
    val resourceType = parts[parts.size - 2]
    val id = parts.last()
    return provider.fhir[resourceType].orEmpty().firstOrNull { it.optString("id") == id }
}

private fun firstHumanNameForUi(names: JSONArray?): String? {
    val first = names?.optJSONObject(0) ?: return null
    first.optString("text").takeIf(String::isNotBlank)?.let { return it }
    val given = stringValuesForUi(first.opt("given")).joinToString(" ")
    val family = first.optString("family")
    return "$given $family".trim().ifBlank { null }
}

private fun stringValuesForUi(value: Any?): List<String> {
    return when (value) {
        is String -> listOf(value).filter { it.isNotBlank() }
        is JSONArray -> {
            val out = mutableListOf<String>()
            for (index in 0 until value.length()) out += stringValuesForUi(value.opt(index))
            out
        }
        else -> emptyList()
    }
}

private fun firstCodeTextForUi(codes: JSONArray?): String? {
    return jsonObjects(codes).firstNotNullOfOrNull(::codeTextForUi)
}

private fun codeTextForUi(code: JSONObject?): String? {
    if (code == null) return null
    code.optString("text").takeIf(String::isNotBlank)?.let { return it }
    return jsonObjects(code.optJSONArray("coding")).firstNotNullOfOrNull { coding ->
        coding.optString("display").takeIf(String::isNotBlank)
            ?: coding.optString("code").takeIf(String::isNotBlank)
    }
}

private fun classTextForUi(code: JSONObject?): String? {
    if (code == null) return null
    return code.optString("display").takeIf(String::isNotBlank)
        ?: code.optString("code").takeIf(String::isNotBlank)
}

@Composable
fun DemoApp(
    state: ScreenState,
    caller: CallerIdentity? = null,
    selectedItems: SnapshotStateMap<String, Boolean>,
    selectedCandidates: SnapshotStateMap<String, Set<String>>,
    questionnaireAnswers: SnapshotStateMap<String, Any>,
    onItemSelected: (String, Boolean) -> Unit,
    onCandidateSelected: (String, String, Boolean) -> Unit,
    onAnswerChanged: (String, Any?) -> Unit,
    onShare: () -> Unit,
    onDecline: () -> Unit,
    onClose: () -> Unit,
) {
    Scaffold(
        containerColor = AppColors.Page,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { SmartTopBar(title = "SMART Health Check-in Wallet", subtitle = "Reference wallet") },
        bottomBar = {
            if (state is ScreenState.Consent) {
                ConsentActions(onShare = onShare, onDecline = onDecline)
            }
        },
    ) { padding ->
        when (state) {
            is ScreenState.Empty -> EmptyScreen(padding, onClose)
            is ScreenState.Loading -> LoadingScreen(state, padding)
            is ScreenState.Submitting -> SubmittingScreen(state, padding)
            is ScreenState.Error -> ErrorScreen(state, padding, onClose)
            is ScreenState.Complete -> CompleteScreen(padding, caller)
            is ScreenState.Consent -> ConsentScreen(
                request = state.request,
                caller = caller,
                resolutions = state.resolutions,
                selectedItems = selectedItems,
                selectedCandidates = selectedCandidates,
                questionnaireAnswers = questionnaireAnswers,
                onItemSelected = onItemSelected,
                onCandidateSelected = onCandidateSelected,
                onAnswerChanged = onAnswerChanged,
                padding = padding,
            )
        }
    }
}

@Composable
private fun EmptyScreen(padding: PaddingValues, onClose: () -> Unit) {
    CenterPanel(padding) {
        Text(
            text = "Open from a check-in link",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "This sample app appears in the SMART Health Check-in picker and opens verified requests from the demo verifier.",
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.Muted,
        )
        Spacer(Modifier.height(24.dp))
        SmartPrimaryButton(onClick = onClose, modifier = Modifier.fillMaxWidth().testTag("close")) {
            Text("Close")
        }
    }
}

@Composable
private fun LoadingScreen(state: ScreenState.Loading, padding: PaddingValues) {
    CenterPanel(padding) {
        CircularProgressIndicator(color = AppColors.Primary)
        Spacer(Modifier.height(20.dp))
        Text(
            text = state.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.Muted,
        )
    }
}

@Composable
private fun SubmittingScreen(state: ScreenState.Submitting, padding: PaddingValues) {
    CenterPanel(padding) {
        CircularProgressIndicator(color = AppColors.Primary)
        Spacer(Modifier.height(20.dp))
        Text(
            text = state.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.Muted,
        )
    }
}

@Composable
private fun ErrorScreen(state: ScreenState.Error, padding: PaddingValues, onClose: () -> Unit) {
    CenterPanel(padding) {
        StatusDot(AppColors.Error, AppColors.ErrorSoft)
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Could not complete the request",
            modifier = Modifier.testTag("error-title"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.Muted,
        )
        Spacer(Modifier.height(24.dp))
        SmartPrimaryButton(onClick = onClose, modifier = Modifier.fillMaxWidth().testTag("close")) {
            Text("Close")
        }
    }
}

@Composable
private fun CompleteScreen(padding: PaddingValues, caller: CallerIdentity?) {
    CenterPanel(padding) {
        StatusDot(AppColors.Success, AppColors.SuccessSoft)
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Data shared",
            modifier = Modifier.testTag("complete-title"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The data you selected was encrypted and sent to ${recipientPhrase(caller)}.",
            modifier = Modifier.testTag("complete-message"),
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.Muted,
        )
    }
}

@Composable
private fun ConsentScreen(
    request: VerifiedRequest,
    caller: CallerIdentity?,
    resolutions: List<RequestItemResolution>,
    selectedItems: SnapshotStateMap<String, Boolean>,
    selectedCandidates: SnapshotStateMap<String, Set<String>>,
    questionnaireAnswers: SnapshotStateMap<String, Any>,
    onItemSelected: (String, Boolean) -> Unit,
    onCandidateSelected: (String, String, Boolean) -> Unit,
    onAnswerChanged: (String, Any?) -> Unit,
    padding: PaddingValues,
) {
    val resolutionsByItem = remember(resolutions) { resolutions.associateBy { it.itemId } }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeaderCard(request, caller)

        Text(
            text = "Choose what to share",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Ink,
        )
        Text(
            text = "Review what this wallet found before deciding what to share.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.Muted,
        )

        request.items.forEach { item ->
            val resolution = resolutionsByItem[item.id] ?: RequestItemResolution(
                itemId = item.id,
                availability = WalletItemAvailability.Error,
                candidates = emptyList(),
                matchSummary = "Could not prepare this item.",
                statusIfShared = RequestItemStatusCode.Error,
            )
            val selected = selectedItems[item.id] != false
            DataRequestCard(
                item = item,
                resolution = resolution,
                selected = selected,
                onSelectedChange = { onItemSelected(item.id, it) },
            )

            if (selected && resolution.candidates.size > 1) {
                CandidateSelectionCard(
                    itemId = item.id,
                    resolution = resolution,
                    selectedCandidateIds = selectedCandidates[item.id].orEmpty(),
                    onCandidateSelected = onCandidateSelected,
                )
            }

            if (selected && item.kind == RequestKind.Questionnaire) {
                val questionnaire = item.meta.optJSONObject("questionnaire")
                if (questionnaire != null) {
                    QuestionnaireCard(
                        credentialId = item.id,
                        questionnaire = questionnaire,
                        answers = questionnaireAnswers,
                        onAnswerChanged = onAnswerChanged,
                    )
                } else {
                    NoticeCard("This questionnaire was referenced by URL. Inline rendering requires the request to include the Questionnaire resource.")
                }
            }
        }

        TechnicalSummary(request, caller as? CallerIdentity.App)
    }
}

/**
 * Who is asking, in words that claim only what the wallet knows: a website (its
 * origin is shown below the heading) or an app. Never a practice, clinic, or
 * provider: nothing in the request proves that. An app caller isn't named at all;
 * see [CallerIdentity].
 */
fun requesterPhrase(caller: CallerIdentity?): String = when (caller) {
    is CallerIdentity.App -> "An app"
    else -> "A website"
}

/** The recipient on the success screen: the website's origin, or "the app". */
fun recipientPhrase(caller: CallerIdentity?): String = when (caller) {
    is CallerIdentity.Website -> caller.origin
    is CallerIdentity.App -> "the app"
    null -> "the website that asked"
}

@Composable
private fun HeaderCard(request: VerifiedRequest, caller: CallerIdentity?) {
    val app = caller as? CallerIdentity.App
    ElevatedPanel {
        Text(
            text = "${requesterPhrase(caller ?: CallerIdentity.Website(request.verifierOrigin))} is asking for your health information",
            modifier = Modifier.semantics { heading() }.testTag("consent-heading"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Ink,
        )
        Spacer(Modifier.height(8.dp))
        val readerAuthText = when {
            request.readerAuth.present && request.readerAuth.signatureValid ->
                "The request is signed (readerAuth); review the requested data before sharing."
            request.readerAuth.present ->
                "The request carries a readerAuth signature that did not verify. Review carefully before sharing."
            app == null ->
                "Check that you recognize the website below before sharing."
            else ->
                "The wallet can't tell which organization this app belongs to."
        }
        Text(
            text = readerAuthText,
            style = MaterialTheme.typography.bodyLarge,
            color = AppColors.Muted,
        )

        Spacer(Modifier.height(16.dp))

        if (app == null) {
            VerifierStrip(request.verifierOrigin, request.readerAuth)
        } else {
            AppCallerStrip(request.readerAuth)
        }
    }
}

/**
 * An app caller. The wallet doesn't name it: its package name and label are the
 * app's own choice, so they appear only under Technical details.
 */
@Composable
private fun AppCallerStrip(readerAuth: ReaderAuthVerification) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SmartRadius.lg))
            .background(AppColors.PanelAlt)
            .border(BorderStroke(1.dp, AppColors.Line), RoundedCornerShape(SmartRadius.lg))
            .padding(12.dp),
    ) {
        Text(
            text = "App",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Muted,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "An app on this phone, not a website",
            modifier = Modifier.testTag("caller-headline"),
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.Ink,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Share only if you opened this from an app you trust.",
            modifier = Modifier.testTag("caller-caution"),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Amber,
        )
        if (readerAuth.present && readerAuth.certificateSubject != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Reader certificate: ${readerAuth.certificateSubject}",
                style = MaterialTheme.typography.bodySmall,
                color = if (readerAuth.signatureValid) AppColors.Success else AppColors.Amber,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VerifierStrip(verifierOrigin: String, readerAuth: ReaderAuthVerification) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SmartRadius.lg))
            .background(AppColors.PanelAlt)
            .border(BorderStroke(1.dp, AppColors.Line), RoundedCornerShape(SmartRadius.lg))
            .padding(12.dp),
    ) {
        Text(
            text = "Website",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Muted,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = verifierOrigin,
            modifier = Modifier.testTag("caller-origin"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (readerAuth.present && readerAuth.certificateSubject != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Reader certificate: ${readerAuth.certificateSubject}",
                style = MaterialTheme.typography.bodySmall,
                color = if (readerAuth.signatureValid) AppColors.Success else AppColors.Amber,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DataRequestCard(
    item: RequestItem,
    resolution: RequestItemResolution,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
) {
    ElevatedPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DataGlyph(item.kind)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val formTitle = questionnaireTitleForRequestItem(item)
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Ink,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.Muted,
                )
                if (!formTitle.isNullOrBlank() && formTitle != item.title) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "Form: $formTitle",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Muted,
                    )
                }
            }
            Switch(
                checked = selected,
                onCheckedChange = onSelectedChange,
                modifier = Modifier.testTag("item-switch-${item.id}"),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(resolution.matchSummary, availabilityTone(resolution.availability))
            if (resolution.candidates.size > 1) {
                val selectedLabel = "${resolution.candidates.count { it.selectedByDefault }} selected by default"
                StatusChip(selectedLabel, ChipTone.Neutral)
            }
        }
        val detail = resolution.detail
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.Muted,
            )
        }
    }
}

@Composable
private fun CandidateSelectionCard(
    itemId: String,
    resolution: RequestItemResolution,
    selectedCandidateIds: Set<String>,
    onCandidateSelected: (String, String, Boolean) -> Unit,
) {
    val candidateIds = remember(resolution.candidates) { resolution.candidates.map { it.id } }
    val expandedGroups = remember(itemId, candidateIds) { mutableStateMapOf<String, Boolean>() }
    val showAllInGroups = remember(itemId, candidateIds) { mutableStateMapOf<String, Boolean>() }
    val expandedJson = remember(itemId, candidateIds) { mutableStateMapOf<String, Boolean>() }
    val groups = remember(resolution.candidates) { candidateGroups(resolution.candidates) }
    val groupMode = shouldGroupCandidates(groups, resolution.candidates.size)
    val selectedCount = resolution.candidates.count { it.id in selectedCandidateIds }
    fun setCandidates(candidates: List<WalletCandidate>, selected: Boolean) {
        candidates.forEach { candidate -> onCandidateSelected(itemId, candidate.id, selected) }
    }

    ElevatedPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Matching records",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Ink,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "$selectedCount of ${resolution.candidates.size} selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Muted,
                )
            }
            SmartTextButton(onClick = { setCandidates(resolution.candidates, true) }) {
                Text("All")
            }
            SmartTextButton(onClick = { setCandidates(resolution.candidates, false) }) {
                Text("None")
            }
        }

        Spacer(Modifier.height(8.dp))
        if (groupMode) {
            groups.forEach { group ->
                val expanded = expandedGroups[group.key] == true
                val showAll = showAllInGroups[group.key] == true
                ResourceTypeGroupCard(
                    group = group,
                    selectedCandidateIds = selectedCandidateIds,
                    expanded = expanded,
                    showAll = showAll,
                    expandedJson = expandedJson,
                    onExpandedChange = { expandedGroups[group.key] = it },
                    onShowAllChange = { showAllInGroups[group.key] = it },
                    onCandidatesSelected = ::setCandidates,
                    onCandidateSelected = { candidate, selected ->
                        onCandidateSelected(itemId, candidate.id, selected)
                    },
                )
                Spacer(Modifier.height(10.dp))
            }
        } else {
            resolution.candidates.forEach { candidate ->
                ResourceCandidateRow(
                    candidate = candidate,
                    selected = candidate.id in selectedCandidateIds,
                    jsonExpanded = expandedJson[candidate.id] == true,
                    onSelectedChange = { checked -> onCandidateSelected(itemId, candidate.id, checked) },
                    onJsonExpandedChange = { expandedJson[candidate.id] = it },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ResourceTypeGroupCard(
    group: CandidateGroup,
    selectedCandidateIds: Set<String>,
    expanded: Boolean,
    showAll: Boolean,
    expandedJson: SnapshotStateMap<String, Boolean>,
    onExpandedChange: (Boolean) -> Unit,
    onShowAllChange: (Boolean) -> Unit,
    onCandidatesSelected: (List<WalletCandidate>, Boolean) -> Unit,
    onCandidateSelected: (WalletCandidate, Boolean) -> Unit,
) {
    val selectedCount = group.candidates.count { it.id in selectedCandidateIds }
    val visibleCandidates = if (showAll) group.candidates else group.candidates.take(CANDIDATE_GROUP_PREVIEW_LIMIT)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(SmartRadius.lg),
        colors = CardDefaults.cardColors(containerColor = AppColors.PanelAlt),
        border = BorderStroke(1.dp, AppColors.Line),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = selectedCount == group.candidates.size,
                    onCheckedChange = { checked -> onCandidatesSelected(group.candidates, checked) },
                                    )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = group.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.Ink,
                    )
                    Text(
                        text = "${group.candidates.size} ${recordNoun(group.candidates.size)} · $selectedCount selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Muted,
                    )
                    group.previewLabels.takeIf(String::isNotBlank)?.let { preview ->
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.Subtle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                SmartTextButton(onClick = { onCandidatesSelected(group.candidates, true) }) {
                    Text("All")
                }
                SmartTextButton(onClick = { onCandidatesSelected(group.candidates, false) }) {
                    Text("None")
                }
            }
            SmartTextButton(onClick = { onExpandedChange(!expanded) }) {
                Text(if (expanded) "Hide records" else "Review records")
            }
            if (expanded) {
                visibleCandidates.forEach { candidate ->
                    ResourceCandidateRow(
                        candidate = candidate,
                        selected = candidate.id in selectedCandidateIds,
                        jsonExpanded = expandedJson[candidate.id] == true,
                        onSelectedChange = { selected -> onCandidateSelected(candidate, selected) },
                        onJsonExpandedChange = { expandedJson[candidate.id] = it },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (group.candidates.size > CANDIDATE_GROUP_PREVIEW_LIMIT) {
                    SmartTextButton(onClick = { onShowAllChange(!showAll) }) {
                        Text(if (showAll) "Show fewer" else "Show all ${group.candidates.size}")
                    }
                }
            }
        }
    }
}

@Composable
private fun ResourceCandidateRow(
    candidate: WalletCandidate,
    selected: Boolean,
    jsonExpanded: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onJsonExpandedChange: (Boolean) -> Unit,
) {
    val candidateJson = candidate.value
    val shape = RoundedCornerShape(SmartRadius.lg)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) SmartTheme.colors.brandWash.copy(alpha = 0.35f) else AppColors.Surface)
            .border(BorderStroke(1.dp, if (selected) AppColors.Primary else AppColors.Line), shape)
            .clickable { onSelectedChange(!selected) }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = selected,
                onCheckedChange = onSelectedChange,
                            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = candidate.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = candidateSubtitle(candidate),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                resourceDecisionDetails(candidate)?.let { details ->
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = details,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Subtle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (candidateJson != null) {
                SmartTextButton(onClick = { onJsonExpandedChange(!jsonExpanded) }) {
                    Text(if (jsonExpanded) "Hide JSON" else "JSON")
                }
            }
        }
        if (jsonExpanded && candidateJson != null) {
            Spacer(Modifier.height(8.dp))
            CodeBlock(candidateJson.toString(2).replace("\\/", "/"))
        }
    }
}

private data class CandidateGroup(
    val key: String,
    val label: String,
    val candidates: List<WalletCandidate>,
) {
    val previewLabels: String = candidates
        .map { it.label }
        .distinct()
        .take(3)
        .joinToString(prefix = "Examples: ")
}

private const val CANDIDATE_GROUP_PREVIEW_LIMIT = 25

private fun candidateGroups(candidates: List<WalletCandidate>): List<CandidateGroup> {
    return candidates
        .groupBy { candidateResourceType(it) }
        .map { (resourceType, groupCandidates) ->
            CandidateGroup(
                key = resourceType,
                label = resourceTypeLabel(resourceType),
                candidates = groupCandidates,
            )
        }
        .sortedWith(compareByDescending<CandidateGroup> { it.candidates.size }.thenBy { it.label })
}

private fun shouldGroupCandidates(groups: List<CandidateGroup>, candidateCount: Int): Boolean {
    return candidateCount > 8 || groups.size > 2
}

private fun candidateResourceType(candidate: WalletCandidate): String {
    return candidate.resourceType
        ?: candidate.value?.optString("resourceType")?.takeIf(String::isNotBlank)
        ?: "FHIR resource"
}

private fun resourceTypeLabel(resourceType: String): String {
    return when (resourceType) {
        "AllergyIntolerance" -> "Allergies"
        "CarePlan" -> "Care plans"
        "CareTeam" -> "Care teams"
        "DiagnosticReport" -> "Diagnostic reports"
        "DocumentReference" -> "Documents"
        "MedicationRequest" -> "Medication requests"
        "MedicationStatement" -> "Medication statements"
        "ServiceRequest" -> "Service requests"
        "Coverage" -> "Coverage"
        else -> splitCamelCase(resourceType).replaceFirstChar { it.uppercase() } + "s"
    }
}

private fun splitCamelCase(value: String): String {
    return value.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase()
}

private fun recordNoun(count: Int): String = if (count == 1) "record" else "records"

private fun candidateSubtitle(candidate: WalletCandidate): String {
    val source = candidate.sourceName
    return if (source.isNullOrBlank()) {
        candidate.subtitle
    } else {
        "${candidate.subtitle} · $source"
    }
}

private fun resourceDecisionDetails(candidate: WalletCandidate): String? {
    val resource = candidate.value ?: return null
    val details = when (candidateResourceType(candidate)) {
        "Coverage" -> listOfNotNull(
            referenceDisplay(resource.optJSONArray("payor"))?.let { "Payor $it" },
            resource.optString("subscriberId").takeIf(String::isNotBlank)?.let { "Subscriber $it" },
            coverageClassSummary(resource, "group")?.let { "Group $it" },
            coverageClassSummary(resource, "plan")?.let { "Plan $it" },
            periodSummaryForUi(resource.optJSONObject("period")),
        )
        "Condition" -> listOfNotNull(
            codeTextForUi(resource.optJSONObject("verificationStatus"))?.let { "Verification $it" },
            codeListSummary(resource.optJSONArray("category"))?.let { "Category $it" },
            resource.optString("onsetDateTime").takeIf(String::isNotBlank)?.let { "Onset $it" },
            resource.optString("abatementDateTime").takeIf(String::isNotBlank)?.let { "Abated $it" },
        )
        "AllergyIntolerance" -> listOfNotNull(
            resource.optString("criticality").takeIf(String::isNotBlank)?.let { "Criticality $it" },
            stringListSummary(resource.opt("category"))?.let { "Category $it" },
            reactionSummary(resource),
            resource.optString("onsetDateTime").takeIf(String::isNotBlank)?.let { "Onset $it" },
        )
        "Observation" -> listOfNotNull(valueSummaryForUi(resource), resource.optString("issued").takeIf(String::isNotBlank))
        "DiagnosticReport" -> listOfNotNull(
            resource.optString("effectiveDateTime").takeIf(String::isNotBlank),
            resource.optString("issued").takeIf(String::isNotBlank)?.let { "Issued $it" },
            referenceSummary(resource.optJSONArray("performer"), "Performer"),
            jsonArrayCount(resource.optJSONArray("result"), "result"),
        )
        "DocumentReference" -> listOfNotNull(
            codeTextForUi(resource.optJSONObject("category")) ?: codeListSummary(resource.optJSONArray("category"))?.let { "Category $it" },
            resource.optString("date").takeIf(String::isNotBlank),
            referenceSummary(resource.optJSONArray("author"), "Author"),
            documentContentSummary(resource),
        )
        "MedicationRequest",
        "MedicationStatement",
        "MedicationDispense" -> listOfNotNull(
            resource.optString("authoredOn").takeIf(String::isNotBlank),
            resource.optJSONObject("requester")?.optString("display")?.takeIf(String::isNotBlank)?.let { "Requester $it" },
            quantitySummary(resource.optJSONObject("quantity")),
            dosageSummary(resource.optJSONArray("dosageInstruction")),
        )
        "Medication" -> listOfNotNull(
            codeTextForUi(resource.optJSONObject("form"))?.let { "Form $it" },
            jsonArrayCount(resource.optJSONArray("ingredient"), "ingredient"),
        )
        "Immunization" -> listOfNotNull(
            resource.optString("occurrenceDateTime").takeIf(String::isNotBlank),
            codeTextForUi(resource.optJSONObject("route"))?.let { "Route $it" },
            codeTextForUi(resource.optJSONObject("site"))?.let { "Site $it" },
            booleanSummary(resource, "primarySource", "Primary source"),
        )
        "Encounter" -> listOfNotNull(periodSummaryForUi(resource.optJSONObject("period")))
        "Procedure" -> listOfNotNull(
            resource.optString("performedDateTime").takeIf(String::isNotBlank),
            codeListSummary(resource.optJSONArray("reasonCode"))?.let { "Reason $it" },
            referenceDisplay(resource.optJSONObject("asserter"))?.let { "Asserter $it" },
        )
        "CarePlan" -> listOfNotNull(
            resource.optString("intent").takeIf(String::isNotBlank)?.let { "Intent $it" },
            codeListSummary(resource.optJSONArray("category"))?.let { "Category $it" },
            noteSummary(resource.optJSONArray("note")),
            jsonArrayCount(resource.optJSONArray("activity"), "activity"),
        )
        "CareTeam" -> listOfNotNull(
            codeListSummary(resource.optJSONArray("category"))?.let { "Category $it" },
            jsonArrayCount(resource.optJSONArray("participant"), "participant"),
        )
        "Goal" -> listOfNotNull(
            resource.optString("lifecycleStatus").takeIf(String::isNotBlank),
            resource.optString("startDate").takeIf(String::isNotBlank)?.let { "Started $it" },
            referenceDisplay(resource.optJSONObject("expressedBy"))?.let { "By $it" },
        )
        "ServiceRequest" -> listOfNotNull(
            resource.optString("intent").takeIf(String::isNotBlank)?.let { "Intent $it" },
            codeListSummary(resource.optJSONArray("category"))?.let { "Category $it" },
            periodSummaryForUi(resource.optJSONObject("occurrencePeriod")),
            quantitySummary(resource.optJSONObject("quantityQuantity")),
        )
        "Patient" -> listOfNotNull(
            resource.optString("gender").takeIf(String::isNotBlank),
            resource.optString("birthDate").takeIf(String::isNotBlank)?.let { "DOB $it" },
            jsonArrayCount(resource.optJSONArray("telecom"), "contact"),
        )
        "RelatedPerson" -> listOfNotNull(
            stringListSummary(resource.opt("relationship"))?.let { "Relationship $it" },
            jsonArrayCount(resource.optJSONArray("telecom"), "contact"),
        )
        "Practitioner" -> listOfNotNull(
            resource.optString("gender").takeIf(String::isNotBlank),
            booleanSummary(resource, "active", "Active"),
            jsonArrayCount(resource.optJSONArray("identifier"), "identifier"),
        )
        "Organization" -> listOfNotNull(
            booleanSummary(resource, "active", "Active"),
            addressSummary(resource.optJSONArray("address")),
            jsonArrayCount(resource.optJSONArray("telecom"), "contact"),
        )
        "Location" -> listOfNotNull(
            resource.optString("mode").takeIf(String::isNotBlank)?.let { "Mode $it" },
            addressSummary(resource.optJSONArray("address")),
        )
        "Specimen" -> listOfNotNull(
            resource.optString("receivedTime").takeIf(String::isNotBlank)?.let { "Received $it" },
            specimenCollectionSummary(resource.optJSONObject("collection")),
        )
        "Device" -> listOfNotNull(
            resource.optString("manufacturer").takeIf(String::isNotBlank),
            resource.optString("modelNumber").takeIf(String::isNotBlank)?.let { "Model $it" },
            codeTextForUi(resource.optJSONObject("type")),
        )
        else -> emptyList()
    }
    return details.distinct().joinToString(" · ").ifBlank { null }
}

private fun codeListSummary(codes: JSONArray?, limit: Int = 2): String? {
    val values = jsonObjects(codes).mapNotNull(::codeTextForUi).distinct()
    return values.take(limit).joinToString().ifBlank { null }?.let { summary ->
        if (values.size > limit) "$summary +${values.size - limit}" else summary
    }
}

private fun stringListSummary(value: Any?, limit: Int = 2): String? {
    val values = when (value) {
        is String -> listOf(value).filter { it.isNotBlank() }
        is JSONObject -> listOfNotNull(
            codeTextForUi(value) ?: value.optString("display").takeIf(String::isNotBlank),
        )
        is JSONArray -> {
            val out = mutableListOf<String>()
            for (index in 0 until value.length()) {
                out += stringListSummary(value.opt(index), limit = Int.MAX_VALUE)
                    ?.split(", ")
                    .orEmpty()
            }
            out
        }
        else -> emptyList()
    }.distinct()
    return values.take(limit).joinToString().ifBlank { null }?.let { summary ->
        if (values.size > limit) "$summary +${values.size - limit}" else summary
    }
}

private fun referenceSummary(references: JSONArray?, label: String, limit: Int = 2): String? {
    val values = jsonObjects(references).mapNotNull(::referenceDisplay).distinct()
    return values.take(limit).joinToString().ifBlank { null }?.let { summary ->
        val suffix = if (values.size > limit) " +${values.size - limit}" else ""
        "$label $summary$suffix"
    }
}

private fun reactionSummary(resource: JSONObject): String? {
    val reactions = jsonObjects(resource.optJSONArray("reaction"))
    if (reactions.isEmpty()) return null
    val manifestations = reactions
        .flatMap { reaction -> jsonObjects(reaction.optJSONArray("manifestation")) }
        .mapNotNull(::codeTextForUi)
        .distinct()
    val manifestationSummary = manifestations.take(2).joinToString()
    return when {
        manifestationSummary.isNotBlank() && manifestations.size > 2 ->
            "Reactions $manifestationSummary +${manifestations.size - 2}"
        manifestationSummary.isNotBlank() -> "Reactions $manifestationSummary"
        else -> "${reactions.size} ${if (reactions.size == 1) "reaction" else "reactions"}"
    }
}

private fun documentContentSummary(resource: JSONObject): String? {
    val firstAttachment = jsonObjects(resource.optJSONArray("content"))
        .firstOrNull()
        ?.optJSONObject("attachment")
        ?: return jsonArrayCount(resource.optJSONArray("content"), "content")
    val title = firstAttachment.optString("title").takeIf(String::isNotBlank)
    val contentType = firstAttachment.optString("contentType").takeIf(String::isNotBlank)
    return listOfNotNull(title, contentType).joinToString(" · ").ifBlank { null }
}

private fun quantitySummary(quantity: JSONObject?): String? {
    if (quantity == null) return null
    val value = quantity.opt("value")?.toString()?.takeIf(String::isNotBlank)
    val unit = quantity.optString("unit").ifBlank { quantity.optString("code") }.ifBlank { null }
    return listOfNotNull(value, unit).joinToString(" ").ifBlank { null }
}

private fun dosageSummary(dosage: JSONArray?): String? {
    val items = jsonObjects(dosage)
    val firstText = items.firstNotNullOfOrNull { it.optString("text").takeIf(String::isNotBlank) }
    return firstText ?: jsonArrayCount(dosage, "dosage")
}

private fun noteSummary(notes: JSONArray?): String? {
    val items = jsonObjects(notes)
    val firstText = items.firstNotNullOfOrNull { it.optString("text").takeIf(String::isNotBlank) }
    return firstText ?: jsonArrayCount(notes, "note")
}

private fun addressSummary(addresses: JSONArray?): String? {
    val address = jsonObjects(addresses).firstOrNull() ?: return null
    val line = stringListSummary(address.optJSONArray("line"), limit = 1)
    val city = address.optString("city").takeIf(String::isNotBlank)
    val state = address.optString("state").takeIf(String::isNotBlank)
    return listOfNotNull(line, city, state).joinToString(", ").ifBlank { null }
}

private fun specimenCollectionSummary(collection: JSONObject?): String? {
    if (collection == null) return null
    return listOfNotNull(
        collection.optString("collectedDateTime").takeIf(String::isNotBlank)?.let { "Collected $it" },
        quantitySummary(collection.optJSONObject("quantity"))?.let { "Quantity $it" },
        codeTextForUi(collection.optJSONObject("method"))?.let { "Method $it" },
        codeTextForUi(collection.optJSONObject("bodySite"))?.let { "Site $it" },
    ).joinToString(" · ").ifBlank { null }
}

private fun booleanSummary(resource: JSONObject, key: String, label: String): String? {
    return if (resource.has(key)) "$label ${resource.optBoolean(key)}" else null
}

private fun jsonArrayCount(array: JSONArray?, noun: String): String? {
    val count = array?.length() ?: 0
    if (count == 0) return null
    val plural = when {
        count == 1 -> noun
        noun.endsWith("y") -> noun.dropLast(1) + "ies"
        noun.endsWith("s") -> noun
        else -> noun + "s"
    }
    return "$count $plural"
}

private fun coverageClassSummary(resource: JSONObject, code: String): String? {
    return jsonObjects(resource.optJSONArray("class"))
        .firstOrNull { coverageClassCodeForUi(it.optJSONObject("type")) == code }
        ?.let { item ->
            listOf(
                item.optString("name").ifBlank { null },
                item.optString("value").ifBlank { null },
            ).filterNotNull().joinToString(" ").ifBlank { null }
        }
}

private fun coverageClassCodeForUi(type: JSONObject?): String? {
    return jsonObjects(type?.optJSONArray("coding"))
        .firstNotNullOfOrNull { it.optString("code").lowercase().ifBlank { null } }
}

private fun referenceDisplay(references: JSONArray?): String? {
    return jsonObjects(references).firstNotNullOfOrNull { reference ->
        referenceDisplay(reference)
    }
}

private fun referenceDisplay(reference: JSONObject?): String? {
    if (reference == null) return null
    return reference.optString("display").ifBlank {
        reference.optString("reference").substringAfterLast('/').ifBlank { null }
    }
}

private fun periodSummaryForUi(period: JSONObject?): String? {
    if (period == null) return null
    val start = period.optString("start").ifBlank { null }
    val end = period.optString("end").ifBlank { null }
    return when {
        start != null && end != null -> "$start to $end"
        start != null -> "Since $start"
        end != null -> "Until $end"
        else -> null
    }
}

private fun valueSummaryForUi(resource: JSONObject): String? {
    resource.optJSONObject("valueQuantity")?.let { quantity ->
        val value = quantity.opt("value")?.toString()?.takeIf(String::isNotBlank)
        val unit = quantity.optString("unit").ifBlank { quantity.optString("code") }
        return listOf(value, unit.ifBlank { null }).filterNotNull().joinToString(" ").ifBlank { null }
    }
    resource.optString("valueString").takeIf(String::isNotBlank)?.let { return it }
    val components = resource.optJSONArray("component")
    if (components != null && components.length() > 0) return "${components.length()} component values"
    return null
}

private fun availabilityTone(availability: WalletItemAvailability): ChipTone {
    return when (availability) {
        WalletItemAvailability.Available -> ChipTone.Success
        WalletItemAvailability.PartiallyAvailable -> ChipTone.Warning
        WalletItemAvailability.Unavailable,
        WalletItemAvailability.Unsupported,
        WalletItemAvailability.Error -> ChipTone.Neutral
    }
}

@Composable
private fun QuestionnaireCard(
    credentialId: String,
    questionnaire: JSONObject,
    answers: SnapshotStateMap<String, Any>,
    onAnswerChanged: (String, Any?) -> Unit,
) {
    ElevatedPanel {
        Text(
            text = questionnaireTitle(questionnaire) ?: "Form answers",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Ink,
        )
        val description = questionnaire.optString("description", "")
        if (description.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.Muted,
            )
        }
        Spacer(Modifier.height(18.dp))
        QuestionnaireItems(
            credentialId = credentialId,
            items = questionnaire.optJSONArray("item"),
            answers = answers,
            onAnswerChanged = onAnswerChanged,
            depth = 0,
        )
    }
}

@Composable
private fun QuestionnaireItems(
    credentialId: String,
    items: JSONArray?,
    answers: SnapshotStateMap<String, Any>,
    onAnswerChanged: (String, Any?) -> Unit,
    depth: Int,
) {
    val values = questionnaireValuesFromAnswerState(credentialId, answers)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        jsonObjects(items).forEach { item ->
            if (!isEnabledForUi(item, values)) return@forEach

            when (item.optString("type")) {
                "display" -> DisplayText(item.optString("text", item.optString("linkId")), depth)
                "group" -> QuestionGroup(credentialId, item, answers, onAnswerChanged, depth)
                else -> QuestionnaireField(credentialId, item, answers, onAnswerChanged, depth)
            }
        }
    }
}

@Composable
private fun QuestionGroup(
    credentialId: String,
    item: JSONObject,
    answers: SnapshotStateMap<String, Any>,
    onAnswerChanged: (String, Any?) -> Unit,
    depth: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 8).dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = item.optString("text", item.optString("linkId")),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Ink,
        )
        QuestionnaireItems(
            credentialId = credentialId,
            items = item.optJSONArray("item"),
            answers = answers,
            onAnswerChanged = onAnswerChanged,
            depth = depth + 1,
        )
    }
}

@Composable
private fun QuestionnaireField(
    credentialId: String,
    item: JSONObject,
    answers: SnapshotStateMap<String, Any>,
    onAnswerChanged: (String, Any?) -> Unit,
    depth: Int,
) {
    val linkId = item.optString("linkId")
    val key = answerKey(credentialId, linkId)
    val value = answers[key]
    val type = item.optString("type")
    val label = buildString {
        append(item.optString("text", linkId))
        if (item.optBoolean("required")) append(" *")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 8).dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.testTag("question"),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Ink,
        )

        when {
            type == "boolean" -> BooleanAnswer(value, onChange = { onAnswerChanged(key, it) })
            type == "date" -> DateAnswer(value, onChange = { onAnswerChanged(key, it) })
            type == "integer" && integerBounds(item) != null ->
                IntegerSliderAnswer(item, value, onChange = { onAnswerChanged(key, it) })
            type in setOf("choice", "open-choice") && item.optJSONArray("answerOption") != null && item.optBoolean("repeats") ->
                MultiChoiceAnswer(item, value, onChange = { onAnswerChanged(key, it) })
            type in setOf("choice", "open-choice") && item.optJSONArray("answerOption") != null ->
                SingleChoiceAnswer(item, value, onChange = { onAnswerChanged(key, it) })
            else -> TextAnswer(item, value, onChange = { onAnswerChanged(key, it) })
        }
    }
}

@Composable
private fun DisplayText(text: String, depth: Int) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 8).dp)
            .clip(RoundedCornerShape(SmartRadius.md))
            .background(AppColors.PanelAlt)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = AppColors.Muted,
    )
}

@Composable
private fun BooleanAnswer(value: Any?, onChange: (Boolean) -> Unit) {
    val current = when (value) {
        is Boolean -> value
        is String -> when (value.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = current == true,
            onClick = { onChange(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) {
            Text("Yes")
        }
        SegmentedButton(
            selected = current == false,
            onClick = { onChange(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) {
            Text("No")
        }
    }
}

private fun integerBounds(item: JSONObject): IntRange? {
    var min: Int? = null
    var max: Int? = null
    val extensions = item.optJSONArray("extension") ?: return null
    for (i in 0 until extensions.length()) {
        val ext = extensions.optJSONObject(i) ?: continue
        when (ext.optString("url")) {
            "http://hl7.org/fhir/StructureDefinition/minValue" ->
                if (ext.has("valueInteger")) min = ext.optInt("valueInteger")
            "http://hl7.org/fhir/StructureDefinition/maxValue" ->
                if (ext.has("valueInteger")) max = ext.optInt("valueInteger")
        }
    }
    return if (min != null && max != null && max > min) min..max else null
}

@Composable
private fun IntegerSliderAnswer(item: JSONObject, value: Any?, onChange: (Int) -> Unit) {
    val bounds = integerBounds(item) ?: return
    val current = (value as? Number)?.toInt()
        ?: value?.toString()?.toIntOrNull()
        ?: bounds.first
    val clamped = current.coerceIn(bounds)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SmartRadius.lg))
            .border(BorderStroke(1.dp, AppColors.Line), RoundedCornerShape(SmartRadius.lg))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = bounds.first.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.Muted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = clamped.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Ink,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = bounds.last.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.Muted,
            )
        }
        Slider(
            value = clamped.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = bounds.first.toFloat()..bounds.last.toFloat(),
            steps = (bounds.last - bounds.first - 1).coerceAtLeast(0),
            colors = SliderDefaults.colors(
                thumbColor = AppColors.Primary,
                activeTrackColor = AppColors.Primary,
            ),
        )
    }
}

@Composable
private fun DateAnswer(value: Any?, onChange: (String) -> Unit) {
    val parts = parseDateParts(value?.toString())
    var year by remember { mutableStateOf(parts.year) }
    var month by remember { mutableStateOf(parts.month) }
    var day by remember { mutableStateOf(parts.day) }

    fun emit() {
        onChange(formatDateParts(year, month, day))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SmartRadius.lg))
            .border(BorderStroke(1.dp, AppColors.Line), RoundedCornerShape(SmartRadius.lg))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = year,
            onValueChange = { input ->
                val sanitized = input.filter(Char::isDigit).take(4)
                year = sanitized
                emit()
            },
            modifier = Modifier.weight(1.4f),
            singleLine = true,
            placeholder = { Text("YYYY") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(SmartRadius.md),
        )
        OutlinedTextField(
            value = month,
            onValueChange = { input ->
                val sanitized = input.filter(Char::isDigit).take(2)
                month = sanitized
                emit()
            },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("MM") },
            enabled = year.length == 4,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(SmartRadius.md),
        )
        OutlinedTextField(
            value = day,
            onValueChange = { input ->
                val sanitized = input.filter(Char::isDigit).take(2)
                day = sanitized
                emit()
            },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("DD") },
            enabled = year.length == 4 && month.length in 1..2,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(SmartRadius.md),
        )
    }
}

private data class DateParts(val year: String, val month: String, val day: String)

private fun parseDateParts(raw: String?): DateParts {
    if (raw.isNullOrBlank()) return DateParts("", "", "")
    val tokens = raw.split('-')
    val y = tokens.getOrNull(0)?.filter(Char::isDigit)?.take(4).orEmpty()
    val m = tokens.getOrNull(1)?.filter(Char::isDigit)?.take(2).orEmpty()
    val d = tokens.getOrNull(2)?.filter(Char::isDigit)?.take(2).orEmpty()
    return DateParts(y, m, d)
}

private fun formatDateParts(year: String, month: String, day: String): String {
    if (year.length != 4) return ""
    val mm = month.takeIf { it.isNotBlank() }?.padStart(2, '0') ?: return year
    val dd = day.takeIf { it.isNotBlank() }?.padStart(2, '0') ?: return "$year-$mm"
    return "$year-$mm-$dd"
}

@Composable
private fun SingleChoiceAnswer(item: JSONObject, value: Any?, onChange: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SmartRadius.lg))
            .border(BorderStroke(1.dp, AppColors.Line), RoundedCornerShape(SmartRadius.lg))
            .padding(vertical = 4.dp),
    ) {
        jsonObjects(item.optJSONArray("answerOption")).forEach { option ->
            val key = answerOptionKeyForUi(option)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The radio button is the control (48dp target), as UI automation expects.
                RadioButton(
                    selected = value?.toString() == key,
                    onClick = { onChange(key) },
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = answerOptionLabelForUi(option),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.Ink,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MultiChoiceAnswer(item: JSONObject, value: Any?, onChange: (List<String>) -> Unit) {
    val selected = when (value) {
        is Collection<*> -> value.mapNotNull { it?.toString() }.toSet()
        is JSONArray -> (0 until value.length()).map { value.optString(it) }.toSet()
        null -> emptySet()
        else -> value.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SmartRadius.lg))
            .border(BorderStroke(1.dp, AppColors.Line), RoundedCornerShape(SmartRadius.lg))
            .padding(vertical = 4.dp),
    ) {
        jsonObjects(item.optJSONArray("answerOption")).forEach { option ->
            val key = answerOptionKeyForUi(option)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = key in selected,
                    onCheckedChange = { checked ->
                        val next = if (checked) selected + key else selected - key
                        onChange(next.toList())
                    },
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = answerOptionLabelForUi(option),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.Ink,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TextAnswer(item: JSONObject, value: Any?, onChange: (String) -> Unit) {
    val type = item.optString("type")
    val multiLine = type == "text"
    val keyboardType = when (type) {
        "integer" -> KeyboardType.Number
        "decimal" -> KeyboardType.Decimal
        else -> KeyboardType.Text
    }

    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        minLines = if (multiLine) 3 else 1,
        maxLines = if (multiLine) 5 else 1,
        enabled = !item.optBoolean("readOnly"),
        placeholder = {
            if (type == "date") Text("YYYY-MM-DD")
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(SmartRadius.lg),
    )
}

@Composable
private fun ConsentActions(onShare: () -> Unit, onDecline: () -> Unit) {
    Surface(color = AppColors.Surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
        ) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.Line))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmartPrimaryButton(
                    onClick = onShare,
                    modifier = Modifier.fillMaxWidth().testTag("share-selected"),
                ) {
                    Text("Share selected data")
                }
                SmartSecondaryButton(
                    onClick = onDecline,
                    modifier = Modifier.fillMaxWidth().testTag("decline"),
                ) {
                    Text("Decline")
                }
            }
        }
    }
}

@Composable
private fun TechnicalSummary(request: VerifiedRequest, app: CallerIdentity.App?) {
    var expanded by remember { mutableStateOf(false) }
    ElevatedPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Technical details",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Ink,
            )
            SmartTextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (app == null) {
                "Optional raw request details for debugging and test captures."
            } else {
                "The calling app's package name and origin, and the raw request."
            },
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.Muted,
        )
        if (expanded) {
            if (app != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Calling app",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.Muted,
                )
                Spacer(Modifier.height(6.dp))
                DebugLine("package", app.packageName + " (chosen by the app, not verified)")
                DebugLine("origin", app.protocolOrigin)
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Request transport",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Muted,
            )
            Spacer(Modifier.height(6.dp))
            DebugLine("requestInfo", if (request.requestCarrierDebug.requestInfoPresent) "present" else "absent")
            DebugLine(
                "warnings",
                request.requestCarrierDebug.warnings.ifEmpty { listOf("none") }.joinToString(", "),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "SMART request JSON",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.Muted,
            )
            Spacer(Modifier.height(6.dp))
            CodeBlock(request.rawSmartRequestJson.ifBlank { "{}" })
        }
    }
}

@Composable
private fun DebugLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.widthIn(min = 112.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Muted,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = AppColors.Ink,
        )
    }
}

@Composable
private fun NoticeCard(text: String) {
    ElevatedPanel {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = AppColors.Muted)
    }
}

@Composable
private fun ElevatedPanel(content: @Composable ColumnScope.() -> Unit) {
    SmartCard(content = content)
}

@Composable
private fun CenterPanel(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        SmartCard(contentPadding = PaddingValues(24.dp), content = content)
    }
}

@Composable
private fun StatusDot(color: Color, background: Color) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
private fun StatusChip(text: String, tone: ChipTone) {
    StatusPill(
        text = text,
        tone = when (tone) {
            ChipTone.Success -> StatusTone.Ok
            ChipTone.Warning -> StatusTone.Warn
            ChipTone.Neutral -> StatusTone.Neutral
        },
    )
}

@Composable
private fun DataGlyph(kind: RequestKind) {
    val label = when (kind) {
        RequestKind.Coverage -> "ID"
        RequestKind.Plan -> "PL"
        RequestKind.Clinical -> "CL"
        RequestKind.Questionnaire -> "QA"
        RequestKind.Unknown -> "DT"
    }
    val c = SmartTheme.colors
    val (foreground, background) = when (kind) {
        RequestKind.Coverage -> c.brandInk to c.brandWash
        RequestKind.Plan -> c.ok to c.okWash
        RequestKind.Clinical -> c.fg1 to c.surfaceAlt
        RequestKind.Questionnaire -> c.warn to c.warnWash
        RequestKind.Unknown -> c.fg3 to c.surfaceAlt
    }

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(SmartRadius.md))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = foreground,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * The wallet's theme: [SmartTheme] (the site's colors, Inter, and shapes,
 * following the system's dark setting), with test tags exposed as resource
 * ids so UI automation can find controls without matching their wording.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WalletTheme(content: @Composable () -> Unit) {
    SmartTheme {
        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            content()
        }
    }
}
private fun questionnaireValuesFromAnswerState(credentialId: String, answers: Map<String, Any>): JSONObject {
    val values = JSONObject()
    val prefix = "$credentialId::"
    answers.forEach { (key, value) ->
        if (key.startsWith(prefix)) {
            values.put(key.removePrefix(prefix), if (value is Collection<*>) JSONArray(value) else value)
        }
    }
    return values
}

private fun isEnabledForUi(item: JSONObject, values: JSONObject): Boolean {
    val enableWhen = item.optJSONArray("enableWhen")
    if (enableWhen == null || enableWhen.length() == 0) return true

    val any = item.optString("enableBehavior") == "any"
    var aggregate = !any

    jsonObjects(enableWhen).forEach { condition ->
        val result = compareForUi(values.opt(condition.optString("question")), condition)
        aggregate = if (any) aggregate || result else aggregate && result
    }

    return aggregate
}

private fun compareForUi(actual: Any?, condition: JSONObject): Boolean {
    val operator = condition.optString("operator")
    val expected = when {
        condition.has("answerInteger") -> condition.opt("answerInteger")
        condition.has("answerBoolean") -> condition.opt("answerBoolean")
        condition.has("answerString") -> condition.opt("answerString")
        condition.has("answerCoding") -> condition.optJSONObject("answerCoding")?.optString("code")
        else -> null
    }
    if (operator == "exists") return (actual != null && actual != JSONObject.NULL) == (expected == true)
    if (actual == null || actual == JSONObject.NULL || expected == null || expected == JSONObject.NULL) return false
    if (operator == "=") return containsForUi(actual, expected)
    if (operator == "!=") return !containsForUi(actual, expected)

    return runCatching {
        val left = actual.toString().toDouble()
        val right = expected.toString().toDouble()
        when (operator) {
            ">" -> left > right
            "<" -> left < right
            ">=" -> left >= right
            "<=" -> left <= right
            else -> false
        }
    }.getOrDefault(false)
}

private fun containsForUi(actual: Any, expected: Any): Boolean {
    if (actual is JSONArray) {
        for (index in 0 until actual.length()) {
            if (actual.opt(index).toString() == expected.toString()) return true
        }
        return false
    }
    return actual.toString() == expected.toString()
}

private fun answerOptionKeyForUi(option: JSONObject): String {
    val coding = option.optJSONObject("valueCoding")
    if (coding != null) return coding.optString("code", coding.optString("display", coding.toString()))
    if (option.has("valueString")) return option.optString("valueString")
    if (option.has("valueInteger")) return option.optInt("valueInteger").toString()
    if (option.has("valueDate")) return option.optString("valueDate")
    if (option.has("valueTime")) return option.optString("valueTime")
    return option.toString()
}

private fun answerOptionLabelForUi(option: JSONObject): String {
    val coding = option.optJSONObject("valueCoding")
    if (coding != null) return coding.optString("display", coding.optString("code", coding.toString()))
    if (option.has("valueString")) return option.optString("valueString")
    if (option.has("valueInteger")) return option.optInt("valueInteger").toString()
    if (option.has("valueDate")) return option.optString("valueDate")
    if (option.has("valueTime")) return option.optString("valueTime")
    return option.toString()
}

private fun answerKey(credentialId: String, linkId: String): String = "$credentialId::$linkId"

private fun jsonObjects(array: JSONArray?): List<JSONObject> {
    if (array == null) return emptyList()
    val values = ArrayList<JSONObject>(array.length())
    for (index in 0 until array.length()) {
        array.optJSONObject(index)?.let(values::add)
    }
    return values
}

sealed interface ScreenState {
    data object Empty : ScreenState
    data class Loading(val title: String, val message: String) : ScreenState
    data class Consent(
        val request: VerifiedRequest,
        val resolutions: List<RequestItemResolution>,
    ) : ScreenState
    data class Submitting(val title: String, val message: String) : ScreenState
    data class Error(val message: String) : ScreenState
    data object Complete : ScreenState
}

private enum class ChipTone {
    Success,
    Warning,
    Neutral,
}

/** The screens' color names, mapped to the site's tokens ([SmartTheme.colors]) for the current mode. */
private object AppColors {
    val Page: Color @Composable @ReadOnlyComposable get() = SmartTheme.colors.bgAlt
    val Surface: Color @Composable @ReadOnlyComposable get() = SmartTheme.colors.surface
    val PanelAlt: Color @Composable @ReadOnlyComposable get() = SmartTheme.colors.surfaceAlt
    val Line: Color @Composable @ReadOnlyComposable get() = SmartTheme.colors.border
    val LineStrong: Color @Composable @ReadOnlyComposable get() = SmartTheme.colors.borderStrong
    val Ink: Color @Composable @ReadOnlyComposable get() = SmartTheme.colors.fg1
    val Muted: Color @Composable @ReadOnlyComposable get() = SmartTheme.colors.fg2
    val Subtle: Color @Composable @ReadOnlyComposable get() = SmartTheme.colors.fg3
    val Primary: Color @Composable @ReadOnlyComposable get() = SmartTheme.colors.brand
    val Amber: Color @Composable @ReadOnlyComposable get() = SmartTheme.colors.warn
    val Success: Color @Composable @ReadOnlyComposable get() = SmartTheme.colors.ok
    val SuccessSoft: Color @Composable @ReadOnlyComposable get() = SmartTheme.colors.okWash
    val Error: Color @Composable @ReadOnlyComposable get() = SmartTheme.colors.bad
    val ErrorSoft: Color @Composable @ReadOnlyComposable get() = SmartTheme.colors.badWash
    val Code: Color @Composable @ReadOnlyComposable get() = SmartTheme.colors.codeBg
}
