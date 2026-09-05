#!/usr/bin/env bash
# Package a source-built Termux runtime for execution from Android nativeLibraryDir.
set -euo pipefail

if [[ $# != 3 ]]; then
    echo "Usage: $0 DEB_DIRECTORY aarch64|x86_64 DESTINATION_DIRECTORY" >&2
    exit 2
fi
deb_dir=$(realpath "$1")
arch=$2
destination=$3
case "$arch" in aarch64|x86_64) ;; *) echo "Unsupported architecture: $arch" >&2; exit 2 ;; esac
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
shopt -s nullglob
for package in proot libtalloc libandroid-shmem; do
    matches=("$deb_dir/${package}_"*"_${arch}.deb")
    if [[ ${#matches[@]} != 1 ]]; then
        echo "Expected exactly one $package package for $arch" >&2
        exit 1
    fi
    member=$(ar t "${matches[0]}" | awk '/^data\.tar\./ {print}')
    [[ -n "$member" && "$member" != *$'\n'* ]]
    case "$member" in
        *.xz) compression=J ;;
        *.gz) compression=z ;;
        *) echo "Unsupported deb compression: $member" >&2; exit 1 ;;
    esac
    ar p "${matches[0]}" "$member" | tar -x"$compression" -C "$scratch"
done
prefix="$scratch/data/data/com.hatake716.linuxdesktop/files/usr"
mkdir "$scratch/native"
cp "$prefix/bin/proot" "$scratch/native/libpdrt.so"
cp "$prefix/libexec/proot/loader" "$scratch/native/libpdrt-loader.so"
cp -L "$prefix/lib/libtalloc.so.2" "$scratch/native/libtalloc.so"
cp -L "$prefix/lib/libandroid-shmem.so" "$scratch/native/libandroid-shmem.so"
patchelf --page-size 16384 --set-rpath '$ORIGIN' --replace-needed libtalloc.so.2 libtalloc.so "$scratch/native/libpdrt.so"
patchelf --page-size 16384 --set-rpath '$ORIGIN' --set-soname libtalloc.so "$scratch/native/libtalloc.so"
chmod 755 "$scratch/native/"*.so
mkdir -p "$destination"
cp "$scratch/native/"*.so "$destination/"
sha256sum "$destination/"*.so
