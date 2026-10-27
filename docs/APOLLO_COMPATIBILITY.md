# Apollo compatibility investigation

Evidence collected October 27, 2026. This is a protocol investigation, not a completed Moonshot integration or a provider-identity endorsement. No account login, subscription playback, or provider-side permission check was performed. Moonshot's application code is unchanged by this report.

## Sources and reproducibility

- User-supplied [APK download](https://apk.apg.ooo/aptv.apk), downloaded over verified HTTPS: 112,065,477 bytes.
- APK SHA-256: `1b3e2a59443b20f44e96fd0c45e15ee1a944bf327499019bc5baab94a6d3e7e4`. It matches the user's earlier inspection report.
- Package: `tv.apollogroup.androidtv`, version 2.6.1, version code 126, minimum API 24, target API 35.
- Android build tools `apksigner verify` passed APK Signature Scheme v2 verification. This verifies signature integrity, not the real-world identity or reputation of its publisher.
- [Public web player](https://watch-time.me/movies) and its publicly served [application JavaScript](https://watch-time.me/js/app.d0f520fd.js).
- JavaScript SHA-256 at inspection: `dd2f292b76ba3b141a29b5ab1df9b22462b7468a7b6143f13b8a4a744ab97565`. Deployments may replace this bundle.
- JADX 1.5.3 was used for static APK inspection. It completed with 49 decompilation errors; recovered declarations provide evidence of the API contract, not proof of every runtime code path. The APK was not executed.

Original APKs, JavaScript bundles and decompiled third-party code are kept outside the Moonshot checkout. This report contains factual interface observations; no third-party application code, interface assets, passwords or account tokens are committed.

## Confirmed in the publicly served web-player code

The application sets its default API base to `https://easy-play.cc/`. Its login helper sends a POST to `api/webplayer/login` with `X-Client-Type: web`. The form supplies username and password. Its login success handler reads `data.data.token` and stores an access token. A request interceptor adds `Authorization: Bearer <token>` and, when selected, `X-API-PROFILE`.

Representative declared routes:

| Purpose | Route |
| --- | --- |
| Current account | `GET /api/user` |
| Profiles | `GET /api/profiles` |
| Live categories | `GET /api/info/livetv/categories` |
| Live category entries | `GET /api/info/livetv/categories/{category}/0/100` |
| Movie playback resolution | `GET /api/play/movie/{id}` |
| Live playback resolution | `GET /api/play/livetv.epg/{id}` |
| Episode playback resolution | `GET /api/play/tvshow/{id}/{season}/{episode}` |

Playback code consumes a `secure_link` result. Thus the public client resolves a media address through an authenticated API, rather than assuming a previously downloaded M3U contains a durable playable URL. Signed-link expiry behavior and actual server responses are not verified.

The bundle also includes installation instructions mentioning M3U links, Startup Show, and an account-provided `credentials.m3uLink` property. This contradicts treating the evidence as proof that M3U is categorically unavailable. It does not establish that the user's particular URL downloads a standard M3U, that every account exposes that property, or that arbitrary third-party players are supported. Example URLs in help text must not be substituted for a user's current account link.

## Confirmed in recovered APK declarations

Relevant recovered files include `io/nitrix/core/datasource/ws/api/UserApi.java`, `PlayApi.java`, `CrossPlatformLoginApi.java`, `DomainApi.java`, `io/nitrix/data/body/UserLoginBody.java`, `io/nitrix/data/response/user/UserLoginResponse.java` and `io/nitrix/data/response/MediaResponse.java`.

- Account login is declared at `login`, relative to the configured API base. Its body contains `username` and `password`. An overload supplies device/app metadata headers.
- The login response model includes `data.token`, `token_type`, `expires_at`, `service` and a separate guide-updater token.
- Playback declarations use an Authorization header and `X-API-PROFILE`. They include `play/movie/{id}`, `play/livetv.epg/{id_extension}` and episode-specific routes.
- Media response models contain `data.link` and `data.secure_link`.
- A request interceptor identifies Android requests using `X-Client-Type: app-android`.
- A separate cross-device login client uses `https://tvauth.infolink.me/`, with code exchange and `X-AUTH-TOKEN` authentication declarations. This is a separate flow, not sufficient evidence that this hostname is the ordinary account-login server.
- Configuration contains development API bases, but a host-rewriting interceptor substitutes a configured service hostname at runtime. Hardcoding a `dev.testbyte.top` string found in the binary would therefore be unjustified.

These facts support a provider-specific account API. They do not establish Xtream compatibility: username/password UI alone does not identify Xtream, and Moonshot's current Xtream connector cannot simply be pointed at a web-player page.

## Why the current M3U failure remains separate

The user supplied a browser screenshot reporting a DNS failure for the playlist hostname. In this updated cloud instance, a direct resolver attempt also returned a temporary DNS error, and the playlist request was refused at the cloud HTTPS tunnel with HTTP 403. That cloud result is not a provider HTTP response, an account rejection, or an authoritative global DNS diagnosis. The private URL and response content were not logged or committed.

A working app/web account and a failing supplied playlist URL can coexist because their connection paths differ. The available evidence does not prove a universal external-player ban, reseller status, or which similarly named service is original.

## Community research status

Google and Bing became reachable after the environment update, but Google returned an interstitial rather than usable results, and Bing results for the targeted queries were unrelated. Reddit search and JSON access returned HTTP 403. No matching community report was verified and no search snippet is presented as evidence of one. The APK and public web client supply the concrete findings above.

## Moonshot integration work still required

1. Add a separate provider-account connection type; preserve existing M3U and Xtream options.
2. Resolve and validate the appropriate production account API and service/profile selection. Identify Moonshot honestly; do not impersonate an approved application to evade restrictions.
3. Store credentials/tokens through the existing encrypted storage boundary, handle token expiry and retain parental authorization checks.
4. Map paginated live/VOD/series metadata into the local library, and resolve media URLs immediately before playback. Do not treat temporary secure links as permanent playlist identities.
5. Verify actual success/error response shapes, permitted authentication, profile selection, stream formats, expiry and playback with user-entered account credentials on the intended devices. Respect any required browser challenge or provider access restrictions.

No credentials are needed in chat or source. Static declarations alone cannot certify that a third-party client will be accepted. The existing APK does not yet implement this account API.
