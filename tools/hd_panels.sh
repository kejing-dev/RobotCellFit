#!/usr/bin/env bash
# High-definition panel shots for a before / after comparison (v0.1.3 layout pass):
#   DEV=emulator-5568 AVD=PICO_6_1_Factory5 tools/hd_panels.sh <apk> <outdir>
# Installs the APK, restarts the app (no camera reset), looks 15 degrees down so the whole cell panel is in frame,
# puts the robot on the virtual desk and captures the aiming panel, the four tabs, a pin verdict, a running program
# and the More menu. Set the AVD to High Definition first (AVD.conf graphics_preset=2, 2560 x 2558).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$1"; OUT="$2"; mkdir -p "$OUT"
export DEV=${DEV:-emulator-5568} AVD=${AVD:-PICO_6_1_Factory5}
PKG=io.embodit.robotcell
ADB="${LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe"
H="bash $ROOT/tools/harness/emu.sh"
cmd() { MSYS_NO_PATHCONV=1 "$ADB" -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '$1'" >/dev/null 2>&1; }
shot() { SH="$OUT" PRE=1 SETTLE=5 timeout 120 bash "$ROOT/tools/qa_shot.sh" "$@" | tail -1; }
MSYS_NO_PATHCONV=1 "$ADB" -s $DEV install -r "$APK" | tail -1
MSYS_NO_PATHCONV=1 "$ADB" -s $DEV shell am force-stop $PKG; sleep 2
MSYS_NO_PATHCONV=1 "$ADB" -s $DEV shell am start -n $PKG/.platform.LaunchActivity >/dev/null 2>&1
for i in $(seq 1 40); do MSYS_NO_PATHCONV=1 "$ADB" -s $DEV logcat -d -s RobotCell:V | grep -qE "STATE S(0[2-9]|1[0-9]|2[0-9])" && break; sleep 2; done
cmd "lang zh"; cmd clear_now; sleep 1; cmd "vroom on"; sleep 1; cmd "vroom here"; sleep 2
shot S25 aim-panel
cmd "place table 0 0"; sleep 3
$H drag 1279 900 1279 1240 -Button right -Steps 20 -StepMs 60 -NoFast >/dev/null 2>&1; sleep 2
shot S05 tab-pose
shot S10 tab-joints
shot S11 tab-pins
shot - tab-pins-red-selected "select 3"
shot S22 tab-run
shot - tab-run-playing "run pins"
cmd "run stop"; sleep 1
shot S13 menu
$H drag 1279 1240 1279 900 -Button right -Steps 20 -StepMs 60 -NoFast >/dev/null 2>&1
echo "done $OUT"
