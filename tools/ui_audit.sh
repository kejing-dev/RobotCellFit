#!/usr/bin/env bash
# Spatial UI audit (v0.1.2): every spatial surface at the default heading, after a 90 degree turn and after a
# 180 degree turn (camera right-drag, 0.044 degrees per pixel), one screenshot each, plus the SIGHT / FOLLOW lines.
#   DEV=emulator-5568 AVD=PICO_6_1_Factory5 SH=<dir> tools/ui_audit.sh [H0|H90|H180]
# Uses the virtual desk (`vroom on`) so the robot stands in front of the camera on any emulator room.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEV=${DEV:-emulator-5568}
export DEV AVD="${AVD:-PICO_6_1_Factory5}"
PKG=io.embodit.robotcell
ADB="${LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe"
H="bash $ROOT/tools/harness/emu.sh"
OUT="${SH:-$ROOT/.scratch/ui-audit}"
cmd() { MSYS_NO_PATHCONV=1 "$ADB" -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '$1'" >/dev/null 2>&1; }
turn90() { $H drag 60 1079 2105 1079 -Button right -Steps 40 -StepMs 60 -NoFast >/dev/null 2>&1; sleep 3; }
shot() { SH="$OUT/$HEAD" PRE=1 SETTLE=4 timeout 120 bash "$ROOT/tools/qa_shot.sh" "$@" | tail -2; }
heading="${1:-H0}"; turns="${TURNS:-}"
case "$heading" in
  H0) ;;
  H90) turn90 ;;
  H180) [ "$turns" = 1 ] && turn90 || { turn90; turn90; } ;;

esac
HEAD=$heading; mkdir -p "$OUT/$HEAD"
cmd hmd; sleep 1; MSYS_NO_PATHCONV=1 "$ADB" -s $DEV logcat -d -s RobotCell | grep "POSE hmd" | tail -1 | grep -o "fwd=([^)]*)"
cmd clear_now; sleep 1; cmd "vroom on"; sleep 1; cmd "vroom here"; sleep 1
shot S03 aim-panel
cmd "goto S25"; cmd "vroom here"; sleep 1
shot - virtual-desk
cmd "place table 0 0"; sleep 2
shot S05 cell-pose-tab
shot S10 cell-joints-tab
shot S11 cell-pins-tab+plates
shot - pin-red-selected "select 3"
shot S22 cell-run-tab
shot - run-playing "run pins"
cmd "run stop"; sleep 1
shot S13 menu
shot S14 dialog-clear
cmd "ui dialog_cancel"; sleep 1
shot S21 coachmark
shot S09 hit-tag
shot S12 edge-plates
shot S07 wall-plate
shot S24 tcp-ball
SH="$OUT/$HEAD" PRE=0.3 SETTLE=3 timeout 120 bash "$ROOT/tools/qa_shot.sh" S15 snackbar-deleted | tail -2
shot S20 dialog-unavailable
cmd "goto S05"; sleep 1
MSYS_NO_PATHCONV=1 "$ADB" -s $DEV logcat -d -s RobotCell | grep -E "SIGHT|FOLLOW|FATAL" | tail -8
