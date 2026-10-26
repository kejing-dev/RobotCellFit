#!/usr/bin/env bash
# One idle cold start (no fast-on, no input): force-stop, start, watch 60 s, report ANR and timings.
#   tools/coldstart.sh [clear]   ("clear" wipes the app's data first: no saved cell)
set -u
DEV=${DEV:-emulator-5560}
PKG=io.embodit.robotcell
ADB="${LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe"
"$ADB" -s $DEV shell am force-stop $PKG
[ "${1:-}" = clear ] && "$ADB" -s $DEV shell pm clear $PKG >/dev/null
sleep 3
"$ADB" -s $DEV logcat -c; "$ADB" -s $DEV logcat -b events -c
t0=$(date +%s%3N)
"$ADB" -s $DEV shell am start -n $PKG/.platform.LaunchActivity >/dev/null 2>&1
sleep 60
L=$("$ADB" -s $DEV logcat -d -v epoch -s RobotCell:V)
first() { echo "$L" | grep -m1 -E "$1" | awk -v t0=$t0 '{printf "%.1f", ($1*1000-t0)/1000}'; }
echo "stage_enter_s=$(first 'STAGE enter') scene_attached_s=$(first 'SCENE attached') robot_s=$(first 'STATE S(0[5-9]|1[0-9]|2[01])') aiming_s=$(first 'STATE S0[34]')"
echo "meshes: $(echo "$L" | grep -o 'MESH [a-zA-Z]* ms=[0-9]*' | tr '\n' ' ')"
echo "anr_events=$("$ADB" -s $DEV logcat -b events -d | grep -c 'am_anr')"
echo "anr_windows=$("$ADB" -s $DEV shell dumpsys window windows 2>/dev/null | grep -c 'Not Responding')"
echo "alive=$("$ADB" -s $DEV shell pidof $PKG)"
echo "$L" | grep -E "STATE S" | tail -1 | cut -c1-140
