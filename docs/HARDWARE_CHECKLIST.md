# Acceptance on Google TV Streamer and Fire TV Stick 4K Max

Run this independently on both devices. Use only your subscription and streams you are authorized to access. Keep private URLs, passwords and PINs out of screenshots, logs and results. Record device model/generation, OS version, APK version, provider access type (M3U/Xtream), network type and the **permitted** connection count. Close other IPTV players during controlled tests.

Use `PASS`, `FAIL`, `BLOCKED` or `NOT RUN`; include the exact steps and visible result for a failure. A missing provider capability is blocked, not a pass. Test builds use a development signing identity; install 0.3 over earlier test builds only when the signature matches. Do not clear app data unless you intend to remove all local libraries and recordings.

| ID | Check | Expected result |
| --- | --- | --- |
| TV01 | Install/update; launch from the TV launcher | Recognizable Moonshot entry; update preserves old data |
| TV02 | Navigate every main screen with original remote; Back/Home/resume | Visible focus, reachable controls, no touch requirement or audio continuing after Home |
| LIB01 | Import valid M3U; reject an invalid URL/response; refresh valid source | Useful failure; previous library/favorites retained after failed replacements |
| LIB02 | Favorite, hide/restore, reorder, custom group; relaunch and refresh | Personal state persists; groups accessible through group chooser |
| LIB03 | Edit playlist name/address using a valid endpoint | Source identity and unambiguous stream state retained; no duplicate-source overwrite |
| EPG01 | Import matching XMLTV; browse earlier/later and change display zone | Correct current/upcoming programs; no time shift of the underlying schedule |
| EPG02 | Enable refresh; leave device idle and later inspect library/guide | OS-permitted refresh works; delayed jobs are distinguished from failed imports |
| API01 | Add Xtream-compatible login, when supplied by provider | Valid account imports; wrong credentials fail without exposing them |
| API02 | Browse categories, movie details, seasons and episodes | Correct stream selected; episode resume persists; API absence marked blocked |
| API03 | Keep artwork off, then explicitly enable and inspect a title | No image requests by default; supported artwork loads without blocking playback |
| PLAY01 | Play live HLS/TS and VOD; test pause/seek/resume where available | Video/audio advance; lifecycle and retry work |
| PLAY02 | Change audio/subtitles; exercise Menu and live channel up/down | Correct track and channel selected; one audible stream |
| PLAY03 | Test available 4K/HDR and surround-audio streams in single playback | Report actual video/audio/display behavior; do not infer support from an open stream |
| ARC01 | Choose a past program with a supported explicit archive template | Correct program plays; expired/future/unsupported archive cases are unavailable |
| FAM01 | Set PIN; five wrong attempts, wait lockout, enter correct PIN | Lockout persists; correct PIN succeeds after expiry |
| FAM02 | Create family profile; add only permitted content with parental PIN | Separate library; Settings and catalog access require parental authorization |
| FAM03 | Return Home/background for over five minutes; reopen protected profile | PIN gate appears; cancel returns to launcher without playback |
| BAK01 | Export a selected-profile library; restore it into another test profile | State restores; PIN, other profiles, guide caches and recordings are unaffected |
| BAK02 | Attempt restore with wrong passphrase | Existing library remains unchanged |
| BAK03 | Check system document/folder picker availability | Record unsupported picker as BLOCKED; do not claim export passed |
| DVR01 | Record 15 minutes of supported live TS/HLS, then play it | File is usable; stop/cancel is responsive; status reflects partial/failed capture honestly |
| DVR02 | Schedule a program ≤2 hours; grant exact-alarm access when requested | Starts at scheduled time when OS/provider permit; status updates |
| DVR03 | Repeat schedule through normal display sleep and a normal device reboot | Future schedule re-arms; missed starts are marked; interrupted files can be recovered |
| DVR04 | Interrupt network during capture; stop and inspect partial capture | No credential-bearing playlist/key files; completed media remains usable where possible |
| DVR05 | Approach the app quota using legitimate recordings; delete old captures | Capture stops at quota/free-space guard; deletion frees space; existing recordings retained |
| DVR06 | Export finished capture to a chosen folder; keep app open | Archive contains capture/media files, without provider URLs or encryption keys |
| MV01 | Set the actual allowed connection count; select two low-resolution/ABR channels | Two usable tiles and controllable audio, or a clear decoder/format refusal |
| MV02 | Attempt multiview while budget is occupied; then release a session | Refuses excess app-owned sessions; capacity becomes available after stop |
| SOAK01 | Watch for 60–120 minutes; repeatedly change channels and background/resume | Report stalls, crashes, temperature or focus loss; verify old audio/streams stop |

## Send results

A short table is enough: `Device | Test ID | Result | Steps / observed behavior`. Include OS version and APK version once per device. Never include subscription links or secret values. Re-run failed checks after the corresponding fix. These results are required to close physical-device acceptance; the cloud instance cannot perform them on your TVs.
