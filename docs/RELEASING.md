# Build, sideload and release

`./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` builds a development-signed test APK and an optimized unsigned release APK. Test APK location: `app/build/outputs/apk/debug/app-debug.apk`. The release APK has no signing identity and cannot be installed until signed.

For production, generate and back up an Android signing key using Android Studio's **Generate Signed Bundle / APK** flow. Keep it outside the checkout and use the same key for all updates. Do not use a debug key for store releases. For a CLI workflow, build the release, align the unsigned APK with your SDK's `zipalign`, then sign with `apksigner sign --ks /secure/path/moonshot.jks --out moonshot-release.apk aligned.apk`. Let the tool prompt securely for passwords; do not put them in shell commands, source or CI logs. Verify with `apksigner verify --verbose moonshot-release.apk`. APK Signature Scheme v2+ is supported on both intended devices. APKs contain no native ABI-specific player libraries, so one artifact covers the intended ARM devices.

Install via a trusted local transfer or an authorized ADB connection. Fire TV permits sideloading after allowing your installer or ADB debugging in device settings. Google TV can install from a permitted installer or a paired debugging connection. Never bundle your subscription URL in a publicly shared APK.

Before publishing:

- Test the real provider's live HLS/TS, audio variants, subtitles, channel changes, VOD seek/resume, reconnects, expired access, and provider connection limits.
- Test focus navigation using each original remote, TV launcher icon/banner, Back/Home, audio focus, suspend/resume, and network loss.
- Measure startup time, APK size, memory, decoding behavior, 4K/HDR and audio passthrough on Google TV Streamer and Fire TV Stick 4K Max; do not infer these from emulator playback.
- Run the automated suite and verify no credentials or private content are packaged.
- Review content rights, store policies, signing and privacy documentation for the actual distribution channel.

A development-signed build cannot be updated in place by a release APK signed with a different key. Uninstalling removes the local library; re-add playlists afterward. Passphrase-encrypted library backups can transfer supported library data, including provider addresses, where a document picker is available; they do not transfer recordings, PINs or the device Keystore.

## 0.3 candidate acceptance

Run docs/HARDWARE_CHECKLIST.md on both intended TVs before closing the six-phase acceptance gates. The current automated suite uses framework/fixture coverage; it has not certified native decoders or vendor foreground-service/alarm behavior. Review DVR format/storage limits, two-tile resolution admission, system picker availability and the documented security boundaries. SHA-256 lookup fingerprints are not keyed, and this is not full database encryption or a defense against database extraction/root.

The artifact bundle includes a development-signed test APK, source archive, checksum and verification JSON. The optimized release APK remains unsigned. CI dependencies/actions are pinned; update them deliberately and regenerate dependency locks when authorized dependency changes are made. Never treat a successful build or saved environment draft as store publication.

## GitHub download automation

The Android workflow installs checksum-verified command-line tools 12 and explicit SDK packages through `scripts/ci-android-setup.sh`. This avoids the retired `tools` package requested by the previous setup action. Java 17, platform 36 and build tools 35.0.0 match the local toolchain.

After unit tests, lint and both APK builds succeed, `scripts/package-development.sh` verifies the test APK signature and packages source at the exact commit, reports, metadata and checksums. The `moonshot-development-build` Actions artifact is retained for 90 days. On `main`, a separate job with `contents: write` publishes an immutable prerelease named `dev-<run>-<attempt>` with those assets. Pull requests never publish releases. Manual **Run workflow** is supported. These are development downloads, not store releases or physical-device qualification. TV emulator smoke testing remains a separate visible job; APK publication does not imply it passed.

All source/configuration is committed. SDKs, caches, signing keys, subscription data and generated binaries stay out of Git; distributable build outputs are GitHub release assets. CI currently generates debug signing identities per runner: preserve/export data before switching builds. Configure a protected persistent signing key separately before promising in-place updates.
