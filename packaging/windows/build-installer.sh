#!/usr/bin/env bash
# Builds ClinicQueue-Setup.exe (a Windows installer) from Linux or macOS. Needs: JDK 17+, Go 1.21+,
# Python 3 with pip, and internet access to download a Windows Java runtime (about 50 MB, cached).
#
#   ./packaging/windows/build-installer.sh
#
# Result: dist/ClinicQueue-Setup.exe
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
WORK="$ROOT/out/installer"
DIST="$ROOT/dist"

echo "1/6 Building the app"
"$ROOT/build.sh" >/dev/null

echo "2/6 Getting a Windows Java runtime (cached in out/installer/runtime-cache)"
CACHE="$WORK/runtime-cache"
if [ ! -f "$CACHE/jdk4py/java-runtime/bin/javaw.exe" ]; then
  rm -rf "$CACHE" "$WORK/dl"
  mkdir -p "$CACHE" "$WORK/dl"
  # jdk4py publishes a complete Windows OpenJDK runtime as a Python wheel (a zip file).
  pip download --no-deps --only-binary=:all: --platform win_amd64 --python-version 3.12 \
      --break-system-packages jdk4py -d "$WORK/dl" >/dev/null 2>&1 \
    || pip download --no-deps --only-binary=:all: --platform win_amd64 --python-version 3.12 jdk4py -d "$WORK/dl" >/dev/null
  python3 -m zipfile -e "$WORK"/dl/jdk4py-*.whl "$CACHE" >/dev/null
fi
RUNTIME="$CACHE/jdk4py/java-runtime"
[ -f "$RUNTIME/bin/javaw.exe" ] || { echo "Runtime download failed: javaw.exe not found" >&2; exit 1; }

echo "3/6 Building the uninstaller"
mkdir -p "$DIST" "$WORK/payload"
cd "$HERE"
GOFLAGS=-mod=mod CGO_ENABLED=0 GOOS=windows GOARCH=amd64 \
  go build -trimpath -ldflags "-H windowsgui -s -w" \
  -o "$WORK/Uninstall ClinicQueue.exe" ./cmd/uninstall

echo "4/6 Assembling the payload"
python3 - "$ROOT" "$HERE" "$WORK" "$RUNTIME" <<'PY'
import os, sys, zipfile
root, here, work, runtime = sys.argv[1:5]
out = os.path.join(here, "cmd", "setup", "payload.zip")
SKIP_DIRS = {"include"}  # C headers: not needed to run Java
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    z.write(os.path.join(root, "out", "clinicqueue.jar"), "app/clinicqueue.jar")
    z.write(os.path.join(here, "clinicqueue.ico"), "clinicqueue.ico")
    z.write(os.path.join(work, "Uninstall ClinicQueue.exe"), "Uninstall ClinicQueue.exe")
    z.write(os.path.join(here, "README-windows.txt"), "README.txt")
    for base, dirs, files in os.walk(runtime):
        dirs[:] = [d for d in dirs if not (os.path.relpath(base, runtime) == "." and d in SKIP_DIRS)]
        for f in files:
            p = os.path.join(base, f)
            z.write(p, "runtime/" + os.path.relpath(p, runtime).replace(os.sep, "/"))
print("payload:", round(os.path.getsize(out) / 1e6, 1), "MB")
PY

echo "5/6 Building the installer"
GOFLAGS=-mod=mod CGO_ENABLED=0 GOOS=windows GOARCH=amd64 \
  go build -trimpath -ldflags "-H windowsgui -s -w" \
  -o "$DIST/ClinicQueue-Setup.exe" ./cmd/setup

echo "6/6 Done"
ls -la "$DIST/ClinicQueue-Setup.exe"
sha256sum "$DIST/ClinicQueue-Setup.exe" | tee "$DIST/ClinicQueue-Setup.exe.sha256"
