#!/usr/bin/env python3
"""Patch AndroidManifest.xml minSdkVersion in an APK to allow v1 (JAR) signing.

apksigner skips v1 when manifest minSdk>=24, which causes "package appears to be
invalid" on some installers. We patch minSdk to 23 before signing, then sign.
"""
import sys, zipfile, os

apk = sys.argv[1]
target = int(sys.argv[2]) if len(sys.argv) > 2 else 23
tmp = apk + '.tmp'

# AXML attribute: resourceId 0x0101020c (minSdkVersion), type int_dec (0x10), value 24
NEEDLE = b'\x0c\x02\x01\x01'
OLD_VAL = (24).to_bytes(4, 'little')
NEW_VAL = (target).to_bytes(4, 'little')

with zipfile.ZipFile(apk, 'r') as zin, zipfile.ZipFile(tmp, 'w') as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == 'AndroidManifest.xml':
            b = bytearray(data)
            patched = 0
            i = 0
            while True:
                j = b.find(NEEDLE, i)
                if j < 0:
                    break
                # typed value at j+12: size(2)=0800, res0=00, type=10(int_dec)
                if b[j+12:j+16] == b'\x08\x00\x10\x00' and b[j+16:j+20] == OLD_VAL:
                    b[j+16:j+20] = NEW_VAL
                    patched += 1
                i = j + 4
            if patched == 0:
                raise SystemExit('minSdkVersion 24 attribute not found in AndroidManifest.xml')
            data = bytes(b)
            print(f'patched AndroidManifest.xml minSdkVersion 24->{target} ({patched} occurrence(s))')
        zi = zipfile.ZipInfo(item.filename)
        zi.date_time = item.date_time
        zi.compress_type = item.compress_type
        zi.external_attr = item.external_attr
        zi.flag_bits = item.flag_bits
        zi.create_system = item.create_system
        zi.internal_attr = item.internal_attr
        zi.comment = item.comment
        zi.extra = item.extra
        if item.compress_type == zipfile.ZIP_DEFLATED:
            zout.writestr(zi, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
        else:
            zout.writestr(zi, data, compress_type=item.compress_type)
os.replace(tmp, apk)
