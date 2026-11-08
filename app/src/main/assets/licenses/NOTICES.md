AgentMT open source notices

AgentMT uses the open source software below. Each keeps its own license; the full texts are in this screen.

BUNDLED IN THE APP (all builds)
- AndroidX (Core, Activity, Lifecycle, Navigation, Room, Compose UI, Material 3, Material Icons, SplashScreen): Apache-2.0. Copyright The Android Open Source Project. https://android.googlesource.com/platform/frameworks/support
- Kotlin standard library and kotlinx.coroutines: Apache-2.0. Copyright JetBrains s.r.o. https://github.com/JetBrains/kotlin
- OkHttp 4.12.0 and Okio: Apache-2.0. Copyright Square, Inc. https://github.com/square/okhttp
- Termux terminal-view and terminal-emulator v0.118.1: Apache-2.0. Copyright Termux developers, based on Android Terminal Emulator by Jack Palevich. https://github.com/termux/termux-app/tree/v0.118.1/terminal-view
  libtermux.so is rebuilt by us from the same Apache-2.0 source (termux.c, unchanged) with 16 KB page alignment.
  (Only these two Apache-2.0 libraries are used. No GPLv3 code from the Termux app is included.)

BUNDLED IN THE FULL BUILD ONLY (GitHub release)
- PRoot 5.1.107.102 (Termux build), as libproot.so and libproot-loader.so: GPL-2.0. Copyright STMicroelectronics and PRoot contributors.
  Source: https://github.com/termux/proot/archive/v5.1.107.102.zip and the build recipe https://github.com/termux/termux-packages/tree/master/packages/proot
  Modification: the binary's NEEDED entry was renamed from libtalloc.so.2 to libtalloc.so so Android installs it. Nothing else was changed.
  PRoot runs as a separate program; it is not linked into AgentMT.
- talloc 2.5.0 (Termux build), as libtalloc.so: LGPL-3.0-or-later (the Termux package labels it GPL-3.0; both texts are included). Copyright Andrew Tridgell and Samba team.
  Source: https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz and https://github.com/termux/termux-packages/tree/master/packages/libtalloc
  Used only by PRoot. You may replace it with your own build.
- libandroid-shmem 0.7 (Termux build), as libandroid-shmem.so: BSD-3-Clause (text in BSD-3-Clause-libandroid-shmem.txt). Copyright Sergii Pylypenko and Fredrik Fornwall.
  Source: https://github.com/termux/libandroid-shmem. Needed by PRoot for System V shared memory. Unmodified.
  We will also give you the exact source of the PRoot and talloc versions we ship, on request, for three years from the release date.

DOWNLOADED ONLY WHEN YOU ASK (full build, not part of the app)
- Ubuntu Base 24.04.5 (Canonical), Alpine Linux minirootfs 3.24.4, and packages you install with apt, apk or pip: each package under its own license, fetched from the official mirrors. Ubuntu is a trademark of Canonical Ltd.; Alpine Linux is a trademark of its project. AgentMT is not affiliated with either.
- APK build kit: OpenJDK 17 (GPL-2.0 with Classpath Exception), Gradle 8.9 (Apache-2.0), Android SDK platform and build-tools (Android SDK License, accepted by you during install), aapt2 arm64 build from https://github.com/lzhiyong/android-sdk-tools (Apache-2.0).
- Skills, plugins and userscripts you install from GitHub, skills.sh or Greasy Fork keep their authors' licenses.

IDEAS, NOT CODE
Some features were designed after studying these projects; no code was copied from them:
opencode (MIT), Claude Code plugin layout (format only), proot-distro (GPL-3.0), termux-tools (GPL-3.0), Termux:API (GPL-3.0), Tampermonkey userscript format (metadata format only).
