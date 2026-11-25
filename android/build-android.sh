#!/bin/sh
# Builds Archives for Android:
#
#   android/build-android.sh [ABI] [debug|release]
#
# ABI is arm64-v8a (phones, the default) or x86_64 (the emulator). Needs the
# Android SDK and NDK (ANDROID_HOME, default ~/.local/opt/android-sdk),
# cargo-ndk, the Rust target for the ABI, and a JDK (JAVA_HOME).
#
# 1. the engine (core/ through android/rust) for that ABI, and its Kotlin
#    bindings (UniFFI);
# 2. the app's tests, then the app (Gradle), in android/app/build/outputs/apk/.

set -eu
here=$(cd "$(dirname "$0")" && pwd)
abi=${1:-arm64-v8a}
kind=${2:-debug}
case "$abi" in
    arm64-v8a | x86_64) ;;
    *) echo "build-android: ABI is arm64-v8a or x86_64" >&2; exit 2 ;;
esac
case "$kind" in debug | release) ;; *) echo "build-android: debug or release" >&2; exit 2 ;; esac

: "${ANDROID_HOME:=$HOME/.local/opt/android-sdk}"
export ANDROID_HOME
ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)}
[ -d "$ANDROID_NDK_HOME" ] || { echo "build-android: no NDK in $ANDROID_HOME/ndk" >&2; exit 1; }
export ANDROID_NDK_HOME
# No paths of the computer it's built on end up in the library.
export RUSTFLAGS="--remap-path-prefix=$HOME=~"

rust=$here/rust
app=$here/app/src/main
echo "build-android: engine for $abi"
cargo ndk --manifest-path "$rust/Cargo.toml" -t "$abi" --platform 26 -o "$app/jniLibs" build --release --locked --lib
cargo build --manifest-path "$rust/Cargo.toml" --release --locked --bins
"$rust/target/release/uniffi-bindgen" generate --library "$app/jniLibs/$abi/libarchives_ffi.so" \
    --language kotlin --out-dir "$app/java" --no-format >/dev/null

echo "build-android: app"
task=assemble$(printf %s "$kind" | cut -c1 | tr a-z A-Z)$(printf %s "$kind" | cut -c2-)
# ARCHIVES_VERSION_CODE / ARCHIVES_VERSION_NAME: what the release workflow numbers it.
"$here/gradlew" -p "$here" --no-daemon -q testDebugUnitTest "$task" -Pabi="$abi" \
    ${ARCHIVES_VERSION_CODE:+-PversionCode="$ARCHIVES_VERSION_CODE"} ${ARCHIVES_VERSION_NAME:+-PversionName="$ARCHIVES_VERSION_NAME"}
ls "$here"/app/build/outputs/apk/"$kind"/*.apk
