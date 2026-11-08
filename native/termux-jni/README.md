# libtermux.so (16 KB page aligned)

`termux.c` is the JNI part of Termux's terminal-emulator library v0.118.1 (Apache-2.0, from
https://github.com/termux/termux-app/tree/v0.118.1/terminal-emulator/src/main/jni), unchanged.

The prebuilt libtermux.so inside the terminal-emulator AAR is 4 KB page aligned, which Google Play rejects for apps
targeting Android 15+. We rebuild it with `-Wl,-z,max-page-size=16384` and ship ours from app/src/main/jniLibs
(`pickFirsts` in app/build.gradle.kts makes ours win).

Build (any host with clang + lld and the NDK r27c sysroot; we used zig 0.13's clang):

    clang --target=aarch64-linux-android26 --sysroot=$NDK_SYSROOT -fuse-ld=lld -shared -fPIC -std=c11 -Os \
      -fno-stack-protector -nostdlib -Wl,-z,max-page-size=16384 -Wl,-soname,libtermux.so \
      $NDK_SYSROOT/usr/lib/aarch64-linux-android/26/crtbegin_so.o termux.c \
      $NDK_SYSROOT/usr/lib/aarch64-linux-android/26/crtend_so.o -L$NDK_SYSROOT/usr/lib/aarch64-linux-android/26 -lc -ldl \
      -o libtermux.so

Repeat for x86_64-linux-android, armv7a-linux-androideabi (lib dir arm-linux-androideabi) and i686-linux-android.
