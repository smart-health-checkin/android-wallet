package org.smarthealthit.checkin.wallet

/**
 * Who is asking, as the consent screen shows it. A browser caller is named by
 * its web origin. An app caller is named only by websites that vouch for it
 * through a two-way Digital Asset Links check; its package name and label are
 * the app's own choice and appear only under Technical details.
 */
sealed interface CallerIdentity {
    data class Website(val origin: String) : CallerIdentity

    data class App(
        /** The `android:apk-key-hash:` origin used in the session transcript. */
        val protocolOrigin: String,
        val packageName: String,
        /** The app's own label; not verified. */
        val appLabel: String?,
        /** The package that installed the app, if Android says. */
        val installer: String?,
        val check: AppLinkStatus,
    ) : CallerIdentity
}

sealed interface AppLinkStatus {
    data object Checking : AppLinkStatus

    /** [verifiedSites]: origins that passed both directions. [results]: one line per declared site. */
    data class Done(
        val declaredSites: List<String>,
        val verifiedSites: List<String>,
        val results: List<String>,
    ) : AppLinkStatus
}
