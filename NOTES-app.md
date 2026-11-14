# RX VPN — notes that must survive

Working branch: `arena/d739235f-rx-client` (App repo: `kairomax1/RX_client`).

## App identity
- **Name:** RX VPN — not "RX Client". `app_name` = `RX VPN` in `values/strings.xml`,
  `dev`/`fdroid` flavour strings, and all 10 locale files.
- **Package:** `com.mobix.vpn` (must stay — installed app and signing key depend on it).
- **Icon:** dark rounded square (#1E1E1E-ish) with "R" in white and "X" in amber (#F5B050-ish).
  Source of truth: `branding/rx-vpn-app-icon.png` (the 1254 px file the user supplied).
  Generated from it: `branding/rx-vpn-playstore-512.png`,
  `fastlane/metadata/android/en-US/images/icon.png`, and every
  `mipmap-*/ic_launcher.png`, `ic_launcher_round.png`, `ic_launcher_foreground.png`.
- **Palette:** graphite dark theme with an **amber** accent (the RX VPN theme), not the
  violet/cyan "RX Client" theme. `Theme.kt` holds it.
- **UI:** graphite glass panels/buttons (`RxGlassPanel`, `RxGlassButton` in
  `ui/compose/RxComponents.kt`), app routing (`ui/perappproxy/`), bottom bar in
  `ui/main/MainBottomBar.kt`.

## Structure of the bottom bar (as of this change)
- Status line: dot + status text. When connected it is tappable and re-checks the ping;
  a spinner replaces the ping chip while a check runs.
- The ping chip (`48 ms`) appears on the status line **and** as a second line under the
  "Disconnect" label. No separate lightning button any more.
- Big accent button: Connect / Disconnect (`rx_connect` / `rx_disconnect`).
- Square button next to it opens App routing (`MainDestination.PerAppProxy`).

## Things to remove (the user's standing instruction)
- Anything advertising-like: promotion rows, promo URLs, ad/analytics/crash SDKs,
  upstream Telegram channel links, dead wiki/help entries, upstream branding.
- These were already stripped once (`80fa4a92`) and stayed stripped after the RX VPN merge.
  Verify after any upstream merge with:
  `grep -rIn "APP_PROMOTION_URL\|APP_WIKI_MODE\|9\.234456\|github_2dust" --exclude-dir=.git .`

## Things to add / keep in mind
- Ping visible to the user without a separate button (done in the bottom bar).
- Privacy policy lives in `CR.md` and is referenced by `AppConfig.APP_PRIVACY_POLICY`.
- Store metadata in `fastlane/metadata/android/en-US/` (title, short/full description, icon).
- Release notes template: `.github/release-notes.md` with `@TAG@` / `@SHA@` placeholders.

## Signing (so updates install over the top)
- Only one key signs RX VPN: `V2rayNG/signing/rxclient.p12` (alias `mobix`, password
  `mobixvpn`, PKCS#12). Cert SHA-256:
  `52:86:4C:67:AF:63:07:4A:B2:A6:13:64:3A:4B:09:43:4E:3D:32:0B:87:DC:9C:1D:6B:EF:51:4D:88:1A:5C:83`.
- CI used to generate a throwaway key per run, so every APK had a new signature and Android
  refused to update in place ("App not installed") until the old app was uninstalled.
  `build.yml` now signs with the checked-in key (`APP_KEYSTORE_BASE64`, if ever set, must
  hold that same key). Never regenerate the key — that would force every user to uninstall.
- Playstore APKs all carry `1000000*4 + versionCode`, so any playstore APK updates any other;
  fdroid builds use a different offset, so switching between the two needs an uninstall.

## Publishing notes
- Live releases: `v1.0.0` (RX Client, has the old promotion build) and `v1.0.0-rxvpn`
  (pre-release). The next release should be a normal release, e.g. `v1.0.1`, so
  `releases/latest` points at the new build.
- Workflow `Build RX VPN APK` builds APKs and runs validation; `Publish RX VPN Release`
  uploads the artifact of a successful run with
  `-f release_tag=<tag> -f artifact_run_id=<run id>`.
- No Android SDK/JDK in the sandbox — GitHub Actions is the only compile gate.

## Design system (1.0.2 pass)
- `ui/compose/Theme.kt` owns `AppShapes` (8/12/18/24/30 dp) and is passed to `MaterialTheme`,
  so chips, cards, dialogs and menus share one corner language.
- Semantic latency tone: `rxPingColor(ms)` / `rxPingContainer(ms)` — tertiary <= 400 ms,
  primary <= 900 ms, error otherwise or for -1. Do not hardcode `colorPing`/`colorPingRed`
  in new screens (they stay for the older routing screen).
- `RxPill(text, contentColor, containerColor)` in `ui/compose/RxComponents.kt` is the one
  small label used for protocol, latency and subscription badge.
- Server rows: tinted selected background + 4 dp accent rail, name row, meta row with badge /
  statistics / done tick, third row with type pill (plus latency pill in the two column grid).
  Share, edit and delete live behind a single 40 dp overflow button that opens
  `ShareMethodDialog(more = true)`.
- Splash: brand mark, name, then `splash_tagline` («هواتو داره» / "Got your back"); the whole
  overlay is skipped when `ServiceRuntimeState.isRunning()` is true, i.e. the tunnel is up.
- `ServiceRuntimeState` mirrors the core state into MMKV (`pref_service_running`) from the
  `:daemon` process and verifies the daemon process is alive before trusting it.

## Black and silver pass
- Dark scheme in `Theme.kt` is now pure black with brushed silver (`#F4F6FA` / `#A4ABB8`), from
  the user's HTML design; the amber accent survives only in the light scheme and the launcher
  icon. To go back to gold, change `primary`/`secondary` to `#E0B070` / `#C9A070`.
- Intro (`MainSplashOverlay` + `MainSplashTimeline`): rings, mark spinning in out of a blur, two
  slashes, sheen, name, tagline, loading line, then zoom-out exit while the home screen scales in
  from 96 % (`MainActivity.ScreenContent`). Tap skips to the exit.
- Main header is pinned left-to-right in every locale; group tabs sit in a 22 dp rounded bar.
- The HTML the user pasted was cut off after `.tabs`; server cards and the bottom bar were not
  part of it and are unchanged apart from the palette.

## "Have to uninstall on every build" (fixed)
- Cause: releases carried `RXVPN_*_test-debug_*.apk`, signed with the CI runner's throwaway debug
  key, which is different on every run. Installing it once broke every later update.
- Now: that file is no longer shipped, CI signs debug builds with the release key too, and
  `build.yml` fails if any APK of a run has another certificate. Do not re-add a differently
  signed file to a release.


## Build 1 of the 24-item batch (home screen, menus, update notice, About)

Standing rule (item 19): **anything newly added to the app (button, menu, message, card, screen)
gets its own animation.** Every such animation must go through `rememberAnimationsEnabled()` so the
system "Remove animations" switch still gives a still screen.

What changed and where:

- Live wallpaper: `RxDustBackground` in `ui/compose/RxMotion.kt`, drawn behind the main screen.
- Connection state lives in `ConnectPhase` (`MainContract.kt`). `Connecting` lasts until a real
  delay test through the tunnel succeeds (`MainViewModel.beginConnectionVerification`). Two failed
  probes, a start failure, or 25 s without a result count as failed: the service is stopped, the
  connect button turns red and shakes, the phone buzzes, `ConnectFailureMessage` rises from the dock.
  No toast is shown on connect or disconnect; `LauncherManager.startService(quiet = true)` is what
  the main screen uses. The open-proxy warning toast is kept on purpose (safety notice).
- The measured delay is `MainUiState.pingMillis`, shown on the ping button in the dock.
- Menus use `RxDropdownMenu` / `RxMenuItem`. Three-dots menu: six entries
  (`MainMoreMenuAction`). Plus menu: three entries. The manual "add ..." editors are no longer
  reachable from the plus menu; editing an existing config still works.
- "Delete failed configs" pings the current group first (`PingThenRemoveInvalid`), then removes.
- Group tab long-press (0.7 s fill) opens a per-group menu: `MainGroupTab.kt`.
- Grid button opens `MainQuickSheet` (subscriptions, per-app, routing, settings). The drawer keeps
  update check, backup and About. Cloudflare and SpeedTest screens still exist but have no entry.
- Settings: technical entries are hidden, not reset (list in `SettingsActivity.kt`'s comment).
- Updates: `handler/UpdateNotifier.kt`. Official releases only. Checked on app open (at most every
  6 h) and by the daily worker; red dot via `UpdateNotifier.pending`; the notification opens
  `CheckUpdateActivity` with `EXTRA_AUTO_INSTALL`, which downloads the APK and opens the installer.
- About: licences, translators, feedback, privacy and the package line are no longer shown
  (user decision; GPL attribution stays reachable through the source repository link).
- Logo: generated from `branding/rx-vpn-app-icon-silver.png` (launcher, adaptive foreground at 66 %
  on black, themed monochrome layer, `ic_brand_logo` for intro, drawer and onboarding).
- Strings added in this pass are translated for fa, ar, ru, zh, vi; `az` and `bn` carry the English
  text and `bqi` the Persian text until someone translates them.

Not run on a device: none of the above was seen on a phone or emulator.

### Follow-up after the first device test

- The config list hid the wallpaper because `ReorderableListItem`/`ReorderableGridItem` wrapped every
  row in an opaque `Surface`. Both now take a `color`; the main list passes transparent.
- Main screen backdrop is `rxScreenBrush()` (deep blue-grey), not pure black; the dust is denser,
  twinkles, and sits over two slowly wandering lights.
- Configs are separate rounded cards (`SERVER_CARD_SHAPE`), no dividers; the selected one has a
  silver rim.
- `MainUiState.exitIp` / `exitCountry` come from every successful delay test and are typed out in
  the status line (`ExitAddressText`), so a rotating exit address updates on each ping.

### Second fix round after device tests

- **Readable ink.** The home screen is not inside a `Surface`, so text without a colour was drawn
  black. `MainScreen` now provides `LocalContentColor`; the subscription card names its
  `contentColor` (a translucent container colour is not a scheme role and falls back to black).
- **No pure black anywhere.** `DarkColor` in `Theme.kt` is slate (`#0F1218` and lifted containers);
  window background follows. The splash keeps its own black: it is the brand intro.
- **Light theme is a real theme.** `LightColor` is paper and graphite (the old amber is gone).
  `RxDustBackground` draws graphite dust on light instead of switching off; tabs, empty-state mark
  and the "live" colour (`rxLiveColor`) take their ink from the scheme. Use `rxIsDark()` for any
  new decorative layer.
- **Drawer fog.** `RxFogBackground` (five drifting, breathing banks; white on dark, slate on
  light) sits behind the drawer and the welcome card.
- **Exit address typing.** `MainExitAddress` holds the pacing (95 ms per character, 1 s pause
  before the country) and the splitting (a flag is one piece). Each character fades in.
- **Settings.** One `SettingsSection` card per topic with an icon and a one-line purpose; list and
  edit rows take a `hint` and show the current value below it; DNS is folded behind a switch and
  fragment details appear only while fragment is on.
- **Language.** No first-run question. `LanguageOnboarding.applyDefaultOnce()` sets Persian when
  nothing explicit is stored. Settings offer Persian, English and the phone language only; the
  other translations remain reachable through the phone language.
- **Welcome message.** `WelcomeMessageOverlay`, shown once after the intro
  (`pref_welcome_shown`). The Persian text is the owner's wording: do not edit it.
- **Links.** `ExternalLinks.open` turns `t.me/...` into `tg://` so Telegram opens directly, tries
  the YouTube app for YouTube links, and falls back to the browser.

## Third fix round after device tests

Nine items, delivered together.

- **DNS switch** in settings is stored (`pref_show_dns_settings`), so it survives leaving the screen and a restart.
- **Fragment examples** read `50-100` and `10-20` in Persian too: the range is wrapped in left-to-right marks, otherwise right-to-left text mirrors it.
- **Persian UI**: the built-in group is stored as "Default" and is shown as «کانفیگ های عادی» (`group_default_name`) at display time, only while the user has not renamed it. Config names, names sent by a subscription server and protocol names are left alone.
- **Conversational prompts** (`ui/compose/RxPrompt.kt`): one family of cards that rise from the bottom of the screen, written in the Lalezar display face (SIL OFL, `res/font/lalezar_regular.ttf`, licence in `assets/lalezar_OFL.txt`). Used for the subscription name question, the single-config delete check and every backup/restore step. Their texts are the owner's wording, marked `translatable="false"` and shown in Persian in every language; do not edit them.
- **Subscription name**: asked only when the clipboard holds exactly one subscription link that is not stored yet. No, empty or back gives «اشتراک ها», then «اشتراک ها ۲», «۳» (`MainSubscriptionNaming.kt`).
- **Automatic test** (`handler/AutoConfigTest.kt`): after an import or a subscription update every new config is pinged, then the five lowest pings are speed-tested with a 600 KB download. A config that is not connected has no tunnel, so each speed test starts a private core for that config with a local HTTP inbound and stops it again; the running VPN is untouched. Numbers are kept in their own storage (`RX_AUTO_TEST`).
- **Badges** (`handler/ConfigBadges.kt`, rules covered by tests): per group, one config per badge. Winner of both ping and speed is «پیشنهادی» and the titles go to the runners-up; with different winners each keeps its title and the best all-rounder of the rest is recommended.
- **Battery**, with no visible change: decorative frame loops run only while the screen is on show; the drawer fog asks for no frames while the drawer is closed; wallpaper lights and fog banks reuse their gradients instead of building shaders every frame; the update dot no longer recomposes each frame; backups are built off the main thread.
- **Config list settings**: the two-column and "all" tab switches are gone (fixed off); speed display and delete confirmation default to on.
- **Backup screen**: two choices only. A backup is always password protected and leaves out ping results (`SERVER_AFF`) and the automatic test numbers. Older unencrypted backups still restore. Three wrong passwords end the attempt; the file is never locked. WebDAV, share and cleanup code is still in the view model but has no entry on the screen.

## Fourth fix round (version 1.0.1)

- **Automatic test after import** now goes through `CoreTestService`, the same path as "test all"
  (`MainViewModel.startAutoTest` → `finishAutoTest`). The private ping path of round 3 did not work
  on a real phone and is gone. When the pings are in, `AutoConfigTest.finish` measures speed for the
  five best, and `AutoConfigTest.sortGroup` rewrites the group order: recommended, lowest ping,
  fastest, then the rest by ping (`ConfigBadges.order`).
- **Config card** (three dots): `ShareMethodDialog` is an `RxPromptDialog` with `RxPromptOption`
  rows (icon, name, one line on what it is for). Follows the app language. Delete is the last,
  red row. Editing moved to a pencil on the row.
- **Updates**
  - Checked on app open (at most every 3 minutes), 4 s after the tunnel comes up, and every
    15 minutes in the background (`UpdateWatchWorker`, WorkManager minimum). No Google services.
  - Nothing is downloaded before the user saw the size and agreed. Screen: versions, scrolling
    description, fixed download button that turns into the progress bar and then "install".
  - `UpdateDownloader`: routes tunnel proxy → direct → mirrors (mirrors only when the release
    publishes a SHA-256); 4 parallel ranges, each resumable; size and checksum verified.
  - "No update" shows the fixed Persian note `update_none_note` (owner's wording, do not edit).
  - **Release description format**: text for the app goes inside `<!--app` … `-->`; GitHub hides
    it and the app shows only it. Everything else is the GitHub text. Without the block the app
    shows the whole description. See `UpdateRelease.appNotes`.
- `versionName` is `1.0.1`; `versionCode` unchanged (4).

### Lag fix after 1.0.1 device test (no visual change)

- `RxDustBackground` and `RxFogBackground` draw in their own graphics layer. Before, each
  wallpaper frame invalidated the layer it shared with the screen content, so the whole screen
  (or, on the update screen, the whole long description) was re-recorded every frame.
- The decoration clock is capped: dust ~60 fps, fog ~30 fps, instead of the display rate.
- Update description is a `LazyColumn` of lines instead of one huge scrolling `Text`.
- Typing caret alpha is read in the draw phase.
- Master's `build.yml` publishes `v<versionName>` publicly on every push to `master`; that is
  how an old-code `v1.0.2` appeared. The release was deleted on the owner's request.

## Play Protect round: new signing key, no install permission

- **Signing key replaced, once.** The old key was public in the repository. The current key
  (SHA-256 `845b7323...9ab8`, alias `rxvpn`) exists only in the secrets `APP_KEYSTORE_BASE64` and
  `APP_KEYSTORE_PASSWORD` and in the owner's private copy. **Never generate another key**: the
  owner's standing rule is that the signature stays fixed so updates always install. `build.yml`
  fails without the secret or on a fingerprint mismatch. See `V2rayNG/signing/README.md`.
  Anything above in this file about `rxclient.p12` / `mobix` / `mobixvpn` is history.
- **`REQUEST_INSTALL_PACKAGES` removed.** The in-app download stays; "install" copies the APK to
  the public Download folder (`UpdateNotifier.handOver`, Android 10+) and opens the downloads
  list. Android 9 and older open the release page in the browser.
- **No automatic publishing.** A push to `master` only builds and tests. A release is made by
  running `build.yml` by hand with `release_tag`.

## DNS mode (split tunnel by site list)

- Logic: `handler/DnsMode.kt`. When `pref_rx_dns_mode` is on, the core is given `DnsMode.rulesets()`
  instead of the user's stored routing rules (those are not modified and come back when the mode
  is switched off). Listed sites (filtered + restricted + the user's own) and Telegram's networks
  go through the config; everything else goes out directly. Sniffing is forced on, because the
  rules match on the site name.
- The lists are plain domain names kept in the code, so the mode does not depend on geo files.
- It is not a DNS server: an Android app cannot listen on port 53. For a console or TV the card
  shows the phone's proxy address (LAN IP + the app's proxy port); that needs "proxy sharing" on,
  the device on the phone's Wi-Fi/hotspot, and no proxy password.
- UI: fifth tile of the quick sheet (`MainDestination.DnsMode`) opens `MainDnsModeCard`;
  state in `DnsModeViewModel`. A running tunnel is restarted after each change.
- Not tested on a device. With the "dynamic port" setting on, the port changes at every start.

### DNS mode, round 2

- Fix: the tunnelled-sites rule now comes before the local-network rule. Inside Iran filtered
  names resolve to 10.10.34.x, which the local-network rule used to send out directly.
  `routeOnly` is forced off in the mode so the sniffed name replaces that fake address.
- The Google family is tunnelled whole (YouTube and Play need googleapis/gstatic/...), plus the
  `.google`/`.goog` endings (labs.google = Flow) and more AI services.
- Switching the mode on runs the automatic test over every config and connects to the lowest
  ping (`MainViewModel.testAllAndConnectBest` → `connectBestTick` → `MainAction.RestartService`).
- Xbox page (`handler/ConsoleDns.kt`): probes public anti-sanction DNS services from the phone
  and shows the fastest one whose two addresses both answer. The addresses are from memory and
  were not checked from the build machine; the probe on the phone is the check.
- Aether engine, researched (not integrated yet): `QW-AI-Code/Aether` vendors the engine in
  `native/aether` (Rust, core 2.4.0, AGPL-3.0). It is a command-line executable shipped as
  `libaether.so`, started as a process (`core/AetherProcess.kt`) and offering SOCKS on
  127.0.0.1:1819; built with `cargo ndk` (`scripts/build-natives.sh`, needs quiche/BoringSSL;
  Tor is an optional cargo feature costing 6-8 MB per ABI). Plan for RX VPN: build it in CI
  without Tor, run it as a process, and point an Xray `socks` outbound at it, so it works as a
  config-less "proxy" for DNS mode and as a standalone connection.

### DNS mode, round 3 + Aether

- Big site list: `assets/rx_dns_mode_sites.txt` (~6,800 entries) generated from
  v2fly/domain-list-community (MIT, commit 777d056): VPN/anti-censorship services, Google,
  social, communication, AI, dev, media, entertainment, crypto, game publishers. Iranian sites,
  IP-check and speed-test sites are removed so they stay direct. Adult sites are not included.
  Loaded lazily by `DnsMode.bundledSites`; the short Kotlin lists remain as the fallback.
- Top bar: ping button = `MainAction.PingAndSortAll` (the automatic test over every config:
  ping, badges, sort by ping).
- `UpdateRelease.pickApk` ignores assets with "debug" in the name.
- Aether: `build-aether.sh` builds the engine from the pinned commit in CI (cached; every step
  is `continue-on-error`, so a failed engine build still yields an APK without it). Arm64,
  armv7, x86_64 only; Android 8+. `core/AetherEngine.kt` starts it as a process on
  127.0.0.1:1819 from `CoreServiceManager.launchCore` when the selected profile is the built-in
  SOCKS profile "Aether", and stops it in `stopCoreLoop`. DNS mode falls back to that profile
  when no config answers; the card has a "connect with Aether" button. Never run on a device.

### Aether, round 2

- First device test: "connection failed". The engine opens its SOCKS port only after a route
  is proven, which can take longer than the 25 s connect watchdog; and nothing was visible,
  because `LogUtil.i` is dropped at the default log level and no log screen was reachable.
- `AetherEngine` now runs a supervisor thread in the service process: it walks `ROUTES`
  (MASQUE, +ECH, HTTP/2+fragment, WireGuard, WireGuard gfw, IPv6 variants, Gool), waits for
  the port, remembers the winning route per network (`files/aether/rx-winner-<network>`) and
  writes its progress to `files/aether/rx-status` for the UI process.
- `MainViewModel.awaitAetherRoute` holds the connection check until the engine is ready and
  shows "route N of M"; the connect watchdog is extended for the Aether profile.
- Engine output is logged at warning level with the prefix `Aether>`; the existing log screen
  (`ui/logcat`) is now in the drawer ("App log").
- DNS card: shows primary/secondary of the best anti-sanction DNS on its first page.
- Still open from the user's list: Xbox DNS scanner with a large resolver list, in-app Xbox
  web section, half-price (Iranian server) badge, ad blocking.

### App log + Aether, round 3

- Second device test: the search stayed on route 1 and then failed; the cause is not known,
  because the old log screen (Android logcat) is empty on the user's phone (Xiaomi).
- `handler/AppLog.kt`: the app's own log file (`files/logs/rx-app.log`, rotated at 400 KB),
  written by both processes. Sections (Connection, Core, Aether, DnsMode, Test, Subscription,
  Update, Other), levels (Debug/Info/Error), modes (Normal/Errors/Debug). Whole configs and
  share links are refused by `sanitize`. Every `LogUtil` call is mirrored into it regardless of
  the Android log level; key steps are also logged explicitly. Log messages are English
  diagnostic text; the screen chrome is localised.
- `ui/applog/`: new log screen in the app's own style (fog background, chips for mode and
  section with counts, glass lines, staggered arrival, rising notes). "Make a log file" writes
  the complete log to the cache and opens the share sheet. The drawer entry opens this screen;
  the old `ui/logcat` screen is no longer reachable.
- Aether: 14 routes (several ECH key sources: default, Google DoH, Quad9 DoH, 8.8.8.8 with
  crypto.cloudflare.com; HTTP/2+fragment with and without ECH; WireGuard; dual stack; IPv6;
  Gool; MASQUE x2), two rounds. Early exits and timeouts are logged with the engine's exit
  code and its last lines. Speed comparison between several working routes is NOT done yet:
  the first route that carries data wins (the engine's own `--balanced` scan picks the
  fastest gateway inside a route).

### Aether, round 4: racing routes

- Device log (Hamrah Aval, cellular): the sequential search needed 5 minutes; ten routes failed
  one after the other and the eleventh (MASQUE IPv6) connected in 26 s. Three of four ECH key
  sources did not answer; only `crypto.cloudflare.com` via 8.8.8.8 gave a key.
- `AetherEngine` now races up to `PARALLEL` (4) engines, each on a private port (1830+). A
  route is ready once a web request went through it; after the first one, others get 2.5 s to
  be compared and the one with the quickest answer stays. A relay on 1819 forwards TCP to the
  winner (SOCKS UDP goes to the engine's own UDP port directly).
- Remembered per network: the winner (by label) and failure counts (`rx-failed-<network>`);
  the winner searches alone for 12 s, failed routes go to the back. IPv6-only routes are
  skipped without a public IPv6 address. A route gets 40 s, up to 75 s while the engine reports
  progress.
- The ECH key is fetched by the app (HTTPS record over UDP from 8 resolvers × 2 names, first
  well-formed answer) and passed with `--ech <base64>`; ECH routes are skipped without a key.
- Identities are registered once, alone (`--register masque`, `--register wg`), before any
  race, marker `rx-identities`.
- Not verified on a device: the relay under load, several engines sharing one working
  directory (`lastconn` files), CPU/battery cost of four concurrent scans.
- Log: the exported file follows the chosen tab and mode; server names/addresses are no longer
  logged by `HttpUtil`; the update-check line no longer says "new version" before comparing.

### Xbox DNS scanner

- Drawer entry "Xbox DNS scanner" -> `ui/xboxdns/XboxDnsActivity` (own RX-style screen).
- Bundled data (regenerate by re-collecting the lists; all are plain address lists from GitHub):
  - `assets/rx_dns_all.bin`: 107,697 IPv4 addresses, 4 bytes each. Sources: trickest/resolvers,
    janmasarik/resolvers, proabiral/Fresh-Resolvers, BonJarber/fresh-resolvers,
    blechschmidt/massdns (lists/resolvers.txt), six2dez/resolvers_reconftw, rix4uni/resolvers,
    bp0lr/dmut-resolvers, blacklanternsecurity/public-dns-servers,
    shelld3v/fresh-public-dns-servers, kamalalhagh/kevinnet-dns.
  - `assets/rx_dns_named.txt`: 47 named services (anti-sanction, ISP, global), from
    Hadooshi/Iran-DNS-Toolbox. `assets/rx_dns_priority.txt`: 100 more addresses named by
    Iranian DNS tools (IranDNSFinder, smart-dns-ir, dns-switcher, DnsManager, free-dns-setter,
    akaDns, Iran-DNS-Switcher, DNS-on-Tray). None of these addresses were verified by us.
- `handler/XboxDns.kt`: sweep (one UDP socket, two names per server, ~850 servers/s, about
  two minutes for the whole list), relay detection (both names answered with one routable
  address), proof (four Xbox names resolved, TLS handshake with certificate check against each
  answer; pass = 3 of 4). Proved: named services that answered, all relays, the 20 quickest
  ordinary resolvers.
- `handler/DnsProxy.kt` + `ui/xboxdns/XboxWebActivity`: xbox.com in a WebView whose proxy
  (androidx.webkit ProxyController) is a local proxy resolving every name at the DNS under
  test. The app is excluded from its own VPN, so this traffic goes out directly, like a
  console's. No credentials are handled by the app; cookies stay in the WebView until
  "sign out".
- Not verified on a device: the sweep rate on mobile networks (operators may drop bursts of
  DNS packets), Microsoft sign-in inside a WebView, how many relays exist in practice.
- Log: web links are masked in `AppLog.sanitize` (a subscription link was found in a log).

### Xbox DNS scanner, round 2: the app's names, not the site

- Device test of round 1: a server that "passed" still gave "not available in your country" in
  the Xbox app, and no relay was found at all. Cause: the sweep asked `login.live.com` and
  `www.xbox.com`, which anti-sanction services do not relay, and the site opens from Iran
  directly, so passing proved nothing.
- `XboxDns.APP_NAMES` (8 names the Xbox app/console use; the site is gone). Sweep asks the
  first two (`sisu.xboxlive.com`, `user.auth.xboxlive.com`) of every address. Addresses given
  by many servers are taken as Microsoft's own (`commonAddresses`); a server giving a routable
  address that is neither common nor in the coarse Microsoft ranges (`MICROSOFT`, written from
  memory, not checked against the published Azure list) is a suspect. Suspects and all named
  anti-sanction services are examined: all 8 names asked; relay = one unusual address for an
  Xbox Live name plus at least one more name, and that address presents the real certificate
  for each (`examine`). Ordinary resolvers are no longer proved, only listed folded away.
- Country: `seenCountry` asks `geo.prod.do.dsp.mp.microsoft.com/geo` through the relay address
  (SNI). Works only if the relay passes that name on; otherwise empty. Not verified against the
  live service from the build machine (no access); the answer format is from public write-ups.
- Data: 39 more named services (Iranian: Server.ir, DynX, Zeus, DNS Pro, more Shelter/Vanilla/
  Radar/Begzar addresses...; Russian relays built for the same Microsoft block: xbox-dns.ru,
  Fly Xbox, GeoHide, Comss.one, Malw.link, Mafioznik, DNS-AI, AstraCat). Whether the Russian
  ones serve Iranian callers is unknown; the scan decides. Found through GitHub code search for
  files naming known anti-sanction addresses (about 280 list-like files from about 200
  repositories); addresses in at least two repositories went into `rx_dns_all.bin` (now
  123,211), in at least eight into `rx_dns_priority.txt` (7,240). Private, docker and
  filter-page addresses were removed from both; the `HostIran (Server 2)` typo was fixed.
  Licences of the source repositories were not checked.
- Screen: progress card fixed above the list; relays in their own section, appended in order
  of arrival during the scan and sorted once at the end; ordinary resolvers behind a toggle;
  one compact card per service.
- Removed: `DnsProxy`, `XboxWebActivity`, the androidx.webkit dependency (the site test).
- Not built: a phone-wide DNS switch inside the app. It needs a second `VpnService`, which the
  service guide routes through `LauncherManager`; the user tests with a DNS changer app.
- Not verified on a device: everything above. In particular whether real Microsoft addresses
  outside `MICROSOFT` show up as false relays, and whether a network that answers all DNS
  itself (transparent DNS proxy) hides every relay (the log says so when over 90% answer).

## Free public configs

`handler/PublicConfigs.kt` is the whole feature; `ui/publicconfigs/PublicConfigsActivity.kt` is its screen
(cloud button beside the app name, and the first drawer entry).

- Sources: `PublicConfigs.SOURCES`, 48 lists in 27 GitHub repositories. The 30 "small" ones (short,
  or tested by their makers) are always read; the 18 large ones only when the small ones did not
  give enough servers. Each list is kept in `files/rx-public/` with its ETag, so an unchanged
  list costs one short request. Order of fallback per list: GitHub raw, jsDelivr mirror, kept copy;
  with nothing at all, the pack in `assets/rx_public_configs.txt` (1,500 links; regenerate before
  a release, it ages in weeks). Shared accounts of commercial VPNs (the Windscribe WireGuard
  lists going around) are left out on purpose.
- Refused: certificate checking off, VLESS/Trojan without TLS, Shadowsocks without AEAD, VMess with
  alterId, SOCKS/HTTP, sorts the core cannot run (TUIC, SSR, OpenVPN), and servers whose address
  resolves into Cloudflare's ranges (`isCloudflare`).
- Two tests: a TCP connect from the phone (UDP sorts: address check only), then the app's usual
  real test through the core on three times the wanted number. `prune` keeps the quickest that
  answered and renames them "flag CC · TYPE · n" (country from ip-api.com's batch endpoint).
  After the download measurement on the best few, the head of the list is selected and, when
  the user started the run, connected to (`MainViewModel.testPublicConfigs`).
- Game mode: only configs reached straight over TCP/UDP (no ws/grpc/xhttp transports).
- Mux and Fragment are the app-wide settings, set from the screen; they are NOT tried per config
  (a profile has no field for them).
- Renewal: lists are looked at every three hours (`PublicConfigsWorker`, and when the app opens).
  A change lights the dot; the next time the app is open with no tunnel up, the group is
  collected and tested again with the saved options, without connecting.
- Failover: `CoreServiceManager.watchPublicConfig` asks through the tunnel every minute while a
  public config runs; three failures in a row move to the next config of the group.
- The notification's small icon is the vector `drawable/ic_stat_name.xml`; the old PNG sets and the
  proxy/direct icon swap are gone. The old logcat screen is deleted.
- Not built: relays for the domestic-only network. What the reports from the 2026 shutdowns
  name as working is DNS tunnelling (dnstt and relatives) through resolvers inside the country,
  which needs a new client core and tunnel servers; a domestic VPS relay needs a paid server.
