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

The upstream display Activity, AIDL interfaces and native `libXlorie` X server are built into the unified APK. LDFA starts Xorg in a private Android foreground-service process and passes the X connection to the viewer over the upstream Binder interface. Reproducible build-time overlays harden JNI ABI usage, renderer lifecycle, EGL error handling and reconnect teardown while retaining the pinned upstream source tree.

## Termux package bootstrap

The build downloads architecture-specific Termux bootstrap archives from the official `termux/termux-packages` releases. The exact release and SHA-256 values are declared in `termux-runtime/build.gradle`. The bootstrap contains packages with their own licenses and package metadata.

## Debian and XFCE

Debian is downloaded at runtime through PRoot Distro and is not bundled in the APK. Debian, XFCE, Fcitx5, Mozc, fonts, and all packages installed into a user-created environment remain under their respective upstream licenses. Package copyright files are available inside Debian under `/usr/share/doc/<package>/copyright`.

## Google Chrome

Google Chrome is not embedded in the APK or stored in this source repository. On supported `amd64` and `arm64` Debian environments, LDFA downloads the current stable Debian package from `https://dl.google.com/linux/direct/` during provisioning. Google Chrome is proprietary software distributed by Google under its own Terms of Service and additional terms. The user must review those terms when Chrome is first launched. Installing the package may add Google's package repository so Chrome can receive security updates.

## Android and Java dependencies

AndroidX, Material Components, Kotlin, kotlinx.coroutines and other Gradle dependencies retain their upstream licenses. Dependency coordinates are declared in the Gradle build files. Generated APK metadata includes applicable packaged license resources except where duplicate metadata must be excluded during Android packaging.

## Source availability

The corresponding source for the unified APK consists of this repository, its pinned Git submodules, build scripts, and configuration. Clone it with:

```bash
git clone --recurse-submodules https://github.com/hatake716/LDFA.git
```

When distributing APKs, distribute or provide equivalent access to the corresponding source for the same revision, as required by GPLv3.
