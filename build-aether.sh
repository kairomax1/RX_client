#!/usr/bin/env bash
#
# Builds the Aether engine (https://github.com/QW-AI-Code/Aether, AGPL-3.0-only) from source
# into libs/<abi>/libaether.so. The engine is a command-line program; RX VPN starts it as a
# separate process and talks to it over a local SOCKS port. Nothing prebuilt is downloaded.
#
# Built WITHOUT the engine's "tor" feature: RX VPN only uses the engine's own (WARP) path, and
# Tor would add 6-8 MB per ABI.
#
# Needs: AETHER_NDK_HOME (NDK r26, the version the engine is developed against), rustup with
# the Android targets, cargo-ndk.
set -euo pipefail

AETHER_REPO="https://github.com/QW-AI-Code/Aether.git"
# Pinned: a moved branch must never change what ships.
AETHER_COMMIT="249cc903b225de4280b58e50e0c298834e6d4285"
API=26

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${ROOT}/.aether-build"
OUT="${ROOT}/libs-aether"
NDK="${AETHER_NDK_HOME:?AETHER_NDK_HOME is not set}"
TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/linux-x86_64"

rm -rf "${WORK}" "${OUT}"
mkdir -p "${WORK}" "${OUT}"
git clone --quiet "${AETHER_REPO}" "${WORK}/src"
git -C "${WORK}/src" checkout --quiet "${AETHER_COMMIT}"

CRATE="${WORK}/src/native/aether/aether"
# The project's own dependency security fixes; the build still works if this step cannot run.
python3 "${WORK}/src/scripts/patch-engine-dependencies.py" "${CRATE}" || echo "WARNING: dependency patch step failed; building with the locked versions"

export CARGO_TARGET_DIR="${WORK}/target"
export ANDROID_NDK_HOME="${NDK}"
export ANDROID_NDK_ROOT="${NDK}"

build_abi() {
  local abi="$1" triple="$2" sysroot_triple="$3"
  echo "==> Aether engine for ${abi}"
  ( cd "${CRATE}" && cargo ndk -t "${abi}" --platform "${API}" build --release --bin aether )
  local bin="${CARGO_TARGET_DIR}/${triple}/release/aether"
  [ -f "${bin}" ] || { echo "ERROR: no engine binary at ${bin}" >&2; exit 1; }
  mkdir -p "${OUT}/${abi}"
  cp "${bin}" "${OUT}/${abi}/libaether.so"
  "${TOOLCHAIN}/bin/llvm-strip" "${OUT}/${abi}/libaether.so" || true
  # The engine is an executable, so the C++ runtime it links must ship next to it.
  if "${TOOLCHAIN}/bin/llvm-readelf" -d "${OUT}/${abi}/libaether.so" | grep -q 'libc++_shared.so'; then
    cp "${TOOLCHAIN}/sysroot/usr/lib/${sysroot_triple}/libc++_shared.so" "${OUT}/${abi}/libc++_shared.so"
  fi
  ls -la "${OUT}/${abi}"
}

build_abi "arm64-v8a"   "aarch64-linux-android"    "aarch64-linux-android"
build_abi "armeabi-v7a" "armv7-linux-androideabi"  "arm-linux-androideabi"
build_abi "x86_64"      "x86_64-linux-android"     "x86_64-linux-android"

echo "${AETHER_COMMIT}" > "${OUT}/AETHER_COMMIT"
rm -rf "${WORK}"
