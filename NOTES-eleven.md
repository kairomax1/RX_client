# The eleven things

Working notes for this pass. Each line is the defect as it was reported, and
what in the code is responsible.

1. Configs stop carrying traffic after a few minutes; not stable.
   → `CoreServiceManager.watchConfig`: probes every 60 s with `measureDelay` on
     gstatic; three misses → moves. On a line where gstatic is itself
     unreachable this moves a working config away. Also no detection of a
     stalled core (process alive, no traffic).
2. Mux and fragment are not re-checked when the config changes; the app must
   find the best combination itself and really apply it.
   → `Tuning.best()` is remembered per config and never expires; `needsTuning`
     only true when nothing is stored. `Tuning.apply` writes global settings
     that are read when the outbound is built, so a config change keeps the
     previous config's answer. Also gaming mode skips tuning entirely.
3. DNS scanner: it sweeps all servers, never first tests whether Xbox works on
   the user's own line, and only examines "suspects".
   → `XboxDnsViewModel.scan()`.
4. The WireGuard core is not optimised: it pings but carries nothing.
   → `CoreOutboundBuilder.toOutboundWireguard` + traffic test for UDP sorts.
5. Public configs never refresh, come back duplicated, ignore the count, and
   are added before being tried.
   → `PublicConfigs.collect` / `prune` / `refreshIfDue`.
6. Mozilla: the chosen country is not the one used; leaving the screen and
   coming back leaves it cut.
   → `FirefoxVpnActivity.connect()` + `FirefoxVpnManager.endpointsOf`.
7. Mozilla: show the traffic used, live.
   → `FirefoxVpnRelay.pump`.
8. DNS scanner: far more sources.
   → `XboxDns.LIST_SOURCES`.
9. Public configs: more sources, and better speeds.
   → `PublicConfigs.SOURCES` + selection by measured speed.
10. Take the bugs out before building.
11. Not regional: works for everybody, not for one operator.
   → `XboxDns` MICROSOFT-only matching, `NetworkOperator` specific lists.
