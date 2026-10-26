#!/usr/bin/env bash
# Standalone Linux CI setup: use the same Java-17-compatible SDK tools as cloud setup.
set -euo pipefail
: "${JAVA_HOME:?Select JDK 17 before Android setup}"
: "${ANDROID_HOME:?Set a dedicated Android SDK directory}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
archive_dir="$(mktemp -d)"
trap 'rm -rf "$archive_dir"' EXIT
curl --fail --location --retry 3 \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
  --output "$archive_dir/tools.zip"
python3 - "$archive_dir/tools.zip" "$ANDROID_HOME" <<'PY'
import hashlib, pathlib, shutil, sys, zipfile
archive, sdk = map(pathlib.Path, sys.argv[1:])
expected = '2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258'
if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
    raise SystemExit('Android command-line tools checksum mismatch')
target = sdk / 'cmdline-tools' / 'moonshot-12'
target.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(archive) as z:
    for entry in z.infolist():
        relative = pathlib.PurePosixPath(entry.filename)
        if relative.parts[0] != 'cmdline-tools' or '..' in relative.parts:
            raise SystemExit('Unexpected archive path')
        if entry.is_dir():
            continue
        path = target.joinpath(*relative.parts[1:])
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(z.read(entry))
        if path.parent == target / 'bin':
            path.chmod(0o755)
# Emulator-runner also resolves sdkmanager via cmdline-tools/latest.
latest = sdk / 'cmdline-tools' / 'latest'
if not latest.exists():
    latest.symlink_to('moonshot-12', target_is_directory=True)
PY
sdk_bin="$ANDROID_HOME/cmdline-tools/moonshot-12/bin"
export PATH="$sdk_bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
# Process substitution avoids yes receiving SIGPIPE under the CI shell's pipefail.
"$sdk_bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses < <(yes)
"$sdk_bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
  'platform-tools' 'platforms;android-36' 'build-tools;35.0.0'
if [[ -n "${GITHUB_ENV:-}" ]]; then
  printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\n' "$ANDROID_HOME" "$ANDROID_HOME" >> "$GITHUB_ENV"
fi
if [[ -n "${GITHUB_PATH:-}" ]]; then
  printf '%s\n' "$sdk_bin" "$ANDROID_HOME/platform-tools" "$ANDROID_HOME/emulator" >> "$GITHUB_PATH"
fi
