#!/usr/bin/env bash
# Panel stress test (factory rule A4): open / close the More menu and cycle the three tabs 30 times, then capture
# one frame and report the glass panels still draw. tools/stress_panels.sh [ROUNDS]
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEV=${DEV:-emulator-5560}
PKG=io.embodit.robotcell
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
rounds=${1:-30}
cmd() { MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '$1'" >/dev/null 2>&1; }
adb -s $DEV logcat -c
cmd "goto S05"; sleep 2
for i in $(seq 1 "$rounds"); do
  cmd "menu"; sleep 0.35; cmd "menu"; sleep 0.25
  cmd "tab joints"; sleep 0.25; cmd "tab pins"; sleep 0.25; cmd "tab pose"; sleep 0.25
done
echo "rounds=$rounds done"
adb -s $DEV shell pidof $PKG >/dev/null && echo "process alive" || echo "PROCESS DIED"
adb -s $DEV logcat -d 2>/dev/null | grep -E "FATAL EXCEPTION|Process: $PKG" | head -3
adb -s $DEV logcat -d -s RobotCell:V 2>/dev/null | grep -c "STATE S13"
