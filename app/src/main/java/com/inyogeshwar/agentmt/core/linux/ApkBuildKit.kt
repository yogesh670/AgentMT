package com.inyogeshwar.agentmt.core.linux

/**
 * Everything needed to build Android apps on the phone, inside the Linux environment: OpenJDK 17, Gradle 8.9,
 * Android SDK platform 35 + build-tools 35, and a static arm64 aapt2 (Google only ships x86-64 aapt2 for Linux).
 * Gradle is pointed at that aapt2 through android.aapt2FromMavenOverride, the same setup AgentMT itself is built with.
 */
object ApkBuildKit {
    const val TITLE = "Install build tools"
    const val SDK = "/opt/android-sdk"
    const val BUILD_TOOLS = "35.0.0"
    private const val MARKER = "/opt/android-sdk/.agentmt-ready"

    fun ready(): Boolean = LinuxEnv.ready && LinuxEnv.hostPath(MARKER).exists()

    /** One line per component, written by the installer. */
    fun versions(): String = runCatching { LinuxEnv.hostPath(MARKER).readText().trim() }.getOrDefault("")

    val installScript: String = """
set -e
step() { echo; echo "== Step ${'$'}1/7 · ${'$'}2"; }
step 1 "Java 17, unzip, curl, git"
if command -v apt-get >/dev/null 2>&1; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  apt-get install -y openjdk-17-jdk-headless unzip curl git ca-certificates
else
  apk add --no-cache openjdk17-jdk unzip curl git bash
fi
JH=${'$'}(dirname ${'$'}(dirname ${'$'}(readlink -f ${'$'}(command -v javac))))
mkdir -p /opt && cd /opt
step 2 "Gradle 8.9 (130 MB)"
if [ ! -x /opt/gradle/bin/gradle ]; then
  curl -fL --progress-bar -o gradle.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip
  rm -rf gradle-8.9 && unzip -q gradle.zip && rm gradle.zip && rm -rf gradle && mv gradle-8.9 gradle
fi
S=$SDK
mkdir -p ${'$'}S/platforms ${'$'}S/build-tools ${'$'}S/licenses
step 3 "Android SDK platform 35 (64 MB)"
if [ ! -f ${'$'}S/platforms/android-35/android.jar ]; then
  curl -fL --progress-bar -o p.zip https://dl.google.com/android/repository/platform-35_r02.zip
  rm -rf ptmp && mkdir ptmp && unzip -q p.zip -d ptmp && rm -rf ${'$'}S/platforms/android-35 && mv ptmp/* ${'$'}S/platforms/android-35 && rm -rf p.zip ptmp
fi
step 4 "Android build-tools $BUILD_TOOLS (62 MB)"
if [ ! -f ${'$'}S/build-tools/$BUILD_TOOLS/source.properties ]; then
  curl -fL --progress-bar -o bt.zip https://dl.google.com/android/repository/build-tools_r35_linux.zip
  rm -rf btmp && mkdir btmp && unzip -q bt.zip -d btmp && rm -rf ${'$'}S/build-tools/$BUILD_TOOLS && mv btmp/* ${'$'}S/build-tools/$BUILD_TOOLS && rm -rf bt.zip btmp
fi
step 5 "ARM64 aapt2, aapt, zipalign (14 MB)"
curl -fL --progress-bar -o arm.zip https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip
rm -rf armtmp && mkdir armtmp && unzip -q -o arm.zip -d armtmp
cp armtmp/build-tools/* ${'$'}S/build-tools/$BUILD_TOOLS/ && chmod +x ${'$'}S/build-tools/$BUILD_TOOLS/*
rm -rf arm.zip armtmp
printf "\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n84831b9409646a918e30573bab4c9c91346d8abd\nd56f5187479451eabf01fb78af6dfcb131a6481e" > ${'$'}S/licenses/android-sdk-license
step 6 "Configuring Gradle"
cat > /etc/profile.d/android.sh <<EOF
export JAVA_HOME=${'$'}JH
export ANDROID_HOME=$SDK
export ANDROID_SDK_ROOT=$SDK
export GRADLE_HOME=/opt/gradle
export PATH=/opt/gradle/bin:${'$'}JH/bin:\${'$'}PATH
EOF
mkdir -p /root/.gradle
cat > /root/.gradle/gradle.properties <<EOF
android.aapt2FromMavenOverride=$SDK/build-tools/$BUILD_TOOLS/aapt2
org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8
kotlin.compiler.execution.strategy=in-process
kotlin.daemon.enabled=false
org.gradle.daemon=false
org.gradle.parallel=false
EOF
step 7 "Checking"
. /etc/profile.d/android.sh
J=${'$'}(java -version 2>&1 | head -1)
G=${'$'}(gradle --version 2>/dev/null | grep '^Gradle' | head -1)
A=${'$'}(${'$'}S/build-tools/$BUILD_TOOLS/aapt2 version 2>&1 | head -1)
printf "java: %s\n%s\naapt2: %s\nsdk: platform 35, build-tools $BUILD_TOOLS\n" "${'$'}J" "${'$'}G" "${'$'}A" | tee $MARKER
echo
echo "Build tools ready. Ask the agent to build an app, or run: gradle assembleDebug"
""".trimIndent()

    /** Re-check versions without reinstalling. */
    val checkScript = """
. /etc/profile.d/android.sh 2>/dev/null
java -version 2>&1 | head -1
gradle --version 2>/dev/null | grep '^Gradle'
$SDK/build-tools/$BUILD_TOOLS/aapt2 version 2>&1 | head -1
ls $SDK/platforms 2>/dev/null
""".trimIndent()

    /** Added to the system prompt so the agent knows how to build. */
    fun promptNote(): String = if (!ready()) "" else """
# Building Android apps
Build tools are installed in Linux: JDK 17, Gradle 8.9 (`gradle`), Android SDK at $SDK (platform 35, build-tools $BUILD_TOOLS, arm64 aapt2 already configured in ~/.gradle/gradle.properties).
- Build with run_command: `./gradlew assembleDebug` when the project has a wrapper, otherwise `gradle assembleDebug`. Use timeout_seconds 600; the first build downloads dependencies and is slow on a phone. Re-run if it times out, Gradle resumes.
- Never put an aapt2 override or sdk.dir in the project; the global config handles it. If a project sets compileSdk other than 35, Gradle downloads that platform itself.
- The APK lands in app/build/outputs/apk/debug/. Tell the user the full path so they can install it.
- On build errors: read the first error, fix the code, build again.
""".trimIndent()
}
