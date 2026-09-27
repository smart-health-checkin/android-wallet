package org.smarthealthit.checkin.wallet

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.credentials.provider.SigningInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Reads what an app caller says about itself (the websites in its
 * `asset_statements`, its label, who installed it) and runs the two-way check in
 * [AssetLinks] against each declared website. Only the verified websites name the
 * app on the consent screen.
 */
object AppCallerIdentity {
    private const val TAG = "SHCAppCaller"

    /** What PackageManager says about the app, before any network check. */
    data class Local(
        val packageName: String,
        val label: String?,
        val installer: String?,
        val certFingerprints: List<String>,
        val declared: AssetLinks.Declared,
    )

    fun readLocal(pm: PackageManager, packageName: String, signingInfo: SigningInfoCompat): Local {
        val fingerprints = signingInfo.apkContentsSigners.ifEmpty { signingInfo.signingCertificateHistory.takeLast(1) }
            .map { AssetLinks.fingerprint(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }
            .distinct()
        val info: ApplicationInfo? = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            }
        }.onFailure { Log.w(TAG, "cannot read $packageName's application info", it) }.getOrNull()
        if (info == null) {
            return Local(packageName, null, installerOf(pm, packageName), fingerprints,
                AssetLinks.Declared(emptyList(), listOf("the wallet could not read the app's manifest")))
        }
        val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrNull()
        val resId = info.metaData?.getInt("asset_statements", 0) ?: 0
        val statements = if (resId == 0) null else runCatching {
            pm.getResourcesForApplication(info).getString(resId)
        }.onFailure { Log.w(TAG, "cannot read $packageName's asset_statements", it) }.getOrNull()
        val declared = if (resId != 0 && statements == null) {
            AssetLinks.Declared(emptyList(), listOf("the app's asset_statements resource could not be read"))
        } else {
            AssetLinks.declaredSites(statements)
        }
        return Local(packageName, label, installerOf(pm, packageName), fingerprints, declared)
    }

    private fun installerOf(pm: PackageManager, packageName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName)
        }
    }.getOrNull()

    /** Checks every declared site in parallel, off the main thread. */
    suspend fun check(local: Local): AppLinkStatus.Done = withContext(Dispatchers.IO) {
        val results = coroutineScope {
            local.declared.sites.map { site ->
                async { AssetLinks.checkSite(site, local.packageName, local.certFingerprints) }
            }.awaitAll()
        }
        results.forEach { Log.i(TAG, "site ${it.site} for ${local.packageName}: verified=${it.verified} ${it.detail}") }
        AppLinkStatus.Done(
            declaredSites = local.declared.sites,
            verifiedSites = results.filter { it.verified }.map { it.site },
            results = local.declared.problems + results.map {
                if (it.verified) "${AssetLinks.displayName(it.site)}: ${it.detail}" else it.detail
            },
        )
    }
}
