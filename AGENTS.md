# Agent notes: android-wallet

The sample Android wallet. Released as an APK from `wallet-vX.Y.Z` tags.
[MAINTAINING.md](https://github.com/smart-health-checkin/smart-health-checkin.github.io/blob/main/MAINTAINING.md) maps every repo, what triggers what, and how to release.

- Test: `./gradlew :app:testDebugUnitTest --no-daemon`. Test tasks fetch the
  spec's fixtures at the pinned tag first (`scripts/fetch-fixtures.sh`,
  `SPEC_FIXTURES_REF`).
- Build: `./gradlew :app:assembleDebug --no-daemon`.
- **Releasing:** push tag `wallet-vX.Y.Z`; `android-release.yml` builds, signs,
  and attaches the APK. Links everywhere use `releases/latest/download/`, so
  nothing else needs updating. Never re-tag.
- Test vectors come from the client library, pinned to a release tarball in
  `package.json`: `bun install && bun run vectors`.
- connectathon's nightly Android run installs the latest release, so a broken
  release shows up there within a day.
