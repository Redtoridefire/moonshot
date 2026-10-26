# Review findings and corrections in the 0.3 implementation

These are concrete defects or gaps found while implementing and testing, not a claim that no other defects remain.

- **URL rotation and duplicates:** exact matches must be resolved before metadata inheritance. Added whole-playlist reconciliation and duplicate regression cases.
- **Reappearing original URLs:** old deterministic stream/source IDs could collide with an identity retained after editing. New identities avoid occupied IDs; regression tests cover both channel and playlist reappearance.
- **Schema upgrades:** exercised each historical schema (1–5), not only an empty database. New-install and upgrade schemas are both checked.
- **Credential-window timing:** screenshot protection moved before `show`, avoiding a first-display timing gap. Framework UI checks verify the flag.
- **PIN authorization:** configuration saves and catalog resumes re-check authorization; expired sessions cannot replace the PIN. Serialized verification and monotonic lockouts prevent concurrent-attempt and wall-clock bypasses within the app boundary.
- **Backup integrity:** restore remains transactional through EOF/tag verification; malformed/tampered imports retain existing data. Export size reserves the 49-byte authenticated-header/tag overhead.
- **XML correctness/security:** strict timestamp parsing, no DTD/external entities, streamed text/token limits. A raw-token guard was corrected to handle comments/CDATA/processing instructions without interpreting their apostrophes as attribute delimiters.
- **Catalog IDs:** a trailing `category_id` must not overwrite `stream_id`/episode identity. Added parser and API fixture checks, including season ordering and credential encoding.
- **Refresh lifecycle:** each OS job owns its cancellation state/executor so a stopped old run cannot complete/shut down a newer run.
- **DVR concurrency:** conditional recording claims and state-aware cancellation/deletion avoid double starts and deleting active capture files.
- **DVR recovery:** future schedules re-arm; missed starts and partial captures are distinct; app relaunch reconciles force-stop/missed-alarm state.
- **Real cancellation test:** the first fixture accidentally declared a completed response body; strengthened it to remain blocked before cancellation. That test exposed a real `HttpURLConnection` close/read stall. Networking moved to explicitly cancellable OkHttp calls, including Media3 integration. The final test must pass against the genuinely stalled response.
- **Transport verification:** a self-signed TLS fixture verifies default certificate rejection. No TLS/signature/artifact verification was disabled while resolving build issues.
- **Navigation continuity:** library and recording refreshes preserve scroll state; custom groups are accessible through the group chooser without requiring parental Settings access.

Remaining open boundaries are explicit in SECURITY.md, ROADMAP.md and HARDWARE_CHECKLIST.md. In particular, SHA-256 lookup fingerprints are not keyed; full database extraction/root is not an addressed threat. Device picker availability, OS recording limits, codec allocation, real provider semantics and performance cannot be concluded from framework tests.

- **Multiview constraints:** explicit track/size refusal avoids quietly accepting an audio-only black tile when no video rendition satisfies the tile budget; stale lifecycle callbacks cannot revive a stopped session.
- **Test isolation and reproducibility:** isolated framework/JVM security providers, waited for actual UI readiness, locked Gradle dependencies and pinned CI actions to verified commit SHAs. The Media3/client bridge is exercised directly, not inferred from a successful dependency download.

- **Destructive test guard:** the existing instrumentation setup deleted the default library. It now fails before deletion unless running on an identified Android emulator with explicit reset consent; CI supplies that flag only to its isolated AVD. Personal-device acceptance uses the non-resetting manual checklist.
