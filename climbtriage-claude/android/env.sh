# Source from android/: points Gradle at the project-local portable toolchain (see ../.toolchain).
T="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.toolchain" && pwd)"
export JAVA_HOME="$(cygpath -w "$T/jdk" 2>/dev/null || echo "$T/jdk")"
export GRADLE_USER_HOME="$(cygpath -w "$T/gradle-home" 2>/dev/null || echo "$T/gradle-home")"
export ANDROID_HOME="$(cygpath -w "$T/android-sdk" 2>/dev/null || echo "$T/android-sdk")"
