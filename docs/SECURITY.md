# Security model and reviewed boundaries

## Private access details

The APK and repository contain no user subscription URL. Playlist, stream and catch-up template addresses are encrypted at rest using random-IV AES-GCM with a non-exportable Android Keystore key. Key hardware backing depends on the TV. Channel/source IDs and URL lookup fingerprints use SHA-256; these are not a substitute for full database encryption and can expose correlations or act as an offline guess oracle if an attacker already extracts the database and knows candidate credentials. Metadata such as names, groups, guide listings, favorites, positions and recording titles is local plaintext. Rooted/compromised devices and unauthorized OS-level access are outside the app's protection.

No analytics, ads, cloud account or automatic remote artwork requests exist. Optional provider artwork is off until enabled; it may contact third-party hosts supplied in provider metadata. Images have bounded downloads, dimension checks and downsampling. Provider/API/guide text is rendered as native text, not executable HTML. Import only trusted playlists: their media URLs may address arbitrary HTTP(S) hosts, including local-network hosts.

## Transport and cancellation

Networking uses verified platform TLS through OkHttp 5.4.0 (compatible with compile SDK 36); no permissive trust manager, hostname bypass, checksum bypass or certificate pin replacement is installed. An actual local self-signed-server test verifies certificate rejection. Playlist/guide/catalog redirects are bounded and every hop is checked against the HTTP opt-in. Playback uses Media3's OkHttp data source plus a protocol guard; cross-protocol playback redirects are disabled. The manifest permits cleartext only so the explicit HTTP compatibility option can work; HTTP is blocked by runtime guards by default. Enabling it exposes requests and credentials to the network.

Response streams own their cancellable calls. DVR stop cancels the connected response rather than waiting for a blocking `HttpURLConnection` read timeout. A stalled-response regression test verifies this. A connection still being established can take up to the configured connect timeout to unwind.

The app uses generic user-facing errors and does not log raw requests. Playback data-source exceptions are sanitized. Do not enable verbose third-party network/player diagnostics with real credentials; platform or dependency diagnostics are not a blanket no-logging guarantee. Credential-entry and application windows use screenshot protection before display. This is not protection against a compromised OS.

## Profiles and PIN

Profiles use separate databases. Protected profiles and parental settings require the master PIN; provider catalogs also require parental authorization. A family profile opens its own library without the PIN. Profiles are not automatic adult-content filtering, and hidden channels are organizational preferences, not locks.

PIN records use random salts and PBKDF2-HMAC-SHA256 with 210,000 iterations. Sessions expire after five minutes, with resume and sensitive-save checks. Five failed attempts lock entry for 30 seconds; a monotonic within-boot deadline prevents changing the wall clock from bypassing that lockout. Verification and PIN replacement are serialized. Internal activities/services are not exported except the permission-protected JobService and necessary boot/launcher entry points. Lost PIN recovery requires clearing app data; a device owner can always clear/uninstall the app, removing local data.

## Backup and restore

Automatic Android/cloud/device-transfer backups are disabled. Explicit selected-profile exports use a user-held passphrase, 310,000-iteration PBKDF2-HMAC-SHA256 and AES-GCM, with random salt/IV and an authenticated format header. Keystore keys, the PIN, other profiles, guide caches and recordings are excluded. Exported URLs are decrypted only in memory and written through the authenticated encryption stream. Restores re-encrypt addresses with the destination device's key.

Restore is transactional and does not mark success until JSON is validated and the authenticated stream reaches EOF. Wrong passwords, corrupted tags, unknown/missing columns, invalid references and oversized input roll back. Input is capped at 32 MiB; export reserves header/tag overhead within that cap. Untrusted plaintext source URLs are not staged in a file. The system document picker must be available; unsupported TV picker behavior is an acceptance blocker, not a passed feature.

## Untrusted input and state

M3U parsing stops oversized lines before allocating an entire response line, caps metadata and entries, and restricts stream schemes to HTTP(S). Guide parsing uses SAX without external entities, rejects DTDs, bounds XML tokens/text, rejects impossible timestamps, and retains a bounded past/future window. Regression tests include XML comments containing apostrophes, which must not change attribute-quote state. API responses, category sizes, image bytes and backup rows are bounded.

Source and stream refreshes use transactions. Exact URLs take priority over metadata matching across the full incoming playlist. Only unique unmatched metadata inherits state. Reappearing old stream/playlist URLs cannot overwrite rotated identities. Source edits retain source identity and duplicate-address edits fail safely. Catalog-selected episodes survive M3U refresh; cached catalog URLs can still become invalid when provider credentials or endpoint structure change, requiring re-selection from the catalog.

## DVR, exports and resource limits

Recorded video is intentionally not encrypted; it is stored privately under app-owned generated filenames. Local HLS manifests contain local media references, not provider URLs or AES keys. Only authorized AES-128 identity HLS encryption is supported; SAMPLE-AES/DRM is rejected. Direct TS, HLS initialization maps and segment sizes are checked/bounded. A single capture, two-hour duration, 2 GiB quota, free-space reserve and shared app-owned provider-session budget limit resource use. Other apps/devices cannot be counted against that budget.

Capture claims are conditional to avoid double start/cancel/delete races. Foreground service deadlines, wake-lock release, stop cancellation, reboot schedule recovery and partial files are handled, but vendor background policies and provider availability still require device testing. Android 15+ data-sync foreground time limits apply. Explicit file exports leave the private sandbox and are visible through the chosen storage provider; interrupted exports can leave partial archives. Direct external/NAS capture is not implemented.

This is reviewed development code, not an independent security audit or a measured comparison with another player. Production signing, real-provider acceptance and both-device validation remain required.
