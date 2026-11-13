# Aether engine

Release builds of RX VPN may include the Aether engine as a separate executable
(`libaether.so`). RX VPN does not link against it; it starts it as its own process and uses
the local SOCKS port it offers.

- Project: https://github.com/QW-AI-Code/Aether (engine by CluvexStudio)
- License: GNU Affero General Public License v3.0 only (AGPL-3.0-only)
- Exact source used: the commit pinned in `build-aether.sh`, directory `native/aether`,
  built unmodified by that script (without the optional `tor` feature).
