#!/usr/bin/env bash
set -euo pipefail
cd /workspace/moonshot
source scripts/cloud-env.sh
mkdir -p "$MOONSHOT_TOOLS" "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
if [[ ! -x "$JAVA_HOME/bin/jlink" ]]; then
  curl -fL --retry 2 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz' -o "$MOONSHOT_TOOLS/jdk.tar.gz"
  curl -fL --retry 2 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz.sha256.txt' -o "$MOONSHOT_TOOLS/jdk.sha256"
  python3 - <<'PY'
import pathlib,hashlib,tarfile
p=pathlib.Path('/workspace/toolchains/jdk.tar.gz')
assert hashlib.sha256(p.read_bytes()).hexdigest()==p.with_name('jdk.sha256').read_text().split()[0]
with tarfile.open(p) as archive: archive.extractall('/workspace/toolchains',filter='data')
PY
fi
if [[ ! -x "$MOONSHOT_TOOLS/sdk12/cmdline-tools/bin/sdkmanager" ]]; then
  curl -fL --retry 2 https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o "$MOONSHOT_TOOLS/android-tools12.zip"
  python3 - <<'PY'
import pathlib,hashlib,zipfile
p=pathlib.Path('/workspace/toolchains/android-tools12.zip')
# Digest from Google's official Android repository metadata, downloaded over TLS.
assert hashlib.sha1(p.read_bytes()).hexdigest()=='d313adb7aedccf6cf0cfca51ec180f0059f5f8f8'
zipfile.ZipFile(p).extractall('/workspace/toolchains/sdk12')
PY
  chmod +x "$MOONSHOT_TOOLS/sdk12/cmdline-tools/bin/"*
fi
# Use the machine's trusted proxy CA; never disable TLS verification.
python3 - <<'PY'
import pathlib,os,urllib.parse
p=pathlib.Path('/workspace/toolchains/gradle-home/gradle.properties')
lines=p.read_text().splitlines() if p.exists() else []
updates={}
u=urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY',''))
if u.hostname:
 for protocol in ['http','https']:
  updates[f'systemProp.{protocol}.proxyHost']=u.hostname
  updates[f'systemProp.{protocol}.proxyPort']=str(u.port or 80)
if pathlib.Path('/etc/ssl/certs/java/cacerts').exists():
 updates['systemProp.javax.net.ssl.trustStore']='/etc/ssl/certs/java/cacerts'
lines=[line for line in lines if line.split('=',1)[0] not in updates]
p.write_text('\n'.join(lines+[f'{k}={v}' for k,v in updates.items()])+'\n')
PY
"$MOONSHOT_TOOLS/sdk12/cmdline-tools/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses < <(yes) > "$MOONSHOT_TOOLS/android-licenses.log" 2>&1
"$MOONSHOT_TOOLS/sdk12/cmdline-tools/bin/sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
# ANDROID_HOME is sufficient; do not overwrite a developer's local.properties.
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
