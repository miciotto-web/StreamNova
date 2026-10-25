# StreamNova V1.0.1 - Official Stable Release

StreamNova V1.0.1 follows the V1.0 stable release and ships one focused change to the in-app update flow, together with the version bump. No player, catalog, provider or extractor behaviour is touched.

## What's new in 1.0.1

- **The update check selects the APK by device ABI.** The release assets are matched against the ABIs declared by the device — `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` — in the order the system itself declares them, so a 64-bit ARM device receives the 64-bit ARM file and a 32-bit ARM device the 32-bit one. The order in which GitHub lists the assets no longer affects the result, and `app-release.apk` is no longer preferred blindly: a file that does not declare its architecture is never chosen.
- **A release without an installable APK is reported explicitly instead of being offered for download.** When the latest release carries no APK, only APKs for other architectures, or two assets declaring the same ABI, the update is not proposed and Settings explains it with a new localized message ("This release does not contain an APK installable on this device", added in Italian, English, German, Spanish and French). No download is started and no file is written to the cache.
- **The asset validated by the check is the asset that gets downloaded.** The selection happens once, during the check; the download reuses that validated asset instead of selecting again, and the declared size and SHA-256 of that same file are what the integrity verification enforces. Dismissing the update result also invalidates the asset, so a download can never start from a check that has already been discarded.
- Version bump: `versionName` `1.0.1`, `versionCode` `1003`.

## Version and compatibility

- App version: `1.0.1`
- Version code: `1003`
- Application ID: `com.aistudio.streamnova.tvapp`
- Targets Android TV and Fire TV Stick; separate APKs are built for `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`.
- All four APKs of this release are signed with the dedicated distribution key already used for the previous stable release.

## Verification

The complete automated unit test suite was re-run in full on this source state on 2026-10-25 and passed: 1,296 tests, 0 failures, and 2 skipped (two network-dependent provider checks, skipped by design). The four Release APKs were then rebuilt from this source state: they are signed with the distribution key and declare `versionName` `1.0.1` and `versionCode` `1003`, with the ABI of each file matching the name of the file.

Limits of this verification, stated explicitly: the update flow (check, asset selection, download) is covered by automated tests that run against a local HTTP server, not against a release published on GitHub, and no on-device verification of 1.0.1 has been performed. On-device verification of the update flow on a real device therefore remains open.

## Signing

This release uses a dedicated distribution signing key. Keep the keystore and its password in separate secure backups. Losing the signing key can prevent compatible updates to installations signed with that key.

---

# StreamNova V1.0 - Official Stable Release (previous release)

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

The complete automated unit test suite was re-run in full on this source state and passed: 1,271 tests, 0 failures, and 2 skipped (two network-dependent provider checks, skipped by design). On-device functional verification on Fire TV was then completed with a positive outcome, and no signed Release APK has been rebuilt from this source state.

## Seek preview on the timeline (player scrubbing)

Pressing ◀ / ▶ with the controls hidden now shows a video frame preview above the timeline, YouTube-style: a 16:9 thumbnail with cyan accent, the timestamp of the *proposed* position ("1:24 / 43:10"), a discreet loading sheen while the frame is fetched, and an elegant placeholder when the frame cannot be extracted. The overlay is decorative (no focus, no key handling), so the D-pad flow, the delayed seek commit and the BACK cancellation behave exactly as before: no seek is issued per press, only on confirmation.

### How frames are obtained

1. **Frames already provided by the source** (sprite sheets / preview metadata): none of the providers used by the app deliver them, so there was nothing to reuse.
2. **HTTP range extraction from a second decoder**: rejected after inspection. StreamNova plays many sources through the NuvioEngine proxy (TorBox / debrid) or through hoster links with session cookies and signed query strings; those endpoints do not guarantee random-access HTTP Range, and a separate decoder failing there would have to download the file from the beginning — explicitly forbidden.
3. **Chosen: a decoder of its own** (`PlayerSeekPreviewExtractor`). The extractor builds a second `ExoPlayer` on the **same** `MediaSource.Factory` and `DataSource.Factory` as the main player, so it inherits URL, HTTP headers, cookies, cross-protocol redirects, proxy, HLS/DASH and DRM exactly as the stream being played, and it renders off-screen, where the frame is read with an `ImageReader` (`YUV_420_888`) on platforms declared safe or through the GL path `SeekPreviewGlFrameGrabber` — an off-screen `SurfaceTexture` read with `glReadPixels` from an FBO — where they are not. The main player is never seeked, paused or re-prepared by the preview: the seek of the scrubbing stays the single one issued on confirmation, and the playback path gains no extra buffering. Each published frame carries the position the decoder actually reached, read from the renderer's presentation time (`VideoFrameMetadataListener`), never the requested one. The dedicated decoder is warmed up when the scrubbing opens, because its first `prepare()` was measured at about 5.7 s on the Fire TV (`mt8696`) and paying that cost inside the first capture kept the worker busy for ten presses.

### Known limitation

Reading the planes of an `Image` can abort the process inside `libmedia_jni` — a CheckJNI abort that no `catch` can intercept, observed on the Fire TV Stick 4K Max 2nd gen (`mt8696`) — so on platforms declared incompatible the extractor never creates the `ImageReader` at all and takes the GL path instead, where the buffer never leaves the GPU and no `Image`, hence no plane, is ever created. Where even the GL path cannot initialise, the source declares itself unavailable, the preview shows the placeholder and the timestamp, and scrubbing continues to work normally.

The second decoder opens a second connection to the source. Playback is paused while scrubbing, so there is no contention for bandwidth, but a hoster that limits concurrent sessions — or a DRM server — can refuse it, and the preview then honestly reports that no frame is available. There is no fallback that shows a frame of a different position: the timestamp always matches the frame on screen, and a frame the decoder lands on before the requested instant is flagged as an approximation ("≈" badge, dimmed border) instead of being labelled with the requested time.

Retrieval is driven from the player's application thread, as Media3 requires, and it delegates every expensive step — bitmap conversion, frame fingerprint, diagnostic dumps — to background threads, so the preview's cost on the player thread stays limited to the calls Media3 requires. An LRU cache capped at 24 MB avoids repeated requests, consecutive positions are deduplicated, the two adjacent steps are prefetched only while no primary request is pending, positions that are no longer current are skipped, the capture in flight is cancelled when the scrubbing closes or the source changes, and the cache and overlay are reset on episode/source change and on screen disposal.
