#!/usr/bin/env bash
set -euo pipefail

tracelet_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$tracelet_root/packages/tracelet_platform_interface"
flutter pub get
flutter_rust_bridge_codegen generate

if [[ "$(uname -s)" == Darwin ]]; then
  bash "$tracelet_root/sdk/rust-core/build-ios.sh"
fi
