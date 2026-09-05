#!/usr/bin/env python3
"""Inspect packaged DEX and dependency metadata; do not hide SDK dependency reports."""
import argparse
import json
import zipfile
from pathlib import Path

MARKERS = [
    b'org/lsposed/hiddenapibypass', b'org.lsposed.hiddenapibypass',
    b'HiddenApiBypass', b'addHiddenApiExemptions', b'setHiddenApiExemptions',
    b'bypassHiddenAPIReflectionRestrictions',
]


def inspect(path):
    entries = []
    with zipfile.ZipFile(path) as archive:
        for name in archive.namelist():
            if name.endswith('.dex') or name.startswith('BUNDLE-METADATA/com.android.tools.build.libraries/'):
                content = archive.read(name)
                for marker in MARKERS:
                    if marker in content:
                        raise SystemExit(f'FAIL: {path.name}: {name}: {marker.decode()} remains')
                entries.append(name)
    if not any(name.endswith('.dex') for name in entries):
        raise SystemExit(f'FAIL: {path.name}: no DEX inspected')
    return {'file': path.name, 'checked_entries': entries, 'bypass_sdk_absent': True}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('packages', type=Path, nargs='+')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    results = [inspect(path) for path in args.packages]
    if args.report:
        args.report.write_text(json.dumps(results, indent=2) + '\n')
    for result in results:
        print(f'PASS: {result["file"]}: no hidden-API bypass SDK in DEX or dependency metadata')
