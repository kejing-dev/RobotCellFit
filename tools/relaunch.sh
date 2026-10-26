#!/usr/bin/env bash
# Reinstall (optional: pass "install"), reset the emulator camera, start the app and wait until it settles,
# The reset camera looks straight at the window-seat desk 2.2 m ahead (room of Pico_Emulator_6_1_fresh): no walking
# (the posted W/A/S/D/Q/E keys are not reliable and can carry the camera through a wall).
# The Stage origin is the camera pose at launch: always reset the camera BEFORE starting the app, or the plane
# frame and the head frame disagree on the emulator.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEV=${DEV:-emulator-5560}
PKG=io.embodit.robotcell
ADB="${LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe"
H="bash $ROOT/tools/harness/emu.sh"
UIA="$(cygpath -w "$ROOT/tools/harness/uia_cam.ps1")"
[ "${1:-}" = install ] && "$ADB" -s $DEV install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" | tail -1
MSYS_NO_PATHCONV=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$UIA" reset -Avd "${AVD:-Pico_Emulator_6_1_fresh}" | tr -d '\r' | head -1
MSYS_NO_PATHCONV=1 powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$UIA" set camera_rotation_button on -Avd "${AVD:-Pico_Emulator_6_1_fresh}" | tr -d '\r' | head -1
sleep 1
for attempt in 1 2; do
  "$ADB" -s $DEV shell am force-stop $PKG; sleep 3; "$ADB" -s $DEV logcat -c
  "$ADB" -s $DEV shell am start -n $PKG/.platform.LaunchActivity >/dev/null 2>&1
  for i in $(seq 1 30); do "$ADB" -s $DEV logcat -d -s RobotCell:V | grep -qE "STATE S(0[2-9]|1[0-9])" && break; sleep 2; done
  "$ADB" -s $DEV logcat -d -s RobotCell:V | grep -qE "STATE S(0[2-9]|1[0-9])" && break
  echo "stage did not open, retrying"
done
"$ADB" -s $DEV logcat -d -s RobotCell:V | grep -E "STATE" | tail -1 | cut -c40-140
"$ADB" -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd 'lang zh'" >/dev/null
"$ADB" -s $DEV logcat -c; "$ADB" -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd hmd" >/dev/null
for i in $(seq 1 10); do L=$("$ADB" -s $DEV logcat -d -s RobotCell:V | grep "POSE hmd" | tail -1); [ -n "$L" ] && break; sleep 1; done
echo "$L" | grep -o "pos=([^)]*).*fwd=([^)]*)"
