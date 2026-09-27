package org.smarthealthit.checkin.wallet

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * The two-way Digital Asset Links check that names an app caller by a website.
 *
 * The package name and label of an app that calls the wallet are strings the app
 * chose itself, so the wallet never shows them as who is asking. What a patient can
 * rely on is a website they recognize vouching for this exact app. The check needs
 * both directions:
 *
 *  1. The app declares the website: its manifest has
 *     `<meta-data android:name="asset_statements" android:resource="@string/asset_statements"/>`,
 *     a JSON array of statements. A statement whose target is `{"namespace": "web",
 *     "site": "https://example.org"}` declares that site; so does
 *     `{"include": "https://example.org/.well-known/assetlinks.json"}`.
 *  2. The website vouches for the app: `https://<site>/.well-known/assetlinks.json`
 *     has a statement whose target is this package with the SHA-256 fingerprint of
 *     the certificate the app is signed with, and whose relation is one of
 *     [ACCEPTED_RELATIONS].
 *
 * Only then is the site verified for this app. The fetch is HTTPS only, follows no
 * redirects (as the Digital Asset Links protocol requires), and has a short timeout.
 * Anything that fails, including being offline, leaves the site unverified with the
 * reason recorded.
 */
object AssetLinks {
    /**
     * Relations in the website's file that count as vouching for the app:
     * `use_as_origin` (the app may speak for the site's origin; Custom Tabs uses it
     * for postMessage), `handle_all_urls` (Android App Links), and `get_login_creds`
     * (the app shares sign-in credentials with the site). Each says the site's owner
     * put this app, with this signing key, under their name.
     */
    val ACCEPTED_RELATIONS = setOf(
        "delegate_permission/common.use_as_origin",
        "delegate_permission/common.handle_all_urls",
        "delegate_permission/common.get_login_creds",
    )

    const val CACHE_TTL_MS = 60L * 60 * 1000
    const val TIMEOUT_MS = 5000
    const val MAX_BYTES = 256 * 1024

    /** A website the app declared, as an `https://host[:port]` origin, or why a statement could not be used. */
    data class Declared(val sites: List<String>, val problems: List<String>)

