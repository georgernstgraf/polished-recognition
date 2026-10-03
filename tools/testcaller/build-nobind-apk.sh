#!/usr/bin/env bash
#
# Build an app APK whose RecognitionService no longer requires
# android.permission.BIND_RECOGNITION_SERVICE, so that an Android-11 caller can
# bind directly (see README.md § "Android 11 caveat").
#
# The shipped manifest is NEVER touched: this builds from a throw-away git
# worktree of the current commit.
#
# Usage:
#   tools/testcaller/build-nobind-apk.sh          # build, print APK path
#   tools/testcaller/build-nobind-apk.sh --clean  # remove the worktree
#
# Env: WORKTREE (default /tmp/polished-nobind), ANDROID_HOME (default ~/Android/Sdk).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKTREE="${WORKTREE:-/tmp/polished-nobind}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_HOME

cleanup_worktree() {
    git -C "$REPO_ROOT" worktree remove "$WORKTREE" --force >/dev/null 2>&1 || true
    rm -rf "$WORKTREE"
}

if [[ "${1:-}" == "--clean" ]]; then
    cleanup_worktree
    echo "removed $WORKTREE"
    exit 0
fi

for f in keystore.properties app/release.keystore; do
    [[ -f "$REPO_ROOT/$f" ]] || { echo "missing $f (needed for a signed release APK)" >&2; exit 1; }
done

cleanup_worktree
git -C "$REPO_ROOT" worktree add --detach "$WORKTREE" HEAD >/dev/null
cp "$REPO_ROOT/keystore.properties" "$WORKTREE/keystore.properties"
cp "$REPO_ROOT/app/release.keystore" "$WORKTREE/app/release.keystore"

python3 - "$WORKTREE/app/src/main/AndroidManifest.xml" <<'PY'
import re, sys, pathlib
path = pathlib.Path(sys.argv[1])
text = path.read_text()
patched = re.sub(
    r'\n\s*android:permission="android\.permission\.BIND_RECOGNITION_SERVICE"',
    '',
    text,
)
if patched == text:
    sys.exit("permission attribute not found — has the service declaration changed?")
path.write_text(patched)
PY

( cd "$WORKTREE" && ./gradlew assembleRelease >/dev/null )

echo "APK: $WORKTREE/app/build/outputs/apk/release/app-release.apk"
echo "Install it, run the caller, then restore the shipped build and run:"
echo "  $0 --clean"
