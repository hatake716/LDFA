# Bundled PRoot runtime

LDFA packages the PRoot executable, its loader, talloc and android-shmem as native
libraries. Android extracts them into `nativeLibraryDir`, where executable code
from the signed APK is allowed to run. Termux and Debian files remain inside the
application data directory and are executed through this runtime.

`jniLibs.useLegacyPackaging = true` (`extractNativeLibs=true`) is required.
The Play AAB contains ARM64. The validation APK also includes x86_64 so the same
signed APK can be tested on an emulator and then installed on an ARM64 device.

- `arm64-v8a`: retained from the existing Play baseline; originally extracted from
  the Termux ARM64 apt packages and repackaged with patchelf.
- `x86_64`: built from source for LDFA 1.2.0 using termux-packages commit
  `0223902ddb42a5572812044e64310ada0f658ff2` and the tracked LDFA patch.
  Packages: PRoot 5.1.107.92, talloc 2.4.3, android-shmem 0.7.

Reproduction instructions and the packaging script are in
[tools/bootstrap](../../../../tools/bootstrap/README.md). The script sets the
runtime's RUNPATH to `$ORIGIN`, rewrites `libtalloc.so.2` to `libtalloc.so`, and sets
talloc's SONAME accordingly, with a 16KB page size for patchelf.
`libexec/proot/loader` becomes `libpdrt-loader.so`.

The names intentionally avoid `proot`: PRoot-Distro rejects a parent tracer whose
process name contains that word. ARM32 and x86-32 runtimes are not included.

Check the final packaged ELF LOAD segments with
`scripts/verify-release-contents.py`; runtime verification on a 16KB environment
is a separate acceptance check.
