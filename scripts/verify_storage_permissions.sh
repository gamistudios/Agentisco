#!/usr/bin/env bash
# Asserts the storage predicate the user actually experiences: the live
# MANAGE_EXTERNAL_STORAGE grant on the connected device, as the system reports
# it. It never reports ok from the manifest, the code, or a preference — those
# stay true while file access is still blocked, which is exactly how the old
# phantom script managed to print "Storage: ok" for an ungranted permission.
set -uo pipefail

PKG="${AWAKI_PKG:-com.awaki}"
SER="${AWAKI_SERIAL:+-s $AWAKI_SERIAL}"

if ! command -v adb >/dev/null 2>&1; then
  echo "FAIL: adb not found — cannot read the device's real state"
  exit 1
fi
if ! adb $SER get-state >/dev/null 2>&1; then
  echo "FAIL: no device connected (adb get-state) — state unknown, so nothing is ok"
  exit 1
fi

SDK=$(adb $SER shell "getprop ro.build.version.sdk" 2>/dev/null | tr -d '\r[:space:]')
echo "Device SDK: ${SDK:-unknown}"

# Android 11+ (API 30): the special-access toggle lives in appops.
STATE=$(adb $SER shell "appops get $PKG android:manage_external_storage" 2>/dev/null | tr -d '\r')
echo "appops MANAGE_EXTERNAL_STORAGE: ${STATE:-no output}"

if [[ "$STATE" == *allow* && "$STATE" != *ignore* && "$STATE" != *deny* ]]; then
  echo "Storage: ok"
  exit 0
fi

# Below Android 11 the grant is the runtime permissions instead.
if [[ "${SDK:-0}" -lt 30 ]] 2>/dev/null; then
  PERMS=$(adb $SER shell "dumpsys package $PKG" 2>/dev/null | tr -d '\r' | grep -E "READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE")
  echo "$PERMS"
  if echo "$PERMS" | grep -q "READ_EXTERNAL_STORAGE: granted=true" &&
     echo "$PERMS" | grep -q "WRITE_EXTERNAL_STORAGE: granted=true"; then
    echo "Storage: ok (legacy runtime permissions)"
    exit 0
  fi
fi

echo "Storage: NOT granted — open Awaki, the settings page comes up on the next launch, or Settings -> Battery & permissions -> File access"
exit 1
