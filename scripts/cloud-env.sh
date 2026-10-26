#!/usr/bin/env bash
# Source this in cloud tasks. Paths contain no credentials.
export MOONSHOT_TOOLS=/workspace/toolchains
export JAVA_HOME="$MOONSHOT_TOOLS/jdk-17.0.21+9"
export ANDROID_HOME="$MOONSHOT_TOOLS/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$MOONSHOT_TOOLS/android-home"
export ANDROID_AVD_HOME="$MOONSHOT_TOOLS/avd"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export GRADLE_USER_HOME="$MOONSHOT_TOOLS/gradle-home"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

if [[ -f /etc/ssl/certs/java/cacerts ]]; then
  export SDKMANAGER_OPTS="${SDKMANAGER_OPTS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
fi
