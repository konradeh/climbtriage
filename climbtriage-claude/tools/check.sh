#!/usr/bin/env bash
# Every reproducible check in the repo. Needs ./.venv (Python) and ./.toolchain (tools/setup-toolchain.sh).
# Set CLIMBTRIAGE_BACKEND_URL to also run the live Kotlin-client ↔ backend contract test.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PY="$ROOT/.venv/Scripts/python"; [ -x "$PY" ] || PY="$ROOT/.venv/bin/python"

echo "== backend (pytest)";   (cd "$ROOT/backend" && "$PY" -m pytest -q)
echo "== eval (pytest)";      (cd "$ROOT/eval" && "$PY" -m pytest -q)
echo "== android (unit tests, lint, debug APK)"
(cd "$ROOT/android" && source env.sh && ./gradlew --console=plain -q testDebugUnitTest lintDebug assembleDebug)
echo "APK: $ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
