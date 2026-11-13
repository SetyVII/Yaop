#!/usr/bin/env bash
# Dev aid: debug build with demo data inside a phone-sized frame on a connected tablet. Needs adb (USB).
# usage: phone.sh <out.png> [extra am args]  — relaunch YAOP (debug) with demo data in the phone frame, screenshot
# set ANDROID_SERIAL when more than one device is attached
OUT="$1"; shift
adb shell am force-stop org.yaop.stress
adb shell am start -n org.yaop.stress/org.yaop.MainActivity --ez seed_demo true --ez phone_frame true "$@" >/dev/null
sleep 9
adb shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q org.yaop.stress && adb exec-out screencap -p > "$OUT" && echo shot
