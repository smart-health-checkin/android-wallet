package org.smarthealthit.checkin.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

/** The consent and success screens say only what the wallet knows about who is asking. */
class RequesterWordingTest {
    @Test
    fun websiteCaller() {
        val caller = CallerIdentity.Website("https://clinic.example")
        assertEquals("A website", requesterPhrase(caller))
        assertEquals("https://clinic.example", recipientPhrase(caller))
    }

    @Test
    fun appCallerIsNotNamed() {
        // The package name is the app's own choice, so it never names the app.
        val caller = CallerIdentity.App("android:apk-key-hash:x", "com.example.clinic")
        assertEquals("An app", requesterPhrase(caller))
        assertEquals("the app", recipientPhrase(caller))
    }
}
