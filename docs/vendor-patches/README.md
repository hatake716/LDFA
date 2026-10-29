# Archived X server parent-notification patch

This directory preserves one local opt-in change from the pending LDFA checkout.
The patch is **not applied by the default build**. It is separate from the
committed Termux:X11 patches that CMake applies to its native dependencies.

When `TERMUX_X11_NOTIFY_PARENT=1` is set, the archived change also enables
`RunFromSmartParent` during `InitParentProcess`. The default behavior is retained
when this variable is absent. The current LDFA application and scripts do not set
this variable. No upstream repository or pinned submodule commit is changed by
this archival record.

## Provenance

- Upstream repository: https://gitlab.freedesktop.org/xorg/xserver
- Upstream base commit: `65d790bd208ec380b196eb98f144abb0b32e334d`
- Original upstream path: `os/connection.c`
- Original LDFA-relative path: `vendor/termux-x11/lorie/src/main/cpp/xserver/os/connection.c`
- [Pinned public source](https://gitlab.freedesktop.org/xorg/xserver/-/blob/65d790bd208ec380b196eb98f144abb0b32e334d/os/connection.c)
- [Pinned public license collection](https://gitlab.freedesktop.org/xorg/xserver/-/blob/65d790bd208ec380b196eb98f144abb0b32e334d/COPYING)
- License notices: X11-style permission notices of The Open Group and Digital
  Equipment Corporation, copied from the pinned source header to
  `XSERVER-NOTICES.txt`.

## Integrity and validation

- Base file SHA-256: `364193056d42a345e2529416806bf9ccdd6a5206d2d0d43139febba1d26488c2`
- Modified local source SHA-256: `1e075217908ff2b0c3317f39da0d04b081f7ad3f2dd4d101d9416c8d15b4a9d6`
- Patch SHA-256: `72fe9f00f83d73c2ba5c58993e7429f86c3be076c1f2226c07ea85771e1d708b`
- `git apply --check` passed against the clean pinned base file.
- Applying the patch to that temporary clean base reproduced the local source
  byte for byte. The source checkout was preserved during validation.

To inspect the patch against a clean checkout of the pinned X server:

```sh
git -C vendor/termux-x11/lorie/src/main/cpp/xserver apply --check /absolute/path/to/xserver-parent-notify-opt-in.patch
```
