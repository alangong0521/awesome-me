# Self-contained Android command-line build environment (macOS arm64)
# Usage: source /Users/yourname/remote-terminal/env.sh

export JAVA_HOME=/Users/yourname/remote-terminal/jdk/Contents/Home
export ANDROID_HOME=/Users/yourname/remote-terminal/android-sdk
export ANDROID_SDK_ROOT=/Users/yourname/remote-terminal/android-sdk
export PATH=$JAVA_HOME/bin:/Users/yourname/remote-terminal/gradle/gradle-8.9/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH
