# smart-checkin-ui-compose

`smart-checkin-ui-compose` holds the wallet's Compose screens. It works with
the models from `smart-checkin-core` and calls the registration adapter in
`smart-checkin-credential-manager`; request parsing, encryption, and
holder-data lookup live in the other modules.

## What this module contains

- `MainActivity`: the launcher's home screen. It registers the wallet with
  Credential Manager and shows the result, lets the holder choose the
  reference patient, and imports and browses health records.
- `DemoApp`: the holder review screen that `HandlerActivity` shows for each
  request. It lists the requested items and the matching records, renders
  Questionnaire items as input controls, and ends with Share or Decline.
- `WalletTheme`: `SmartTheme` from [`smart-checkin-theme`](../smart-checkin-theme/README.md)
  (the site's colors in light and dark, following the system setting, and
  Inter), with Compose test tags exposed as resource ids for UI automation.

The controls that automation drives have test tags: `share-selected` and
`decline` on the review screen, `question` on each form question's label,
`patient-aria` and `patient-large` on the home screen. Tests match these
instead of the wording.

## Registration on the home screen

The launcher screen calls:

```kotlin
registration = when (val r = Registration.register(this@MainActivity)) {
    is RegistrationResult.Success -> RegistrationState.Registered(
        matcherBytes = r.matcherBytes,
        credentialsBytes = r.credentialsBytes,
        mode = r.mode,
        registeredTypes = r.registeredTypes,
    )
    is RegistrationResult.Failure -> RegistrationState.Failed(r.message)
}
```

The screen presents the status; the registration module makes the
registry-provider calls.

## The review screen's model

The review screen reads `VerifiedRequest` and `RequestItem` from
`smart-checkin-core`:

```kotlin
data class VerifiedRequest(
    val requestId: String,
    val verifierOrigin: String,
    val rawSmartRequestJson: String,
    val readerAuth: ReaderAuthVerification,
    val items: List<RequestItem>,
)
```

Each `RequestItem` has a title and subtitle, a `RequestKind`, the item's
metadata, and the media types it accepts, so the screen can describe the
request without knowing how it was carried over mdoc.

The Verifier's origin shown on the screen is what the browser or Android
reported; it does not by itself establish who the Verifier or its
organization is. Keep the origin, readerAuth status, and the holder's choices
as separate facts on the screen.

## Dependencies

The module depends on `smart-checkin-core`, `smart-checkin-mdoc`,
`smart-checkin-credential-manager`, `smart-checkin-theme`, and the Compose,
Material 3, and lifecycle libraries.
