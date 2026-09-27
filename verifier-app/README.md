# verifier-app: a native Android app as the Verifier

An example of a native app asking for a SMART Health Check-in. The developer
guide is [Native Verifier apps](https://smart-health-checkin.org/client/docs/native-apps.html).

| Button | What it does | Reaches |
| --- | --- | --- |
| Check in through the browser | Opens the [bridge page](https://smart-health-checkin.org/client/demo/native-bridge.html) in a Custom Tab, sends it the request over a Custom Tabs message channel, and gets the checked response back the same way, in parts ([`BrowserCheckin`](src/main/java/org/smarthealthit/checkin/verifier/BrowserCheckin.kt)). | The phone's wallets and web wallets |
| Check in with a wallet on this phone | Calls `CredentialManager.getCredential(GetDigitalCredentialOption(…))` directly and decrypts the response here, with the transcript bound to this app's `android:apk-key-hash:` origin. | The phone's wallets |

Below the buttons, the result shows whether the check-in completed, was
declined, or failed. For a response it lists each requested item with its
status and how many records came back, and the response JSON can be opened
below. For a failure it shows what went wrong, with the technical detail
underneath. The screen uses [`smart-checkin-theme`](../smart-checkin-theme/README.md)
and follows the phone's dark setting.

Install the latest release on a phone or emulator:
<https://github.com/smart-health-checkin/android-wallet/releases/latest/download/smart-health-checkin-verifier.apk>
(`adb install -r smart-health-checkin-verifier.apk` after downloading it). It is
signed with the shared development key, so the browser path works as released.

The browser path needs the bridge page's site to list this app in
`/.well-known/assetlinks.json` (`delegate_permission/common.use_as_origin`).
smart-health-checkin.org lists `org.smarthealthit.checkin.verifier` signed with
the shared key, so a local build must use it too:

```sh
./gradlew :verifier-app:assembleDebug -Pdebug-keystore=<path to the shared development keystore>
adb install -r verifier-app/build/outputs/apk/debug/verifier-app-debug.apk
```

Launch extras, used by the automated test: `request` (a SMART request as JSON)
and `registry` (the wallet registry the bridge page's picker loads; default:
the connectathon registry). The test finds the buttons by their Compose test
tags, `browser-checkin` and `direct-checkin`, which show as resource ids. Each result is logged as
`SHCVerifier: RESULT path=browser|direct ok=… ms=… chars=…`.

The direct button's transcript origin follows spec
[TR-2](https://smart-health-checkin.org/spec/#TR-2) (`android:apk-key-hash:`),
which the reference wallet uses for app callers.

The app declares smart-health-checkin.org in an `asset_statements` resource
(`res/values/strings.xml`, referenced from the manifest's `<meta-data>`). With the
site's `assetlinks.json` listing the app back, wallets that check both directions,
such as the reference wallet from 0.4.6, show "An app linked to
smart-health-checkin.org is asking" instead of the app's package name
([Native Verifier apps](https://smart-health-checkin.org/client/docs/native-apps.html#how-wallets-name-your-app)).

`bun tools/verifier-app-e2e/run.ts` runs five cases: `direct` (with the reference
wallet installed), `small`, and `large` (through the browser, answered by the
SMART Testing Wallet), and `repeat` (two browser check-ins) and `mixed` (browser,
direct, browser), each in one app process.
