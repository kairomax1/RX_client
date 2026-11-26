#!/usr/bin/env python3
"""Patch AndroidManifest.xml minSdkVersion in an APK to 23 before signing.

apksigner skips v1 when manifest minSdk>=24. targetSdk 37 requires v2+.
We need v1+v2+v3: patch only the minSdkVersion attribute (0x0101020c) from 24 to 23.
NO global patching - that corrupted manifests before.
"""
import sys, zipfile, os

apk = sys.argv[1]
target = int(sys.argv[2]) if len(sys.argv) > 2 else 23
tmp = apk + '.tmp'

MIN_SDK_NAME = b'\x0c\x02\x01\x01'  # android:minSdkVersion resource id LE
INT_DEC_24 = b'\x08\x00\x00\x10\x18\x00\x00\x00'  # Res_value: size=8, type=int_dec, 24
INT_DEC_TARGET = b'\x08\x00\x00\x10' + target.to_bytes(4, 'little')

with zipfile.ZipFile(apk, 'r') as zin, zipfile.ZipFile(tmp, 'w') as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == 'AndroidManifest.xml':
            b = bytearray(data)
            patched = 0
            # Targeted: find Res_value int_dec 24 whose attribute name is minSdkVersion
            k = 0
            while True:
                k = b.find(INT_DEC_24, k)
                if k < 0:
                    break
                # attribute layout: ns(4) name(4) rawValue(4) typedValue(8) => name at k-8
                if k >= 8 and b[k-8:k-4] == MIN_SDK_NAME:
                    b[k:k+8] = INT_DEC_TARGET
                    patched += 1
                    print(f'patched minSdkVersion 24->{target} at offset {k}')
                k += 1
            if patched == 0:
                # debug: show any int_dec 24 locations and nearby name
                locs = []
                k = 0
                while True:
                    k = b.find(INT_DEC_24, k)
                    if k < 0:
                        break
                    name = b[k-8:k-4].hex() if k >= 8 else '??'
                    locs.append(f'off={k} name={name}')
                    k += 1
                raise SystemExit(f'minSdkVersion not patched; int_dec_24_locs={locs[:10]}')
            data = bytes(b)
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
