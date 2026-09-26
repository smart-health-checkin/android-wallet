# SMART Health Check-in Android wallet

Download the latest build: <https://github.com/smart-health-checkin/android-wallet/releases/latest/download/smart-checkin-wallet-debug.apk>

This repo was split out of [smart-health-checkin/spec](https://github.com/smart-health-checkin/spec) with its history. Unit tests read the spec's conformance fixtures at a pinned tag (`SPEC_FIXTURES_REF` in [`scripts/fetch-fixtures.sh`](scripts/fetch-fixtures.sh), currently `fixtures-v2`); Gradle's test tasks fetch them into the gitignored `fixtures/` first. Set `SPEC_FIXTURES_DIR=../spec/fixtures` to test against a local spec checkout. Test vectors are generated from the client library: `bun install && bun run vectors`.

## Libraries

This is the reference native Android wallet for SMART Health Check-in over W3C
Digital Credentials API using direct `org-iso-mdoc` (specified in
[the spec](https://smart-health-checkin.org/spec/) §8 and Appendix A).

The project is now split into library-shaped Gradle modules plus a demo app.
The split is intended to make future Android wallet apps small: app code should
provide holder data and UI decisions, while library code handles SMART request
parsing, mdoc transport, Credential Manager registration, and reusable Compose
screens.

## Module map

| Module | Responsibility | README |
| --- | --- | --- |
| `smart-checkin-core` | SMART request/response domain model, request classification, response building, QuestionnaireResponse building, wallet-store interface. | [`smart-checkin-core/README.md`](smart-checkin-core/README.md) |
| `smart-checkin-mdoc` | Direct `org-iso-mdoc` request parsing, SessionTranscript, readerAuth verification, CBOR, COSE, HPKE-sealed wallet response. | [`smart-checkin-mdoc/README.md`](smart-checkin-mdoc/README.md) |
| `smart-checkin-credential-manager` | Android Credential Manager / registry-provider registration for the wallet entry and matcher bytes. | [`smart-checkin-credential-manager/README.md`](smart-checkin-credential-manager/README.md) |
| `smart-checkin-ui-compose` | Compose demo/reusable UI layer: registration home, holder review screens, Questionnaire rendering helpers, theme/state. | [`smart-checkin-ui-compose/README.md`](smart-checkin-ui-compose/README.md) |
| `app` | Demo app shell: manifest, `HandlerActivity`, sample wallet store, bundled demo assets, matcher build/copy tasks, end-to-end wiring. | This file |
| `rp-app` | Spike: a *native relying party* that requests a check-in from the wallet directly through Credential Manager (no browser), plus WebView / Custom Tab probes. | [`rp-app/README.md`](rp-app/README.md) |

Dependency direction:

```text
smart-checkin-core
  <- smart-checkin-mdoc
  <- smart-checkin-ui-compose

smart-checkin-credential-manager
  <- smart-checkin-ui-compose

app
  -> all four library modules
```

The package name is still `org.smarthealthit.checkin.wallet` across modules to
minimize churn while the APIs stabilize. Android namespaces differ by module.

## End-to-end flow

```text
Verifier page
  builds SMART Check-in request
  wraps it in direct org-iso-mdoc deviceRequest/encryptionInfo
  calls navigator.credentials.get(...)

Android Credential Manager
  runs matcher.wasm
  shows the SMART Health Check-in wallet entry
  launches HandlerActivity

HandlerActivity / libraries
  smart-checkin-mdoc parses deviceRequest and encryptionInfo
  smart-checkin-core turns SMART JSON into holder-review/request models
  smart-checkin-ui-compose renders holder review and questionnaire input
  app DemoWalletStore resolves selected resources
  smart-checkin-core builds SMART response JSON
  smart-checkin-mdoc returns encrypted direct-mdoc DeviceResponse (SmartHealthMdocWallet)
```

The SMART request is carried in:

```text
ItemsRequest.requestInfo["org.smarthealthit.checkin.request"]
```

The SMART response is returned as mdoc element:

```text
namespace: org.smarthealthit.checkin
element:   smart_health_checkin_response
doctype:   org.smarthealthit.checkin.1
```

## How the wallet follows the spec

- **Reading a request** (spec §8.4): it fails, and returns nothing, only when it
  can't decode the request, finds no DocRequest for `org.smarthealthit.checkin.1`
  or no request text in `requestInfo`, gets an invalid SMART request, or has no
  usable P-256 recipient key or origin. Everything else (another protocol name,
  padded base64url, an unexpected DeviceRequest version, a non-boolean
  `intentToRetain`, extra DocRequests, a malformed `encryptionInfo` wrapper,
  duplicate CBOR map keys) is a warning: logged, shown on the debug screen, and
  the wallet carries on.
- **Items** (§5.4): a selector problem inside one item (an unknown `kind`,
  mixed form and selection members, a malformed filter array) makes only that
  item `unsupported`. A `selection.fhir` item with no filters is answered from
  the wallet's records. A profile the wallet has no category for is matched by
  `meta.profile` and answered `unavailable` if nothing matches. Old selector
  members such as `canonical` and `resource` are ignored.
- **Versioned canonicals** (§5.5): a versioned form is fetched from its bare
  URL and used only if the Questionnaire's `url` and `version` match exactly. A
  versioned profile is fulfilled only by records whose `meta.profile` names that
  exact version.
- **Declining everything** (§5.7): after reviewing, Decline returns a normal
  response with every item `declined`. Closing the wallet without reviewing
  ends the call with an error.
- **The origin** (§8.3, [TR-2]): a browser on the privileged-caller allowlist
  reports the page's origin. A native app calling directly gets
  `android:apk-key-hash:<base64url SHA-256 of its signing certificate>`
  (`AppCallerOrigin`), which the app computes the same way for its transcript.
- **The response** (§8.4): a detached device signature, an MSO with
  `validityInfo`, and only media types the item accepts.

## Stack

- AGP 8.7.3, Kotlin 2.0.21, Java 17.
- minSdk 26, target/compileSdk 35.
- Compose BOM 2024.12.01 for the UI module.
- `androidx.credentials` and `androidx.credentials.registry-provider`
  snapshots from `https://androidx.dev/snapshots/latest/artifacts/repository`.
- BouncyCastle in `smart-checkin-mdoc` for COSE/certificate/crypto helpers.

## Layout

```text
android-wallet/
  settings.gradle
  build.gradle
  app/
    build.gradle
    src/main/
      AndroidManifest.xml
      assets/
        matcher.wasm
        demo-data/
      java/org/smarthealthit/checkin/wallet/
        HandlerActivity.kt
        DemoWalletStore.kt
        SmartQuestionnaireFetcher.kt
  smart-checkin-core/
    README.md
    src/main/java/org/smarthealthit/checkin/wallet/
      SmartModels.kt
      SmartRequest.kt
      SmartCheckinResponseFactory.kt
      QuestionnaireResponseBuilder.kt
  smart-checkin-mdoc/
    README.md
    src/main/java/org/smarthealthit/checkin/wallet/
      DirectMdocRequest.kt
      SmartHealthMdocWallet.kt
      MdocCbor.kt
      SmartMdocBase64.kt
      SmartMdocCrypto.kt
  smart-checkin-credential-manager/
    README.md
    src/main/java/org/smarthealthit/checkin/wallet/
      Registration.kt
  smart-checkin-ui-compose/
    README.md
    src/main/java/org/smarthealthit/checkin/wallet/
      MainActivity.kt
```

## Run the demo app

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

First launch:

1. Open the app.
2. Tap **Register with Credential Manager**.
3. Open the web verifier in a browser with Digital Credentials support.
4. Request SMART Health Check-in information.
5. Choose the wallet entry, review requested sharing, and accept.

The app currently registers both modern `DigitalCredential.TYPE_DIGITAL_CREDENTIAL`
and legacy `com.credman.IdentityCredential` entries by default for browser
compatibility. Use `-Pregistration-mode=modern-only` or
`-Pregistration-mode=legacy-only` to narrow registration behavior.

## Validation commands

Fast Android/JVM coverage:

```sh
./gradlew :app:testDebugUnitTest --no-daemon
```

Build smoke:

```sh
./gradlew :app:assembleDebug --no-daemon
```

Full direct-mdoc response validation:

```sh
cd ..
bash vendor/scripts/validate-android-mdoc-response.sh
```

The full validation regenerates deterministic request fixtures, has Android
emit a deterministic wallet response, opens that response with the client library's Verifier HPKE
code, inspects the decrypted `DeviceResponse`, and runs
pyMDOC-style issuer-signed byte checks.

## Debug artifacts

`HandlerActivity` writes debug bundles under:

```text
/data/data/org.smarthealthit.checkin.wallet/files/handler-runs/<runId>/
```

Bundles include the Credential Manager request, raw mdoc request/response
bytes, SMART request/response JSON, SessionTranscript, encryption info, HPKE
outputs, issuer/device signing intermediates, and sidecar hex/base64url files.

Pull and analyze the latest run:

```sh
../spec/scripts/pull-android-handler-run.sh   # in a sibling checkout of smart-health-checkin/spec
```

For HPKE-open debugging, pair the Android bundle with the Verifier console event
`@@SHC@@REQUEST_ARTIFACTS@@...`; it includes verifier request artifacts needed
for offline inspection.

## Sample data

Bundled under `app/src/main/assets/demo-data/`:

- `carin-coverage.json`: CARIN-IG Coverage resource.
- `clinical-history-bundle.json`: US Core clinical history bundle.
- `migraine-questionnaire.json`: Chronic Migraine follow-up Questionnaire.
- `migraine-autofill-values.json`: prefill values keyed by Questionnaire linkId.
- `sbc-insurance-plan.json`: Summary of Benefits and Coverage resource.

`DemoWalletStore` is demo-specific. Production apps should replace it with a
real holder data source that implements `SmartHealthWalletStore`.

## What belongs in each layer

| Concern | Home |
| --- | --- |
| Request shape validation and request-item classification | `smart-checkin-core` |
| Holder data lookup and app policy | `app` or a production wallet-store module |
| Direct mdoc CBOR/COSE/HPKE details | `smart-checkin-mdoc` |
| Android Credential Manager registry integration | `smart-checkin-credential-manager` |
| Consent UI and Questionnaire input controls | `smart-checkin-ui-compose` |
| Manifest entries, debug bundle retention, demo assets | `app` |

## Response size and delivery modes

How big a response can this wallet return through the Digital Credentials
API? It depends on which of two delivery modes the request lives in — a
private handshake inside `androidx.credentials`, not anything in the W3C,
OpenID, or ISO specs. Measured on a Pixel 11 Pro XL / Android 17 / Chrome 151
(full write-up: [`docs/research/10-android-response-size.md`](https://github.com/smart-health-checkin/spec/blob/main/docs/research/10-android-response-size.md) in the spec repo):

| Mode | When | Ceiling |
| --- | --- | --- |
| Intent-extra ("legacy") | wallet uses the deprecated two-argument `PendingIntentHandler.setGetCredentialResponse`, **or** the caller offered no large-payload receiver (Chrome < 150, other callers) | result-Intent parcel of ~514 KB passes, ~522 KB is **silently dropped** (picker stays open, the RP's promise never settles); ≥ ~1 MB the wallet crashes in `finish()`. Budget ≈ 200,000 chars of `credentialJson`. |
| Large-payload | androidx ≥ 1.7.0-alpha01, the three-argument overload (what `HandlerActivity` uses), and a caller that put `EXTRA_LARGE_PAYLOAD_RESULT_RECEIVER` in the request (Chrome ≥ 150) | bundles ≥ 200 KB go out of band as a file descriptor; no transport limit found up to 66.7 M chars. The next wall is the wallet's own heap: ≈ 20 MB of payload at the default 256 MB, ≈ 50 MB with `largeHeap`, failing cleanly with an RP-visible `NetworkError`. |

**Detecting the mode in the app.** `ResponseDelivery.describe(request)` (in
`smart-checkin-credential-manager`) reports whether the caller accepts large
payloads — it looks for a `ResultReceiver` under
`androidx.credentials.provider.EXTRA_LARGE_PAYLOAD_RESULT_RECEIVER` in the
request's option `requestData` — plus the process heap cap and a rough
`budgetChars`. `HandlerActivity` logs it for every request
(`SHCHandler: response delivery mode=…`). After
`setGetCredentialResponse`, `ResponseDelivery.wentOutOfBand(intent)` says which
way the response actually went. The sample takes no action on it yet; the
point is that a wallet *can* know, before building the response, whether it
is in a ~500 KB world or a tens-of-MB world, and could offer narrower
selections or return a pointer (a SMART Health Link) instead of bytes.

Sweep it yourself with [`tools/payload-probe/`](tools/payload-probe/README.md).
The `-Plarge-heap` build property requests the larger heap for those
experiments; the default build does not.

## Next library hardening work

- Turn `HandlerActivity` orchestration into a smaller public handler API.
- Split stable reusable Compose components from demo-only `MainActivity` code.
- Add production holder-store examples beyond `DemoWalletStore`.
- Add a server/kiosk sample that consumes the same request/response model.
- Clean app dependencies that are now provided by library modules.
