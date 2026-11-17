#!/usr/bin/env bash
set -euo pipefail

tracelet_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_sha="$(git -C "$tracelet_root" rev-parse --short=8 HEAD)"
bash "$tracelet_root/sdk/rust-core/build-android.sh"
cd "$tracelet_root/sdk/android"
./gradlew :tracelet-sdk:publishForkReleasePublicationToForkRepository "-PforkSource=$source_sha"
