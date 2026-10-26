# Six-phase delivery and acceptance

Implementation now reaches every phase's core capability. **The phases are not all signed off:** automated framework/fixture checks are distinct from provider and physical-device acceptance. The user has offered to test both Google TV Streamer and Fire TV Stick 4K Max. Results must be recorded before release qualification can close.

| Phase | Implemented in 0.3 | Open acceptance / limits |
| --- | --- | --- |
| 1. Library organization | M3U, stable source editing, conservative stream reconciliation, favorites/resume, hide/restore, custom groups and ordering; migrations from schemas 1–5 | Both remotes, large real playlists, real provider URL rotation; no ambiguous matching guesses |
| 2. Guide and refresh | Virtualized two-hour grid, earlier/later, time-zone display, strict bounded XMLTV parsing, opt-in persistent network-bound refresh | Real XMLTV matching, long standby, vendor JobScheduler policies; automatic refresh currently targets the active profile |
| 3. Catalog and catch-up | M3U plus Xtream login, standard live/VOD/series APIs, movie metadata, seasons/episodes, optional bounded artwork; explicit M3U Unix-second catch-up templates | Provider entitlement/API compatibility; unsupported catch-up schemes and DRM remain unsupported; categories capped at 20,000 |
| 4. Family and portability | Separate profile databases, parental PIN/session/lockout controls, protected catalogs/settings, user-passphrase AES-GCM library export/replace with rollback | Actual TV keyboard and lifecycle/bypass tests, document-picker availability; no PIN recovery without local-data reset |
| 5. DVR | Immediate/scheduled foreground recording, exact alarms, private storage quota, TS/HLS including AES-128, cancellation, atomic claim, reboot/partial recovery, playback and chosen-folder export | Sleep/reboot/network-loss/storage-full tests on both TVs; two-hour/2 GiB limits, one capture at a time; no direct external/NAS capture, separate HLS audio or byte-range recording |
| 6. Multiview and qualification | Two tiles, decoder advertisement check, constrained resolution, audio selection and shared provider-session budget; debug and optimized release builds | Actual decoder allocation, 4K/HDR/audio single playback, long-run memory/latency measurements and store review; tiles require ≤720p renditions |

## Release gates still open

1. Complete the hardware checklist on **both** intended devices. Passing local framework tests cannot close this gate.
2. Validate the user's actual playlist and any Xtream/XMLTV/catch-up endpoints. Cloud egress to the provided host remains denied; no live subscription behavior is certified.
3. Record failures, fix reproducible application defects, rerun affected automated and device checks, and repeat until the selected release matrix passes.
4. Review production signing, Android target/API behavior, distribution policies and privacy documentation. No production APK signing or store publication has occurred.

## Beyond this bounded implementation

Direct external/NAS recording destinations, more than two tiles, fixed high-resolution multiview, arbitrary provider-specific archive schemes, DRM, external-player delegation, richer channel/group editing, global TV search, remote synchronization and advanced display-mode controls are not implemented. This build is not complete TiviMate/Apollo feature parity and no comparative performance/security claim has been benchmarked.
