#!/usr/bin/env bash
# Device contrast audit: measure the WCAG 2.1 contrast of every app text
# element ON THE ATTACHED DEVICE, in light and/or dark mode.
#
# The script opens Settings, dumps the view hierarchy (uiautomator) plus a
# screenshot (screencap) and hands both to scripts/contrast_audit.py, which
# measures the real pixels per element. Nothing is written into the repo —
# artifacts (dump, screenshot, JSON, table) land in a temp dir or --out.
#
# Exit codes: 0 = all modes pass, 1 = at least one element below threshold,
# 2 = environment problem (no device, locked device, missing tooling).
#
# Usage:
#   scripts/contrast-audit.sh                       # light + dark, auto device
#   ANDROID_SERIAL=R7SRB06XF5K scripts/contrast-audit.sh --mode dark
#   scripts/contrast-audit.sh --mode manual --out /tmp/contrast   # you switch
set -euo pipefail

PACKAGE="com.georgernstgraf.polishedrecognition"
ACTIVITY="$PACKAGE/.ui.SettingsActivity"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AUDIT="$HERE/contrast_audit.py"

MODE="both"
OUT=""
SERIAL_ARG="${ANDROID_SERIAL:-}"

usage() {
    cat <<'EOF'
Device contrast audit: WCAG 2.1 ratios for every app text element on the
attached device, measured from real pixels (uiautomator dump + screencap).

  --mode light|dark|both|manual   which system mode(s) to measure (both)
                                   manual: you switch, the script measures
  --out DIR                       artifact dir (default: a fresh mktemp dir)
  --serial SERIAL                 adb serial (default: ANDROID_SERIAL or the
                                   single attached device)
  -h, --help                      this text

Exit codes: 0 all modes pass, 1 element(s) below threshold, 2 environment.
Nothing is written into the repository.
EOF
    exit 2
}

die() {
    echo "contrast-audit: $*" >&2
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --mode) MODE="${2:?--mode needs a value}"; shift 2 ;;
        --mode=*) MODE="${1#*=}"; shift ;;
        --out) OUT="${2:?--out needs a path}"; shift 2 ;;
        --out=*) OUT="${1#*=}"; shift ;;
        --serial) SERIAL_ARG="${2:?--serial needs a value}"; shift 2 ;;
        --serial=*) SERIAL_ARG="${1#*=}"; shift ;;
        -h|--help) usage ;;
        *) die "unknown argument: $1 (try --help)" ;;
    esac
done

case "$MODE" in
    light|dark|both|manual) ;;
    *) die "--mode must be light, dark, both or manual (got: $MODE)" ;;
esac

command -v adb >/dev/null 2>&1 || die "adb not found in PATH"
[ -f "$AUDIT" ] || die "missing $AUDIT"
PY=python3
command -v "$PY" >/dev/null 2>&1 || die "python3 not found in PATH"
"$PY" -c 'import PIL' 2>/dev/null || die "Pillow is not installed for $PY"

# --- device ----------------------------------------------------------------

resolve_serial() {
    if [ -n "$SERIAL_ARG" ]; then
        echo "$SERIAL_ARG"
        return
    fi
    local devices=()
    while read -r serial state; do
        [ "$state" = "device" ] && devices+=("$serial")
    done < <(adb devices | awk 'NR > 1 && NF >= 2 {print $1, $2}')
    if [ "${#devices[@]}" -eq 0 ]; then
        die "no device attached — connect one over USB (or set ANDROID_SERIAL)"
    elif [ "${#devices[@]}" -gt 1 ]; then
        die "more than one device attached (${devices[*]}) — pick one with --serial"
    fi
    echo "${devices[0]}"
}

SERIAL="$(resolve_serial)"
ADB=(adb -s "$SERIAL")
"${ADB[@]}" get-state >/dev/null 2>&1 || die "device $SERIAL is not reachable via adb"
echo "device: $SERIAL ($("${ADB[@]}" shell getprop ro.product.model | tr -d '\r'), Android $("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r'))"

# --- device state: awake, unlocked ----------------------------------------

