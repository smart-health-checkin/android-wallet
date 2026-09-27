# Agent notes: android-wallet

The reference Android wallet. Released as an APK from `vX.Y.Z` tags.
[MAINTAINING.md](https://github.com/smart-health-checkin/smart-health-checkin.github.io/blob/main/MAINTAINING.md) maps every repo, what triggers what, and how to release.

- Test: `./gradlew test -Pskip-matcher --no-daemon` (what CI runs). Test tasks
  fetch the spec's fixtures and conformance cases at the pinned tag first
  (`scripts/fetch-spec.sh`, `SPEC_REF`; `SPEC_DIR=../spec` uses a local checkout).
  `-Pskip-matcher` keeps the checked-in `app/src/main/assets/matcher.wasm`;
  without it Gradle rebuilds the matcher from `app/matcher-rs/`, which needs
  nightly Rust with the `wasm32-unknown-unknown` target and `wasm-opt`.
- Conformance: `ConformanceTest` runs the spec's conformance cases
  (`request-json`, `request-cbor`, `transcript`, `wallet-response`). Credentials built
  for `wallet-response` land in `app/build/conformance-wallet/`, and CI checks
  them with `bun spec-conformance/reference/verify-wallet-output.ts
  app/build/conformance-wallet conformance/known-failures.json`.
  `conformance/known-failures.json` lists what fails today (empty); the
  runner also checks per-item `unsupported` outcomes and that every expected
  warning is reported.
- Names follow the spec: Verifier, Wallet, Holder. Not Responder, Requester,
  or RP.
- Build: `./gradlew :app:assembleDebug :verifier-app:assembleDebug -Pskip-matcher --no-daemon`.
- **Releasing:** push tag `vX.Y.Z`; `android-release.yml` builds, signs,
  and attaches both APKs (the wallet and `verifier-app`). Links everywhere use `releases/latest/download/`, so
  nothing else needs updating. Never re-tag.
- Test vectors come from the client library, pinned to a release tarball in
  `package.json`: `bun install && bun run vectors`.
- connectathon's nightly Android run installs the latest release, so a broken
  release shows up there within a day.
- `verifier-app/` is the example native Verifier: the direct Credential Manager
  path and the browser path through the client's bridge page. Its end-to-end
  test is `tools/verifier-app-e2e/run.ts` (local only; needs an emulator with
  Chrome; runs the direct, small, large, repeat, and mixed cases; repeat and
  mixed run several check-ins in one app process). Its package
  (`org.smarthealthit.checkin.verifier`) and signing certificate are listed in
  the apex's `/.well-known/assetlinks.json`; change that file if either changes.
- Signing: every release and `verifier-app` use the shared development key
  from the `ANDROID_DEBUG_KEYSTORE_B64` secret (the builds are debuggable).
  versionCode comes from the version
  (`0.4.1` -> 4001), so versions must only grow. The APKs are
  `smart-health-checkin-wallet.apk` and
  `smart-health-checkin-verifier.apk`.
- App callers' origin is `android:apk-key-hash:<base64url SHA-256 of the signing
  cert>` ([spec TR-2](https://smart-health-checkin.org/spec/#TR-2)); browser callers' origin comes from `getOrigin` with the
  privileged-caller allowlist. That origin is for the transcript only. The consent
  screen says "A website is asking for your health information" with the origin for
  a browser caller, and "An app is asking for your health information" for an app
  caller, never naming the app (its package name and origin are under Technical
  details) and never calling the requester a practice or clinic. The e2e `direct`
  case checks that heading (test tag `consent-heading`).
