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
