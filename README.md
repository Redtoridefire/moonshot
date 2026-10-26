# Moonshot TV · 0.3.0

An independent native Android TV IPTV player for Google TV Streamer and Fire TV Stick 4K Max, with an original dark interface, mint remote focus rings and device-local libraries. One universal APK targets Android 8.0/API 26 and later. No Google Play services, subscription, content, ads or analytics account is bundled.

**Development build.** Automated framework/fixture tests pass as recorded in [validation](docs/VALIDATION.md); physical-device and live-provider acceptance remain open. This is not a claim of complete TiviMate/Apollo feature parity or measured superiority in speed, memory or security.

## Download APK

Open [GitHub Releases](https://github.com/Redtoridefire/moonshot/releases), choose the newest **development build**, expand **Assets**, and download **moonshot-test.apk**. The unsigned release APK needs signing before it can be installed. Releases are created after a successful APK build, unit tests and lint on `main`; a failed build produces no release. The separate TV emulator result is linked from the release and does not certify physical devices.

Every successful `main` build publishes APKs, a matching source archive, validation reports, build metadata and `SHA256SUMS`. They are also available for 90 days under **Actions → Android → run → Artifacts → moonshot-development-build**. Source, tests, scripts and documentation live in Git; generated binaries and reports are release assets rather than Git history. Local SDKs, build caches, private credentials and signing keys are excluded.

CI development APKs currently use a new debug signing key per runner, so an update from another build can fail with a signature mismatch. Uninstalling deletes app data; export a library backup first where supported. A protected, stable signing key is required for seamless updates and production distribution.

## Install and connect

The development-signed test APK is `app/build/outputs/apk/debug/app-debug.apk`. Sideload with an authorized installer or `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Keep the same signing key to update an earlier development build without losing device-local data. Production releases need your own protected signing identity; the optimized `assembleRelease` APK is unsigned.

Open **Moonshot → Settings**, then choose either **Add M3U playlist** or **Add Xtream-compatible login**. Enter credentials on the TV; no private URL is embedded in the APK or source. HTTPS is the default. Explicitly allow HTTP only when a trusted provider requires it; HTTP exposes access details and requests to the network.

- Use **Live TV / Movies / Series**, groups, custom groups and search to browse the local stream library. Up to 5,000 matching streams are shown; narrow the group/search when needed.
- Hold OK on a stream for favorites, hide, move earlier/later, custom group assignment, recording or multiview. Hidden items can be restored in Settings. Settings also offers ordering reset and playlist name/address editing.
- **Program guide** shows a two-hour time grid. Import XMLTV in Settings or use your provider's XMLTV URL. Channel IDs must match `tvg-id`. Browse earlier/later, choose a display time zone, select a program for details, supported catch-up or scheduled recording.
- **Settings → Xtream catalog** provides live/movie/series categories, movie details, season selection and episodes through standard compatible APIs. Selected streams retain local resume/favorite state. Account APIs are not available for arbitrary M3U-only providers. Provider artwork is an explicit opt-in; images are bounded and downsampled.
- In single playback, OK shows controls, Menu opens audio/subtitles, and Back first closes controls. Up/down changes live channels within the current group while controls are hidden. Media3 handles HLS, DASH and supported progressive formats; device codecs determine actual support.

## Refresh, profiles and portable libraries

**Automatic refresh** is opt-in, requested roughly every six hours through Android's persistent, network-constrained JobScheduler. Device standby and OS policies can delay or stop work. The current implementation refreshes the active profile's sources and saved guide; other profiles can be refreshed manually. Failed replacements roll back. Editing a source preserves its identity. Unique metadata matches preserve channel state after stream URL changes; ambiguous duplicates do not inherit state.

**Profiles** have separate libraries. Create them in Settings; choose them with the Profiles button. Set a parental PIN of 6–12 digits before creating an unrestricted-to-open family profile. Protected profiles and settings require the PIN; provider catalogs require parental authorization. Family profiles can watch their own library without the PIN. This is library separation, not automatic adult-content classification. Hidden channels are not parental locks. PIN sessions last five minutes; re-entry is checked on activity resume and sensitive saves. Five incorrect attempts cause a 30-second lockout, using monotonic time within a boot. Forgetting the PIN requires clearing app data, which removes all local libraries and recordings.

**Export / restore encrypted library** uses a user-held passphrase of 12–256 characters, PBKDF2-HMAC-SHA256 and AES-GCM. It covers the selected profile's sources, streams, favorites, groups, hidden state and resume positions. A successful restore replaces that profile's library. Wrong passwords, tampering and invalid rows roll back. Guide caches, PINs, other profiles and recordings are excluded. Maximum encrypted/plaintext backup size is 32 MiB. A system document picker is required; some Fire TV builds do not include one.

## Recording and multiview

**Recording** supports direct 188-byte MPEG-TS and a strict HLS subset: TS/fMP4 segments, AES-128 identity encryption, initialization maps and discontinuities. Separate HLS audio/video renditions, byte-range HLS, SAMPLE-AES/DRM and arbitrary progressive VOD recording are unsupported. External subtitle renditions are not captured. Unsupported streams fail or leave a clearly marked partial capture; no encryption key or provider URL is written into a saved local playlist.

One recording runs at a time, up to two hours each and **2 GiB total private recording storage**. Recording uses a foreground service and a bounded wake lock. Schedule from future guide programs; Android 12+ requires exact-alarm access. Android 15+ foreground data-sync time quotas also apply. Reboot recovery re-arms future schedules, marks missed starts and recovers available partial files. Force-stopping the app can suppress Android alarms; reopening reconciles remaining schedules. None of this guarantees recording through every vendor power-management condition.

Manage playback, stop/cancel, deletion and export in **Settings → Recordings**. Capture initially uses private app storage. Completed/partial recordings can be archived to a folder chosen through the system picker. Keep the app open during export; interrupted exports may leave an incomplete folder. HLS archives need a player that can read the exported playlist together with its media files. Direct capture to external/NAS storage is not implemented.

**Multiview** displays two live channels, subject to an advertised two-instance AVC decoder check. Tiles prefer/require renditions at or below 720p; fixed higher-resolution streams may fail. Menu or Listen changes the audible tile. This is not a promise of two simultaneous 4K decodes.

Set **Provider connection limit** to the limit your subscription actually permits. Default is one. Playback, DVR and multiview share an app-owned session budget; the app cannot count other devices or other IPTV apps. A scheduled recording can report `busy` when the budget is already occupied.

## Build and checks

Use JDK 17, Android SDK platform 36 and build tools 35.0.0. Gradle 8.13 has a distribution checksum pinned in the wrapper. Android Gradle Plugin is 8.13.2; Media3 is 1.11.1; networking uses OkHttp 5.4.0. Resolved app dependencies are locked in `app/gradle.lockfile`. Target SDK is 35 pending Android 16 behavior qualification.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
# Destructive fixture suite: dedicated, isolated Android TV emulator only.
# Do not use a personal TV or an environment containing subscription data.
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.moonshot.resetTestData=true
```

Robolectric's API 28 framework JAR is resolved through Gradle into the build directory; tests use it offline and do not depend on a writable user home. The tests cover network policy, parsing, database migrations, refresh identity, source editing, profiles/PIN controls, authenticated backup, provider API fixtures, HLS/AES capture, quota/session admission and a foreground recording workflow. They do not substitute for native decoding, real power management, 4K/HDR/audio or remote tests.

In this cloud checkout, `source scripts/cloud-env.sh` activates retained tools. `bash scripts/cloud-setup.sh` reproduces prerequisite setup and build checks. Each cloud task is already isolated: use the existing checkout rather than creating a Git worktree. Live emulator processes do not survive snapshots.

See [hardware checklist](docs/HARDWARE_CHECKLIST.md), [roadmap/status](docs/ROADMAP.md), [security model](docs/SECURITY.md), [validation evidence](docs/VALIDATION.md) and [release signing](docs/RELEASING.md). Moonshot is unaffiliated with TiviMate, Apollo, Google or Amazon; use streams you are authorized to access.