wake_device() {
    if "${ADB[@]}" shell dumpsys power 2>/dev/null | grep -q 'mWakefulness=Asleep'; then
        "${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
        sleep 1
    fi
}

keyguard_showing() {
    "${ADB[@]}" shell dumpsys window 2>/dev/null \
        | sed -n '/KeyguardServiceDelegate/,/KeyguardStateMonitor/p' \
        | grep -q 'showing=true'
}

require_unlocked() {
    wake_device
    if keyguard_showing; then
        # `dismiss-keyguard` only succeeds on a swipe lock (no credential).
        "${ADB[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
        sleep 1
    fi
    if keyguard_showing; then
        die "the device is locked by a secure keyguard (PIN/pattern/password).
  The audit measures pixels, so the screen must be unlocked:
  unlock $SERIAL, keep the screen on, and run the script again."
    fi
}

foreground_is_ours() {
    "${ADB[@]}" shell dumpsys window 2>/dev/null | grep -q "mCurrentFocus=.*$PACKAGE"
}

open_settings() {
    "${ADB[@]}" shell am start -W -n "$ACTIVITY" >/dev/null 2>&1 || true
    for _ in $(seq 1 30); do
        if foreground_is_ours; then
            return 0
        fi
        sleep 0.5
    done
    return 1
}

# --- screen metrics --------------------------------------------------------

effective_density() {
    "${ADB[@]}" shell wm density 2>/dev/null \
        | tr -d '\r' | sed -n 's/.*density: *\([0-9]\{1,\}\).*/\1/p' | tail -1
}

font_scale() {
    local value
    value="$("${ADB[@]}" shell settings get system font_scale 2>/dev/null | tr -d '\r')"
    case "$value" in
        ''|null) echo "1.0" ;;
        *) echo "$value" ;;
    esac
}

# --- night mode ------------------------------------------------------------

night_state() {
    "${ADB[@]}" shell cmd uimode night 2>/dev/null | tr -d '\r' \
        | sed -n 's/.*[Nn]ight mode: *\(yes\|no\|auto\).*/\1/p' | tail -1
}

current_mode_label() {
    case "$(night_state)" in
        yes) echo "dark" ;;
        no) echo "light" ;;
        auto) echo "auto" ;;
        *) echo "unknown" ;;
    esac
}

# switch_night yes|no — returns 1 when the device refuses the write.
switch_night() {
    "${ADB[@]}" shell cmd uimode night "$1" >/dev/null 2>&1 || true
    sleep 1
    [ "$(night_state)" = "$1" ]
}

manual_prompt() {
    local want="$1"
    echo
    echo "  adb cannot switch the system night mode on this device — some OEM"
    echo "  builds block the secure-settings write (documented for the OnePlus)."
    echo "  Please set it ON THE DEVICE: Settings -> Display -> Dark mode = $want"
    echo "  (or pull down the quick settings and toggle it)."
    if [ ! -t 0 ]; then
        die "no TTY for the manual prompt — run interactively, or pass --mode light|dark"
    fi
    read -r -p "  press Enter when the device shows the '$want' UI … " _
}

# --- capture ---------------------------------------------------------------

# wait_stable: two identical dumps in a row, so the screenshot cannot catch a
# half-drawn window (rotation/animation/theme recreation).
wait_stable() {
    local previous="" current
    for _ in $(seq 1 20); do
        current="$("${ADB[@]}" exec-out uiautomator dump /dev/tty 2>/dev/null | md5sum)"
        if [ -n "$previous" ] && [ "$current" = "$previous" ] && [ "$current" != "$(printf '' | md5sum)" ]; then
            return 0
        fi
        previous="$current"
        sleep 0.5
    done
    return 1
}

