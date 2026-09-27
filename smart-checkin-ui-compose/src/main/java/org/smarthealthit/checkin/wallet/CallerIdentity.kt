package org.smarthealthit.checkin.wallet

/**
 * Who is asking, as the consent screen shows it. A browser caller is named by
 * its web origin. An app caller is not named: Credential Manager gives the wallet
 * only its package name and signing certificate, and nothing a patient could
 * rely on ties those to a website or organization, so the screen says "An app".
 * The package name and `android:apk-key-hash:` origin appear under Technical details.
 */
sealed interface CallerIdentity {
    data class Website(val origin: String) : CallerIdentity

    data class App(
        /** The `android:apk-key-hash:` origin used in the session transcript. */
        val protocolOrigin: String,
        val packageName: String,
    ) : CallerIdentity
}