    /**
     * The websites an app's `asset_statements` resource declares, as origins.
     * Malformed JSON gives no sites and a problem; statements that are not web
     * targets are skipped.
     */
    fun declaredSites(assetStatementsJson: String?): Declared {
        if (assetStatementsJson.isNullOrBlank()) return Declared(emptyList(), emptyList())
        val array = runCatching { JSONArray(assetStatementsJson.trim()) }.getOrElse {
            return Declared(emptyList(), listOf("the app's asset_statements is not a JSON array"))
        }
        val sites = LinkedHashSet<String>()
        val problems = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val statement = array.optJSONObject(i) ?: continue
            val include = statement.optString("include").takeIf { it.isNotBlank() }
            val site = if (include != null) {
                // An include that points at a site's own assetlinks.json names that site.
                val uri = runCatching { URI(include) }.getOrNull()
                if (uri?.path != "/.well-known/assetlinks.json") {
                    problems += "include $include is not a site's /.well-known/assetlinks.json"
                    continue
                }
                "${uri.scheme}://${uri.rawAuthority}"
            } else {
                val target = statement.optJSONObject("target") ?: continue
                if (target.optString("namespace") != "web") continue
                target.optString("site").takeIf { it.isNotBlank() } ?: continue
            }
            val origin = normalizeSite(site)
            if (origin == null) problems += "$site is not an https origin" else sites += origin
        }
        return Declared(sites.toList(), problems)
    }

    /** `https://host[:port]` with a lowercase host and no path, or null if [site] is not an https origin. */
    fun normalizeSite(site: String): String? {
        val uri = runCatching { URI(site.trim().trimEnd('/')) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase() ?: return null
        if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        return "https://$host$port"
    }

    /** The site as people read it: the host, plus the port if it isn't 443. */
    fun displayName(origin: String): String = origin.removePrefix("https://")

    /** Colon-separated uppercase hex, the form assetlinks.json uses. */
    fun fingerprint(sha256: ByteArray): String = sha256.joinToString(":") { "%02X".format(it) }

    private fun normalizeFingerprint(value: String): String = value.replace(":", "").uppercase()

    /** Whether a site's statement list vouches for [packageName] signed with [certFingerprints]. */
    sealed interface Match {
        data class Yes(val relations: List<String>) : Match
        data class No(val reason: String) : Match
    }

    /**
     * Check a site's `assetlinks.json` body. It must be a JSON array with a statement
     * whose target is this Android package, lists the fingerprint of every
     * certificate the app is signed with, and has a relation in [ACCEPTED_RELATIONS].
     */
    fun match(assetLinksJson: String, packageName: String, certFingerprints: List<String>): Match {
        val array = runCatching { JSONArray(assetLinksJson.trim()) }.getOrElse {
            return Match.No("its assetlinks.json is not a JSON array")
        }
        if (certFingerprints.isEmpty()) return Match.No("the app has no signing certificate")
        val wanted = certFingerprints.map(::normalizeFingerprint).toSet()
        var samePackage = false
        var sameKey = false
        for (i in 0 until array.length()) {
            val statement = array.optJSONObject(i) ?: continue
            val target = statement.optJSONObject("target") ?: continue
            if (target.optString("namespace") != "android_app") continue
            if (target.optString("package_name") != packageName) continue
            samePackage = true
            val listed = strings(target.optJSONArray("sha256_cert_fingerprints")).map(::normalizeFingerprint).toSet()
            if (!listed.containsAll(wanted)) continue
            sameKey = true
            val relations = strings(statement.optJSONArray("relation")).filter { it in ACCEPTED_RELATIONS }
            if (relations.isNotEmpty()) return Match.Yes(relations)
        }
        return Match.No(
            when {
                !samePackage -> "its assetlinks.json has no statement for $packageName"
                !sameKey -> "its assetlinks.json lists $packageName with a different signing key"
                else -> "its assetlinks.json lists $packageName without a relation that vouches for the app " +
                    "(${ACCEPTED_RELATIONS.joinToString { it.substringAfterLast('.') }})"
            },
        )
    }

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.opt(it) as? String }

    /** One site's result for one app. */
    data class SiteResult(val site: String, val verified: Boolean, val detail: String)

    sealed interface Fetched {
        data class Body(val text: String) : Fetched
        data class Failed(val reason: String, val definitive: Boolean) : Fetched
    }

    /** Fetches a URL; replaceable in tests. */
    fun interface Fetcher {
        fun fetch(url: URL): Fetched
    }

    /**
     * GET with no redirects, a short timeout, and a size cap. The scheme is checked
     * by [assetLinksUrl]; this only moves bytes.
     */
    val httpFetcher = Fetcher { url ->
        try {
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            try {
                val code = connection.responseCode
                when {
                    code in 300..399 ->
                        Fetched.Failed("${url.host} redirected (HTTP $code); assetlinks.json must be served without redirects", definitive = true)
                    code != 200 -> Fetched.Failed("${url.host} answered HTTP $code for assetlinks.json", definitive = code in 400..499)
                    else -> {
                        val out = ByteArrayOutputStream()
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                if (out.size() > MAX_BYTES) {
                                    return@Fetcher Fetched.Failed("${url.host}'s assetlinks.json is larger than $MAX_BYTES bytes", definitive = true)
                                }
                            }
                        }
                        Fetched.Body(out.toString(Charsets.UTF_8.name()))
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            Fetched.Failed("could not reach ${url.host} (${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""})", definitive = false)
        }
    }

    fun assetLinksUrl(site: String): URL? = normalizeSite(site)?.let { URL("$it/.well-known/assetlinks.json") }

    private data class CacheKey(val packageName: String, val fingerprints: List<String>, val site: String)
    private data class CacheEntry(val result: SiteResult, val at: Long)
    private val cache = HashMap<CacheKey, CacheEntry>()

    fun clearCache() = synchronized(cache) { cache.clear() }

    /**
     * Check one declared site for one app. Answers from the site (a statement list,
     * a 4xx, a redirect) are cached for [CACHE_TTL_MS] by package, certificate, and
     * site; network failures are not cached, so the next request tries again.
     */
    fun checkSite(
        site: String,
        packageName: String,
        certFingerprints: List<String>,
        fetcher: Fetcher = httpFetcher,
        now: Long = System.currentTimeMillis(),
    ): SiteResult {
        val url = assetLinksUrl(site) ?: return SiteResult(site, false, "$site is not an https origin")
        val key = CacheKey(packageName, certFingerprints.map(::normalizeFingerprint).sorted(), normalizeSite(site)!!)
        synchronized(cache) {
            cache[key]?.takeIf { now - it.at < CACHE_TTL_MS }?.let { return it.result }
        }
        val (result, cacheable) = when (val fetched = fetcher.fetch(url)) {
            is Fetched.Failed -> SiteResult(key.site, false, fetched.reason) to fetched.definitive
            is Fetched.Body -> when (val m = match(fetched.text, packageName, certFingerprints)) {
                is Match.Yes -> SiteResult(key.site, true, "vouched for by ${m.relations.joinToString { it.substringAfterLast('.') }}") to true
                is Match.No -> SiteResult(key.site, false, "${displayName(key.site)}: ${m.reason}") to true
            }
        }
        if (cacheable) synchronized(cache) { cache[key] = CacheEntry(result, now) }
        return result
    }
}
