package org.smarthealthit.checkin.verifier

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.smarthealthit.checkin.theme.CodeBlock
import org.smarthealthit.checkin.theme.SmartCard
import org.smarthealthit.checkin.theme.SmartHeading
import org.smarthealthit.checkin.theme.SmartPrimaryButton
import org.smarthealthit.checkin.theme.SmartSecondaryButton
import org.smarthealthit.checkin.theme.SmartTextButton
import org.smarthealthit.checkin.theme.SmartTheme
import org.smarthealthit.checkin.theme.SmartTopBar
import org.smarthealthit.checkin.theme.StatusPill
import org.smarthealthit.checkin.theme.StatusTone
import java.text.NumberFormat

/** What the last check-in came to, as the screen shows it. */
sealed interface Outcome {
    data class Working(val message: String) : Outcome

    /**
     * A response arrived. [via] says how ("Through the browser"), [details] are
     * name/value lines (wallet, time, size), [request] is the request sent, for item titles.
     */
    data class Completed(
        val via: String,
        val details: List<Pair<String, String>>,
        val response: JSONObject,
        val request: JSONObject?,
    ) : Outcome

    data class Declined(val message: String) : Outcome

    /** A readable [message], and the technical [detail] below it. */
    data class Failed(val message: String, val detail: String? = null) : Outcome
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun VerifierScreen(outcome: Outcome?, onBrowser: () -> Unit, onDirect: () -> Unit) {
    val c = SmartTheme.colors
    Scaffold(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        containerColor = c.bgAlt,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { SmartTopBar(title = "SMART Check-in Verifier", subtitle = "Example Verifier app") },
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
                text = "This example app asks a wallet for a SMART Health Check-in, as a clinic's app would, and shows what comes back.",
                style = MaterialTheme.typography.bodyLarge,
                color = c.fg2,
            )
            SmartCard {
                SmartHeading("Start a check-in")
                Spacer(Modifier.height(16.dp))
                SmartPrimaryButton(onClick = onBrowser, modifier = Modifier.fillMaxWidth().testTag("browser-checkin")) {
                    Text("Check in through the browser")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Opens the check-in page in a Custom Tab. Reaches the wallets on this phone and web wallets.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.fg2,
                )
                Spacer(Modifier.height(20.dp))
                SmartSecondaryButton(onClick = onDirect, modifier = Modifier.fillMaxWidth().testTag("direct-checkin")) {
                    Text("Check in with a wallet on this phone")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Asks Credential Manager directly, with no browser. Reaches only the wallets on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.fg2,
                )
            }
            if (outcome != null) ResultCard(outcome)
        }
    }
}

@Composable
private fun ResultCard(outcome: Outcome) {
    val c = SmartTheme.colors
    SmartCard(modifier = Modifier.testTag("result")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmartHeading("Result", Modifier.weight(1f))
            val (label, tone) = when (outcome) {
                is Outcome.Working -> "In progress" to StatusTone.Info
                is Outcome.Completed -> "Completed" to StatusTone.Ok
                is Outcome.Declined -> "Declined" to StatusTone.Warn
                is Outcome.Failed -> "Failed" to StatusTone.Bad
            }
            StatusPill(label, tone, Modifier.testTag("result-status"))
        }
        Spacer(Modifier.height(12.dp))
        when (outcome) {
            is Outcome.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(outcome.message, style = MaterialTheme.typography.bodyMedium, color = c.fg2)
            }
            is Outcome.Declined -> Text(outcome.message, style = MaterialTheme.typography.bodyMedium, color = c.fg1)
            is Outcome.Failed -> {
                Text(
                    text = outcome.message,
                    modifier = Modifier.testTag("result-message"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.fg1,
                )
                if (!outcome.detail.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    CodeBlock(outcome.detail, maxHeight = 200.dp)
                }
            }
            is Outcome.Completed -> CompletedResult(outcome)
        }
    }
}

