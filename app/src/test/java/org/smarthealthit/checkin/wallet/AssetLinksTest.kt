package org.smarthealthit.checkin.wallet

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

class AssetLinksTest {
    private val pkg = "org.smarthealthit.checkin.verifier"
    private val key = "84:64:3E:B6:9B:C9:5A:2D:C4:EC:2D:4D:92:AF:77:A6:4A:8C:B0:0F:0E:2C:7B:99:BC:E3:93:CE:E2:02:DD:A4"
    private val otherKey = "18:47:FE:96:DC:A8:1B:22:13:2D:57:B7:FE:90:5C:02:3C:47:AD:EA:CE:DD:05:3C:20:F8:A9:52:93:76:00:11"

    private fun siteFile(relation: String = "delegate_permission/common.use_as_origin", packageName: String = pkg, fingerprint: String = key) = """
        [{"relation": ["$relation"],
          "target": {"namespace": "android_app", "package_name": "$packageName",
                     "sha256_cert_fingerprints": ["$fingerprint"]}}]
    """

    @Before fun clear() = AssetLinks.clearCache()

    // The app's side: asset_statements.

    @Test fun declaredSitesReadsWebTargetsAndIncludes() {
        val declared = AssetLinks.declaredSites(
            """[
              {"relation": ["delegate_permission/common.use_as_origin"], "target": {"namespace": "web", "site": "https://Smart-Health-Checkin.org/"}},
              {"include": "https://example.org/.well-known/assetlinks.json"},
              {"relation": ["x"], "target": {"namespace": "android_app", "package_name": "a.b"}}
            ]""",
        )
        assertEquals(listOf("https://smart-health-checkin.org", "https://example.org"), declared.sites)
        assertTrue(declared.problems.isEmpty())
    }

    @Test fun declaredSitesRejectsNonHttpsAndPaths() {
        val declared = AssetLinks.declaredSites(
            """[{"target": {"namespace": "web", "site": "http://example.org"}},
                {"target": {"namespace": "web", "site": "https://example.org/app"}},
                {"include": "https://example.org/other.json"}]""",
        )
        assertTrue(declared.sites.isEmpty())
        assertEquals(3, declared.problems.size)
    }

    @Test fun declaredSitesMalformedJson() {
        val declared = AssetLinks.declaredSites("{not json")
        assertTrue(declared.sites.isEmpty())
        assertEquals(listOf("the app's asset_statements is not a JSON array"), declared.problems)
        assertTrue(AssetLinks.declaredSites(null).sites.isEmpty())
        assertTrue(AssetLinks.declaredSites("""{"target": {"namespace": "web", "site": "https://a.org"}}""").sites.isEmpty())
    }

    // The website's side: assetlinks.json.

    @Test fun matchesPackageKeyAndRelation() {
        for (relation in AssetLinks.ACCEPTED_RELATIONS) {
            val m = AssetLinks.match(siteFile(relation), pkg, listOf(key))
            assertEquals(AssetLinks.Match.Yes(listOf(relation)), m)
        }
    }

    @Test fun fingerprintComparisonIgnoresCaseAndColons() {
        val m = AssetLinks.match(siteFile(fingerprint = key.lowercase()), pkg, listOf(key.replace(":", "")))
        assertTrue(m is AssetLinks.Match.Yes)
    }

    @Test fun rejectsOtherFingerprint() {
        val m = AssetLinks.match(siteFile(fingerprint = otherKey), pkg, listOf(key))
        assertEquals(AssetLinks.Match.No("its assetlinks.json lists $pkg with a different signing key"), m)
    }

    @Test fun rejectsWhenOnlySomeSignersListed() {
        val m = AssetLinks.match(siteFile(), pkg, listOf(key, otherKey))
        assertTrue(m is AssetLinks.Match.No)
    }

    @Test fun rejectsOtherPackage() {
        val m = AssetLinks.match(siteFile(packageName = "com.example.other"), pkg, listOf(key))
        assertEquals(AssetLinks.Match.No("its assetlinks.json has no statement for $pkg"), m)
    }

    @Test fun rejectsOtherRelation() {
        val m = AssetLinks.match(siteFile(relation = "delegate_permission/common.share_location"), pkg, listOf(key))
        assertTrue(m is AssetLinks.Match.No)
        assertTrue((m as AssetLinks.Match.No).reason.contains("without a relation"))
    }

    @Test fun rejectsWebTargetNamedLikeThePackage() {
        val json = """[{"relation": ["delegate_permission/common.use_as_origin"],
            "target": {"namespace": "web", "package_name": "$pkg", "sha256_cert_fingerprints": ["$key"]}}]"""
        assertTrue(AssetLinks.match(json, pkg, listOf(key)) is AssetLinks.Match.No)
    }

