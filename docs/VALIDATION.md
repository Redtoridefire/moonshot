# Validation evidence · 0.4.0 development candidate

## Current-instance automated results

The final full run of `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease` completed successfully.

**77 tests executed; 0 failures; 0 errors; 0 skipped.** These are JVM tests, Android-framework tests through Robolectric API 28, and real loopback/file/crypto fixtures. They are not Android instrumentation tests or physical-device acceptance.

Coverage includes:

- M3U metadata, malformed input, scheme checks, bounded line allocation and catch-up template eligibility/substitution.
- Network redirects, gzip decoding, HTTP opt-in, generic errors, real self-signed TLS rejection and the Media3/OkHttp data-source bridge.
- Every prior library schema (1–5) upgrading to schema 6 without dropping tested user state.
- Failed refresh rollback, exact/ambiguous identity matching, URL rotation/reappearance, source editing/reappearance, hidden/favorite/resume state, ordering and custom groups.
- Strict XMLTV dates, DTD rejection, retained-guide rollback and comments containing apostrophes without corrupting XML quote state.
- Xtream ID priority, query/path credential encoding, nested episode metadata, season ordering and cached episode persistence across M3U refresh.
- Separate profile databases, PIN hashing/lockout, expired authorization rejection and same-boot wall-clock bypass prevention.
- Authenticated backup round-trip, wrong-password/tamper rejection and transactional restore state retention.
- HLS parsing and variant selection; AES-128 fixture decryption; local capture manifests without provider URLs/keys; direct TS and quota checks.
- A genuinely stalled HTTP capture that stays blocked before cancellation and completes promptly after explicit cancellation.
- Foreground recording workflow with generated packet fixtures, partial state, budget release, atomic claim/cancel/delete behavior and boot schedule reconciliation.
- Persistent network-bound refresh scheduling/cancellation, app-owned connection budget, basic UI entry points, screenshot protection and guide time-zone selection.

Framework and JVM test classes run in separate processes to avoid global security-provider contamination. UI readiness checks wait for their actual condition rather than assuming a fixed short startup delay. Robolectric's pinned framework artifact is resolved through Gradle, then used offline from the build directory.

## Build and reproducibility checks

- Debug APK, instrumentation APK and optimized unsigned release APK built successfully.
- Lint completed with **no errors**. Warnings remain for intentional TV landscape orientation, English-only text formatting, synchronous security-state persistence, target SDK qualification and dependency/tool recommendations; these were not hidden by a baseline or blanket suppression.
- The setup script was exercised repeatedly; final dependency resolution is recorded in `app/gradle.lockfile`. Setup does not rewrite that lockfile.
- Gradle distribution SHA-256 remains pinned. Dependency downloads use verified TLS. No artifact, certificate or package verification was disabled.
- CI actions are pinned to commit SHAs verified from their official repositories. At the time of local validation, GitHub Actions had not run. The included workflow runs on push; its remote results must be checked separately.
- APK development-signature verification and private-subscription scanning are performed when packaging the artifact; see the accompanying artifact verification JSON/checksum. Version 0.4 was visually inspected using actual Android layouts rendered with synthetic fixtures (docs/images); this does not certify physical-TV rendering.

## Checks that remain blocked / unrun

- `./gradlew connectedDebugAndroidTest` was attempted again and failed with **No connected devices!**. The earlier software TV emulator remained unauthorized and this machine has no KVM. This cloud attempt did not execute the instrumentation suite. Separately, user-supplied GitHub run 1 logs show nine emulator tests executed with one failure in `browsesFavoritesWithRemote`; the abbreviated log did not identify its assertion line. The fixture’s conflicting screenshot assertion was replaced with focus/filter/protection checks; remote rerun confirmation remains required.
- The provider host was retried read-only and remains denied by the cloud proxy with CONNECT HTTP 403. Its requested domain addition is saved in the environment draft, not applied to this instance. No real M3U, Xtream, XMLTV, catch-up or subscription playback is certified.
- Google TV Streamer and Fire TV Stick 4K Max hardware testing has not occurred here. The user has offered both devices; `HARDWARE_CHECKLIST.md` defines the required results.
- Native video/audio decoding, 4K/HDR/surround behavior, original-remotes, document-picker availability, real sleep/reboot/exact-alarm policies, long captures, storage exhaustion and memory/latency comparisons remain open.
- The release APK is unsigned. Production signing, store review/publication and fresh-task environment restoration have not been validated.

The 0.4 candidate implements core functionality across the six roadmap areas. **It is not production-ready, complete feature parity, or a completed six-phase acceptance claim.** Read ROADMAP.md for capability limits and outstanding gates. This report records local validation; publishing the source does not close the remaining acceptance gates.

The instrumentation fixtures now require both emulator hardware identification and the explicit `moonshot.resetTestData=true` argument before touching app data. CI passes the flag only to its dedicated AVD. Use the manual hardware checklist on personal TVs; the destructive fixture suite is not permitted there.