@Composable
private fun CompletedResult(outcome: Outcome.Completed) {
    val c = SmartTheme.colors
    val response = outcome.response
    val statuses = objects(response.optJSONArray("requestStatus"))
    val artifacts = objects(response.optJSONArray("artifacts"))
    val titles = objects(outcome.request?.optJSONArray("items")).associate { it.optString("id") to it.optString("title") }

    Text(outcome.via, style = MaterialTheme.typography.bodyLarge, color = c.fg1)
    Spacer(Modifier.height(8.dp))
    outcome.details.forEach { (name, value) -> Field(name, value) }
    Field("Artifacts", artifacts.size.toString())

    Spacer(Modifier.height(16.dp))
    Text("Items", style = MaterialTheme.typography.titleSmall, color = c.fg1)
    Spacer(Modifier.height(4.dp))
    statuses.forEachIndexed { index, status ->
        val id = status.optString("item")
        val code = status.optString("status")
        val records = artifacts
            .filter { a -> objects(a.optJSONArray("fulfills"), strings = true).any { it.optString("id") == id } }
            .sumOf(::recordCount)
        if (index > 0) HorizontalDivider(color = c.borderSubtle)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).testTag("item-$id"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(titles[id]?.takeIf { it.isNotBlank() } ?: id, style = MaterialTheme.typography.bodyLarge, color = c.fg1)
                val meta = buildList {
                    add(id)
                    if (code == "fulfilled" || code == "partial" || records > 0) add("$records ${if (records == 1) "record" else "records"}")
                }.joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = c.fg3)
                status.optString("message").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = c.fg2)
                }
            }
            Spacer(Modifier.width(12.dp))
            StatusPill(code, statusTone(code))
        }
    }

    Spacer(Modifier.height(8.dp))
    var showJson by rememberSaveable(response.optString("requestId")) { mutableStateOf(false) }
    SmartTextButton(onClick = { showJson = !showJson }, modifier = Modifier.testTag("toggle-json")) {
        Text(if (showJson) "Hide the response JSON" else "Show the response JSON")
    }
    if (showJson) {
        // org.json writes "/" as "\/"; both mean "/", and the plain form is easier to read.
        val text = remember(response) { response.toString(2).replace("\\/", "/") }
        val shown = if (text.length > JSON_LIMIT) text.take(JSON_LIMIT) else text
        if (shown.length < text.length) {
            Text(
                "Showing the first ${number(JSON_LIMIT)} of ${number(text.length)} characters.",
                style = MaterialTheme.typography.bodySmall,
                color = c.fg3,
            )
            Spacer(Modifier.height(8.dp))
        }
        CodeBlock(shown, modifier = Modifier.testTag("response-json"))
    }
}

/** Longer JSON is cut off on screen; laying out megabytes of text would stall the app. */
private const val JSON_LIMIT = 60_000

@Composable
private fun Field(name: String, value: String) {
    val c = SmartTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(name, modifier = Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, color = c.fg3)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.fg1)
    }
}

private fun statusTone(code: String) = when (code) {
    "fulfilled" -> StatusTone.Ok
    "partial" -> StatusTone.Warn
    "error" -> StatusTone.Bad
    else -> StatusTone.Neutral
}

/** How many records an artifact carries: a Bundle's entries, a SMART Health Card's credentials, else one. */
private fun recordCount(artifact: JSONObject): Int {
    val value = artifact.optJSONObject("value") ?: return 1
    return when {
        value.optString("resourceType") == "Bundle" -> value.optJSONArray("entry")?.length() ?: 0
        value.has("verifiableCredential") -> value.optJSONArray("verifiableCredential")?.length() ?: 1
        else -> 1
    }
}

/** The objects in a JSON array; with [strings], each string becomes `{"id": it}`. */
private fun objects(array: JSONArray?, strings: Boolean = false): List<JSONObject> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        array.optJSONObject(i) ?: if (strings) array.optString(i).takeIf { it.isNotEmpty() }?.let { JSONObject().put("id", it) } else null
    }
}

fun number(n: Number): String = NumberFormat.getIntegerInstance().format(n)
