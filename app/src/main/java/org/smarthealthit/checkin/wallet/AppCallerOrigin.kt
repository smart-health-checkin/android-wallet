package org.smarthealthit.checkin.wallet

import androidx.credentials.provider.SigningInfoCompat
import java.security.MessageDigest
import java.util.Base64

/**
 * The origin string for a native Android app that calls the wallet directly
 * (spec [TR-2]; platform notes, "Native apps as Verifiers"). Android reports no
 * web origin for such a caller, so the origin is
 * `android:apk-key-hash:` + base64url (no padding) SHA-256 of the DER-encoded
 * signing certificate. The calling app computes the same string for its own
 * transcript.
 *
 * Which certificate: `apkContentsSigners`, the certificates the APK is signed
 * with now. After a key rotation that is the current certificate, not the
 * original one in `signingCertificateHistory`. An APK signed by several signers
 * (rare) uses the first one Android lists.
 */
object AppCallerOrigin {
    const val PREFIX = "android:apk-key-hash:"

    fun of(signingInfo: SigningInfoCompat): String {
        val cert = signingInfo.apkContentsSigners.firstOrNull()
            ?: signingInfo.signingCertificateHistory.lastOrNull()
            ?: error("the calling app has no signing certificate")
        return fromCertificate(cert.toByteArray())
    }

    fun fromCertificate(derCertificate: ByteArray): String =
        PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(derCertificate))
}
