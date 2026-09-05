#!/usr/bin/env python3
"""Fail release validation if a merged APK/AAB manifest reintroduces restricted APIs."""
import sys
import xml.etree.ElementTree as ET

ANDROID = '{http://schemas.android.com/apk/res/android}'
FORBIDDEN = {
    'android.permission.REQUEST_INSTALL_PACKAGES',
    'android.permission.BIND_ACCESSIBILITY_SERVICE',
    'android.permission.MANAGE_EXTERNAL_STORAGE',
    'android.permission.READ_EXTERNAL_STORAGE',
    'android.permission.WRITE_EXTERNAL_STORAGE',
}


def check(path):
    root = ET.parse(path).getroot()
    assert root.get('package') == 'com.hatake716.linuxdesktop', 'Unexpected package'
    permissions = {node.get(ANDROID + 'name') for node in root if node.tag.startswith('uses-permission')}
    assert not permissions & FORBIDDEN, f'Forbidden permissions: {permissions & FORBIDDEN}'
    for name in ['FOREGROUND_SERVICE_DATA_SYNC', 'FOREGROUND_SERVICE_SPECIAL_USE']:
        assert 'android.permission.' + name in permissions, f'Missing {name}'
    services = root.findall('./application/service')
    for service in services:
        assert service.get(ANDROID + 'permission') != 'android.permission.BIND_ACCESSIBILITY_SERVICE', 'Accessibility service remains'
        assert not service.get(ANDROID + 'name', '').endswith('.KeyInterceptor'), 'Key interceptor remains'
        for action in service.findall('./intent-filter/action'):
            assert action.get(ANDROID + 'name') != 'android.accessibilityservice.AccessibilityService', 'Accessibility intent remains'
    expected = {'BackupService': 1, 'DesktopKeepAliveService': 0x40000001, 'EmbeddedX11ServerService': 0x40000000}
    for name, mask in expected.items():
        service = next(s for s in services if s.get(ANDROID + 'name', '').endswith('.' + name))
        types = service.get(ANDROID + 'foregroundServiceType', '0')
        actual = int(types, 0) if types.startswith('0x') or types.isdigit() else sum(
            {'dataSync': 1, 'specialUse': 0x40000000}[t] for t in types.split('|'))
        assert actual == mask, f'{name}: incorrect foreground service types: {types}'
        if mask & 0x40000000:
            assert any(p.get(ANDROID + 'name') == 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE' and
                       p.get(ANDROID + 'value') for p in service.findall('property')), f'{name}: missing specialUse explanation'
    print(f'PASS: {path}: restricted permissions and accessibility service absent; required FGS permissions retained')


if __name__ == '__main__':
    if len(sys.argv) < 2:
        raise SystemExit('usage: check-play-permissions.py <decoded-manifest.xml> ...')
    for path in sys.argv[1:]:
        check(path)
