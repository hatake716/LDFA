# Third-party notices

Linux Desktop for Android integrates upstream open-source projects into one APK. This file records the principal sources and licenses; the authoritative copyright and license files remain in each pinned source tree.

## Termux application runtime

- Project: `termux/termux-app`
- Source location: `vendor/termux-app`
- Pinned commit: `3df69d1da197dd9bd71a3bafd902dffd720576b4`
- Main license: GNU General Public License version 3 only
- Upstream license file: `vendor/termux-app/LICENSE.md`

Upstream identifies exceptions for the terminal emulator/view libraries and parts of `termux-shared`. Their own license files and notices are preserved in the submodule.

This project modifies Termux's Android packaging so the runtime is built as a library inside the unified application. The app retains the fixed Termux prefix path `/data/data/com.termux/files/usr` and uses the package name `com.termux`.

## Termux:X11

- Project: `termux/termux-x11`
- Source location: `vendor/termux-x11`
- Pinned commit: `50ac80fb2d4a475e323e752d17fcc0483c3c99fc`
- Main license: GNU General Public License version 3
- Upstream license file: `vendor/termux-x11/LICENSE`

The upstream display Activity, AIDL interfaces and native `libXlorie` X server are built into the unified APK. Since v0.4.0 the X server is launched from the embedded Termux runtime with Android's `/system/bin/app_process` and the upstream `com.termux.x11.CmdEntryPoint` process model. The integration patch only adapts native-library lookup so the app_process instance loads `libXlorie.so` from the installed unified APK's `nativeLibraryDir`.

## Termux package bootstrap

The build downloads architecture-specific Termux bootstrap archives from the official `termux/termux-packages` releases. The exact release and SHA-256 values are declared in `termux-runtime/build.gradle`. The bootstrap contains packages with their own licenses and package metadata.

## Ubuntu and XFCE

Ubuntu is downloaded at runtime through PRoot Distro and is not bundled in the APK. Ubuntu, XFCE, Fcitx5, Mozc, fonts, and all packages installed into a user-created environment remain under their respective upstream licenses. Package copyright files are available inside Ubuntu under `/usr/share/doc/<package>/copyright`.

## Android and Java dependencies

AndroidX, Material Components, Kotlin, kotlinx.coroutines and other Gradle dependencies retain their upstream licenses. Dependency coordinates are declared in the Gradle build files. Generated APK metadata includes applicable packaged license resources except where duplicate metadata must be excluded during Android packaging.

## Source availability

The corresponding source for the unified APK consists of this repository, its pinned Git submodules, build scripts, and configuration. Clone it with:

```bash
git clone --recurse-submodules https://github.com/hatake716/Linux-Desktop-for-Android.git
```

When distributing APKs, distribute or provide equivalent access to the corresponding source for the same revision, as required by GPLv3.
