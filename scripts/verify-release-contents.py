#!/usr/bin/env python3
"""Check native ELF alignment and shared contents of a release APK and Play AAB.

Signature / Android manifest checks and runtime acceptance are separate checks.
"""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path


def elf_load_segments(data):
    if data[:4] != b'\x7fELF':
        raise ValueError('Native library is not ELF')
    endian = '<' if data[5] == 1 else '>'
    if data[4] == 2:
        phoff = struct.unpack_from(endian + 'Q', data, 32)[0]
        size, count = struct.unpack_from(endian + 'HH', data, 54)
        format_ = endian + 'IIQQQQQQ'
        offset_index, address_index = 2, 3
    elif data[4] == 1:
        phoff = struct.unpack_from(endian + 'I', data, 28)[0]
        size, count = struct.unpack_from(endian + 'HH', data, 42)
        format_ = endian + 'IIIIIIII'
        offset_index, address_index = 1, 2
    else:
        raise ValueError('Unknown ELF class')
    segments = []
    for index in range(count):
        header = struct.unpack_from(format_, data, phoff + index * size)
        if header[0] == 1:
            offset, address, alignment = header[offset_index], header[address_index], header[-1]
            if alignment < 16384 or offset % 16384 != address % 16384:
                raise ValueError(f'ELF segment is not 16KB aligned: offset={offset}, address={address}, align={alignment}')
            segments.append({'offset': offset, 'address': address, 'alignment': alignment})
    if not segments:
        raise ValueError('No ELF LOAD segments')
    return segments


def inspect(path, prefix):
    libraries = {}
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if name.startswith(prefix + 'lib/') and name.endswith('.so'):
                data = archive.read(name)
                relative = name.removeprefix(prefix)
                libraries[relative] = {'sha256': hashlib.sha256(data).hexdigest(), 'load_segments': elf_load_segments(data)}
    if not libraries:
        raise ValueError(f'No native libraries in {path.name}')
    abis = sorted({name.split('/')[1] for name in libraries})
    for abi in abis:
        for required in ['libpdrt.so', 'libpdrt-loader.so', 'libtalloc.so', 'libandroid-shmem.so']:
            if f'lib/{abi}/{required}' not in libraries:
                raise ValueError(f'{path.name} has no {required} for {abi}')
    return {'file': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'abis': abis, 'libraries': libraries}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--bundle', type=Path)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    apk = inspect(args.apk, '')
    if not set(apk['abis']).issubset({'arm64-v8a', 'x86_64'}) or 'arm64-v8a' not in apk['abis']:
        raise ValueError('Unexpected APK ABI set')
    if not args.bundle:
        args.report.write_text(json.dumps({'apk': apk, 'elf_alignment_bytes': 16384}, indent=2) + '\n')
        print(f'PASS: {len(apk["libraries"])} APK libraries; required runtime libraries and 16KB ELF alignment verified.')
        return
    bundle = inspect(args.bundle, 'base/')
    if bundle['abis'] != ['arm64-v8a']:
        raise ValueError('The Play bundle must contain ARM64 only')
    for name, entry in bundle['libraries'].items():
        if apk['libraries'].get(name, {}).get('sha256') != entry['sha256']:
            raise ValueError(f'APK and bundle library differ: {name}')
    with zipfile.ZipFile(args.apk) as a, zipfile.ZipFile(args.bundle) as b:
        scripts = {}
        for name in ['ldfa-host.sh', 'ldfa-x11.sh']:
            content = a.read('assets/' + name)
            if content != b.read('base/assets/' + name):
                raise ValueError(f'APK and bundle script differ: {name}')
            scripts[name] = hashlib.sha256(content).hexdigest()
        dex = {}
        apk_dex = {name for name in a.namelist() if '/' not in name and name.endswith('.dex')}
        bundle_dex = {name.removeprefix('base/dex/') for name in b.namelist() if name.startswith('base/dex/') and name.endswith('.dex')}
        if not apk_dex or apk_dex != bundle_dex:
            raise ValueError('APK and bundle DEX file sets differ')
        for name in sorted(apk_dex):
            content = a.read(name)
            if content != b.read('base/dex/' + name):
                raise ValueError(f'APK and bundle application code differs: {name}')
            dex[name] = hashlib.sha256(content).hexdigest()
    result = {'apk': apk, 'bundle': bundle, 'shared_scripts_sha256': scripts, 'shared_dex_sha256': dex, 'elf_alignment_bytes': 16384}
    args.report.write_text(json.dumps(result, indent=2) + '\n')
    print(f'PASS: {len(apk["libraries"])} APK libraries, {len(bundle["libraries"])} AAB libraries; 16KB ELF alignment and shared contents match.')


if __name__ == '__main__':
    main()
