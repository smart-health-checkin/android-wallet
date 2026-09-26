# verifier-app: a native Android app as the Verifier

An example of a native app asking for a SMART Health Check-in. The developer
guide is [Native apps](https://smart-health-checkin.org/client/docs/native-apps.html).

| Button | What it does | Reaches |
| --- | --- | --- |
| Check in through the browser | Opens the [bridge page](https://smart-health-checkin.org/client/demo/native-bridge.html) in a Custom Tab, sends it the request over a Custom Tabs message channel, and gets the checked response back the same way, in parts ([`BrowserCheckin`](src/main/java/org/smarthealthit/checkin/verifier/BrowserCheckin.kt)). | The phone's wallets and web wallets |
| Check in with a wallet on this phone | Calls `CredentialManager.getCredential(GetDigitalCredentialOption(…))` directly and decrypts the response here, with the transcript bound to this app's `android:apk-key-hash:` origin. | The phone's wallets |

The browser path needs the bridge page's site to list this app in
`/.well-known/assetlinks.json` (`delegate_permission/common.use_as_origin`).
smart-health-checkin.org lists `org.smarthealthit.checkin.verifier` signed with
the shared key, so build with it:

```sh
./gradlew :verifier-app:assembleDebug -Pdebug-keystore=<path to the shared debug keystore>
adb install -r verifier-app/build/outputs/apk/debug/verifier-app-debug.apk
```

Launch extras, used by the automated test: `request` (a SMART request as JSON)
and `registry` (the wallet registry the bridge page's picker loads; default:
the connectathon registry). Each result is logged as
`SHCVerifier: RESULT path=browser|direct ok=… ms=… chars=…`.

The direct button's transcript origin follows spec TR-2 (`android:apk-key-hash:`).
The reference wallet switches to that format in its next release; until then
the direct button's responses from it won't decrypt.