    @Test fun malformedSiteFile() {
        assertEquals(AssetLinks.Match.No("its assetlinks.json is not a JSON array"), AssetLinks.match("<html>", pkg, listOf(key)))
        assertEquals(AssetLinks.Match.No("its assetlinks.json is not a JSON array"), AssetLinks.match("{}", pkg, listOf(key)))
        assertTrue(AssetLinks.match("""[1, "x", {"target": 3}, {"target": {"namespace": "android_app"}}]""", pkg, listOf(key)) is AssetLinks.Match.No)
    }

    // Fetching and caching.

    @Test fun onlyHttpsSitesAreFetched() {
        assertNull(AssetLinks.assetLinksUrl("http://example.org"))
        assertEquals("https://example.org/.well-known/assetlinks.json", AssetLinks.assetLinksUrl("https://example.org")!!.toString())
        var fetched = false
        val r = AssetLinks.checkSite("http://example.org", pkg, listOf(key), { fetched = true; AssetLinks.Fetched.Body("[]") })
        assertFalse(r.verified)
        assertFalse(fetched)
    }

    @Test fun cachesAnswersForAnHour() {
        var calls = 0
        val fetcher = AssetLinks.Fetcher { calls++; AssetLinks.Fetched.Body(siteFile()) }
        assertTrue(AssetLinks.checkSite("https://smart-health-checkin.org", pkg, listOf(key), fetcher, now = 0).verified)
        assertTrue(AssetLinks.checkSite("https://smart-health-checkin.org", pkg, listOf(key), fetcher, now = 59 * 60 * 1000).verified)
        assertEquals(1, calls)
        // A different key is a different cache entry, and is not verified.
        assertFalse(AssetLinks.checkSite("https://smart-health-checkin.org", pkg, listOf(otherKey), fetcher, now = 1).verified)
        assertEquals(2, calls)
        AssetLinks.checkSite("https://smart-health-checkin.org", pkg, listOf(key), fetcher, now = 61 * 60 * 1000)
        assertEquals(3, calls)
    }

    @Test fun networkFailuresAreNotVerifiedAndNotCached() {
        var calls = 0
        val fetcher = AssetLinks.Fetcher { calls++; AssetLinks.Fetched.Failed("could not reach example.org (UnknownHostException)", definitive = false) }
        val r = AssetLinks.checkSite("https://example.org", pkg, listOf(key), fetcher, now = 0)
        assertFalse(r.verified)
        assertEquals("could not reach example.org (UnknownHostException)", r.detail)
        AssetLinks.checkSite("https://example.org", pkg, listOf(key), fetcher, now = 1)
        assertEquals(2, calls)
    }

    // The HTTP fetcher, against a local server (the scheme check is separate, above).

    private lateinit var server: ServerSocket

    /** A tiny HTTP/1.0 server: /redirect answers 301, /ok a matching file, anything else 404. */
    @Before fun startServer() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val path = it.getInputStream().bufferedReader().readLine()?.split(" ")?.getOrNull(1)
                    val body = siteFile().toByteArray()
                    val head = when (path) {
                        "/redirect" -> "HTTP/1.0 301 Moved Permanently\r\nLocation: /ok\r\nContent-Length: 0\r\n\r\n"
                        "/ok" -> "HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n"
                        else -> "HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n"
                    }
                    it.getOutputStream().write(head.toByteArray())
                    if (path == "/ok") it.getOutputStream().write(body)
                    it.getOutputStream().flush()
                }
            }
        }
    }

    @After fun stopServer() = server.close()

    private fun local(path: String) = URL("http://127.0.0.1:${server.localPort}$path")

    @Test fun fetcherDoesNotFollowRedirects() {
        val r = AssetLinks.httpFetcher.fetch(local("/redirect"))
        assertTrue(r is AssetLinks.Fetched.Failed)
        assertTrue((r as AssetLinks.Fetched.Failed).reason.contains("redirected (HTTP 301)"))
        assertTrue(r.definitive)
    }

    @Test fun fetcherReadsBodyAndReportsStatus() {
        val ok = AssetLinks.httpFetcher.fetch(local("/ok"))
        assertTrue(ok is AssetLinks.Fetched.Body)
        assertTrue(AssetLinks.match((ok as AssetLinks.Fetched.Body).text, pkg, listOf(key)) is AssetLinks.Match.Yes)
        val missing = AssetLinks.httpFetcher.fetch(local("/missing"))
        assertTrue(missing is AssetLinks.Fetched.Failed && missing.reason.contains("HTTP 404"))
    }
}