capture() {
    local label="$1"
    wait_stable || echo "contrast-audit: warning: window did not stabilise" >&2
    "${ADB[@]}" exec-out uiautomator dump /dev/tty > "$OUT/$label.xml" 2>/dev/null
    "${ADB[@]}" exec-out screencap -p > "$OUT/$label.png"
    [ -s "$OUT/$label.xml" ] || die "empty uiautomator dump for mode $label"
    [ -s "$OUT/$label.png" ] || die "empty screencap for mode $label"
    if ! grep -q "package=\"$PACKAGE\"" "$OUT/$label.xml"; then
        # A system dialog (permission prompt, Play-services password save, …)
        # can own the foreground and silently produce an empty audit.
        die "no $PACKAGE window in the dump for mode $label — something else is in front:
  $("${ADB[@]}" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r')
  dismiss that window on the device and run the script again."
    fi
}

audit() {
    local label="$1" rc=0
    capture "$label"
    "$PY" "$AUDIT" \
        --dump "$OUT/$label.xml" \
        --shot "$OUT/$label.png" \
        --mode "$label" \
        --density-dpi "$DENSITY" \
        --font-scale "$FONT_SCALE" \
        --json "$OUT/$label.json" || rc=$?
    echo
    return "$rc"
}

# --- run -------------------------------------------------------------------

wake_device
require_unlocked

if [ -z "$OUT" ]; then
    OUT="$(mktemp -d "${TMPDIR:-/tmp}/contrast-audit.XXXXXX")"
else
    mkdir -p "$OUT"
fi
OUT="$(cd "$OUT" && pwd)"

DENSITY="$(effective_density)"
FONT_SCALE="$(font_scale)"
[ -n "$DENSITY" ] || die "could not read the screen density (wm density)"

START_NIGHT="$(night_state)"
START_MODE="$(current_mode_label)"
echo "artifacts: $OUT  (density ${DENSITY} dpi, font_scale ${FONT_SCALE}, started in ${START_MODE})"
CHANGED=0
restore_mode() {
    if [ "$CHANGED" -eq 1 ] && [ -n "$START_NIGHT" ]; then
        switch_night "$START_NIGHT" \
            || echo "contrast-audit: warning: could not restore night mode=$START_NIGHT" >&2
    fi
}
trap restore_mode EXIT

open_settings || die "could not bring $ACTIVITY to the foreground — is the app installed and the screen unlocked?"

FAILED_MODES=""
case "$MODE" in
    light|dark)
        want="yes"; [ "$MODE" = "light" ] && want="no"
        if [ "$(night_state)" != "$want" ]; then
            CHANGED=1
            if ! switch_night "$want"; then
                manual_prompt "$MODE"
                if [ "$(night_state)" != "$want" ]; then
                    actual="$(current_mode_label)"
                    echo "contrast-audit: warning: device is in '$actual', not '$MODE' — measuring what is active" >&2
                    MODE="$actual"
                fi
            fi
            open_settings || die "Settings disappeared after the mode switch"
        fi
        audit "$MODE" || FAILED_MODES="$MODE"
        ;;
    both)
        for pair in "light:no" "dark:yes"; do
            label="${pair%%:*}"
            want="${pair##*:}"
            CHANGED=1
            if ! switch_night "$want"; then
                manual_prompt "$label"
            fi
            open_settings || die "Settings disappeared after the mode switch"
            if [ "$(night_state)" != "$want" ]; then
                actual="$(current_mode_label)"
                echo "contrast-audit: warning: device is in '$actual', not '$label'" >&2
            fi
            audit "$label" || FAILED_MODES="$FAILED_MODES $label"
        done
        if cmp -s "$OUT/light.png" "$OUT/dark.png"; then
            echo "contrast-audit: warning: light and dark screenshots are identical —" \
                 "the night-mode switch did not change the rendering" >&2
        fi
        ;;
    manual)
        [ -t 0 ] || die "manual mode needs a TTY — run it interactively, or pass --mode light|dark"
        echo "Set the mode you want to measure on the device, then press Enter."
        read -r -p "  press Enter to capture … " _ || die "no input on stdin"
        label="$(current_mode_label)"
        audit "$label" || FAILED_MODES="$label"
        ;;
esac

echo
if [ -n "${FAILED_MODES// /}" ]; then
    echo "contrast-audit: FAILED in mode(s):$FAILED_MODES"
    exit 1
fi
echo "contrast-audit: all modes pass (artifacts in $OUT)"
