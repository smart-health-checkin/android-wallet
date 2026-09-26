# Agent notes: android-wallet

The sample Android wallet. Released as an APK from `wallet-vX.Y.Z` tags.
[MAINTAINING.md](https://github.com/smart-health-checkin/smart-health-checkin.github.io/blob/main/MAINTAINING.md) maps every repo, what triggers what, and how to release.

- Test: `./gradlew :app:testDebugUnitTest --no-daemon`. Test tasks fetch the
  spec's fixtures at the pinned tag first (`scripts/fetch-fixtures.sh`,
  `SPEC_FIXTURES_REF`).
- Conformance: `ConformanceTest` runs the spec's conformance cases
  (`request-json`, `request-cbor`, `transcript`, `wallet-response`; pinned by
  `SPEC_CONFORMANCE_REF` in `scripts/fetch-conformance.sh`). Credentials built
  for `wallet-response` land in `app/build/conformance-wallet/`, and CI checks
  them with `bun spec-conformance/reference/verify-wallet-output.ts
  app/build/conformance-wallet conformance/known-failures.json`.
  `conformance/known-failures.json` lists what fails today (empty as of W5); the
  runner also checks per-item `unsupported` outcomes and that every expected
  warning is reported.
- Names follow the spec: Verifier, Wallet, Holder. Not Responder, Requester,
  or RP.
- Build: `./gradlew :app:assembleDebug --no-daemon`.
- **Releasing:** push tag `wallet-vX.Y.Z`; `android-release.yml` builds, signs,
  and attaches the APK. Links everywhere use `releases/latest/download/`, so
  nothing else needs updating. Never re-tag.
- Test vectors come from the client library, pinned to a release tarball in
  `package.json`: `bun install && bun run vectors`.
- connectathon's nightly Android run installs the latest release, so a broken
  release shows up there within a day.
