# smart-checkin-credential-manager

`smart-checkin-credential-manager` is the Android registration adapter. It
registers this wallet with Android Credential Manager / registry-provider so
the wallet can appear in the system picker for Digital Credentials requests.

This module does not parse mdoc requests, build SMART responses, or render
holder review UI. It only owns registration of the wallet entry and matcher bytes.

## Key APIs

| API | Purpose |
| --- | --- |
| `Registration.register(context)` | Clears existing registry records for this app and registers the SMART Health Check-in credential entry. |
| `Registration.PROTOCOL` | Active protocol string: `org-iso-mdoc`. |
| `Registration.REGISTRATION_ID` | Stable registration ID for the modern digital credential entry. |
| `RegistrationResult.Success` | Matcher bytes, credentials blob bytes, registration mode, and registered types. |
| `RegistrationResult.Failure` | Failure message suitable for UI/logging. |

Example:

```kotlin
lifecycleScope.launch {
    when (val result = Registration.register(this@MainActivity)) {
        is RegistrationResult.Success -> {
            // Show registered status.
        }
        is RegistrationResult.Failure -> {
            // Surface result.message to the user or logs.
        }
    }
}
```

### `ResponseDelivery`

Tells a provider activity how its response will reach the caller.
`ResponseDelivery.describe(request)` → `callerAcceptsLargePayloads` (the caller
put a large-payload `ResultReceiver` in the request, so androidx can hand a
large response over as a file) and `heapMaxMB`.
`ResponseDelivery.wentOutOfBand(intent)` after `setGetCredentialResponse`.
Always call the three-argument `PendingIntentHandler.setGetCredentialResponse(intent, response, request)`,
so responses of any size get through; the two-argument overload is deprecated.
See [Returning large responses](../README.md#returning-large-responses) in the root README.

## What registration carries

The registry entry includes:

- `matcher.wasm`, built from `app/matcher-rs/` and copied into the
  app assets;
- a small JSON credentials blob describing one SMART Health Check-in credential:
  title, subtitle, doctype, namespace, response element, and package name.

The matcher reads the credentials blob and the incoming request bytes to decide
whether this wallet can handle the request. For this profile, it looks for the
SMART Health Check-in mdoc doctype/request markers and emits a wallet entry.

## Registered types

The wallet registers two entries: `DigitalCredential.TYPE_DIGITAL_CREDENTIAL`
and `com.credman.IdentityCredential`, so browsers that look for either type
find it.

## Dependency rules

This module depends on AndroidX Credential Manager and registry-provider
snapshots. It should not depend on:

- `smart-checkin-mdoc`;
- Compose UI;
- demo wallet data;
- response generation.

Keeping registration narrow avoids a bad dependency direction where transport
or domain logic would need to know about Android registry details.
