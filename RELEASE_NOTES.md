# StreamNova V1.0 - Official Stable Release

StreamNova V1.0 is the first stable release for Android TV and Fire TV Stick following the beta phase.

## Highlights

- Improved Android TV D-pad navigation, focus restoration after details and player screens, and fast row scrolling.
- Catalog, film and series details, synopsis localization, and Top 10 navigation improvements.
- Streaming engine, providers, extractors, source resolution, diagnostics, and player fallback improvements.
- Stremio catalog selection and live-channel support improvements.
- GitHub Releases update flow with version comparison, SHA-256 and size integrity checks, download progress, and explicit user confirmation before installation.
- Updated translations and expanded automated test coverage.

## Version and compatibility

- App version: `1.0.0`
- Version code: `1002`
- Application ID: `com.aistudio.streamnova.tvapp`
- Targets Android TV and Fire TV Stick; separate APKs are built for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`.
- Fire TV 32-bit ARM artifact: `StreamNova V1.0.apk` (`armeabi-v7a`).

## Verification

The Release build completed successfully. The `armeabi-v7a` APK signature and package metadata were verified. The GitHub update tests passed; the full unit test suite is being checked separately.

## Signing

This release uses a dedicated distribution signing key. Keep the keystore and its password in separate secure backups. Losing the signing key can prevent compatible updates to installations signed with that key.