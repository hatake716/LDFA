# Bundled proot native libraries (Play/targetSdk-35 W^X bypass)

These are the proot runtime, repackaged as Android native libraries so they can
be executed from `nativeLibraryDir` under targetSdk >= 29 (where a binary written
to the app data dir cannot be execve'd). The Debian rootfs stays in the data dir
and runs THROUGH this proot — see ProotRuntime.

Provenance: taken from Termux's apt package `proot` (arm64), then rewritten with
patchelf: `--set-rpath '$ORIGIN'`, `--replace-needed libtalloc.so.2 libtalloc.so`,
and libtalloc's SONAME set to `libtalloc.so`. The loader is proot's
`libexec/proot/loader` (renamed). Requires `jniLibs.useLegacyPackaging = true`
(extractNativeLibs=true) so the .so files land on disk and are executable.

TODO: add armeabi-v7a / x86 / x86_64 variants; ideally build proot from source in
CI rather than vendoring device binaries.
