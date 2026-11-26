#!/usr/bin/env python3
"""Patch AndroidManifest.xml minSdkVersion in an APK to allow v1 (JAR) signing.

apksigner skips v1 when manifest minSdk>=24, which causes "package appears to be
invalid" on some installers. We patch minSdk to target (default 23) before signing.
"""
import sys, zipfile, os

apk = sys.argv[1]
target = int(sys.argv[2]) if len(sys.argv) > 2 else 23
tmp = apk + '.tmp'

# AXML attribute name: resourceId 0x0101020c (android:minSdkVersion)
NEEDLE = b'\x0c\x02\x01\x01'
NEW_VAL = (target).to_bytes(4, 'little')

with zipfile.ZipFile(apk, 'r') as zin, zipfile.ZipFile(tmp, 'w') as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == 'AndroidManifest.xml':
            b = bytearray(data)
            patched = 0
            found_any = []
            i = 0
            while True:
                j = b.find(NEEDLE, i)
                if j < 0:
                    break
                # AXML ResXMLTree_attribute: ns(4) name(4) rawValue(4) typedValue(8)
                # name at j, typedValue at j+8: size(2) res0(1) type(1) data(4)
                # Some dumps show different layout; scan a small window for int 24/23.
                window = b[j:j+24]
                found_any.append(('name@'+str(j), window.hex()))
                patched_here = False
                for off in range(4, 20):
                    k = j + off
                    if k + 8 <= len(b) and b[k] == 0x08 and b[k+1] == 0x00 and b[k+2] == 0x00 and b[k+3] in (0x10, 0x11):
                        vdata = int.from_bytes(b[k+4:k+8], 'little')
                        if vdata in (23, 24):
                            b[k+4:k+8] = NEW_VAL
                            patched += 1
                            patched_here = True
                            found_any.append(('patched', off, vdata))
                            break
                if not patched_here:
                    # fallback: scan wider window around the name for an int 24 typed value
                    for k in range(max(0, j-20), min(j+120, len(b)-8)):
                        if b[k] == 0x08 and b[k+1] == 0x00 and b[k+2] == 0x00 and b[k+3] in (0x10, 0x11):
                            vdata = int.from_bytes(b[k+4:k+8], 'little')
                            if vdata == 24:
                                b[k+4:k+8] = NEW_VAL
                                patched += 1
                                found_any.append(('fallback', k-j, vdata))
                                patched_here = True
                                break
                i = j + 4
            if patched == 0:
                # debug: dump any near-misses
                dbg = []
                i = 0
                while True:
                    j = b.find(NEEDLE, i)
                    if j < 0:
                        break
                    chunk = b[j:j+24].hex()
                    dbg.append(chunk)
                    i = j + 4
                raise SystemExit(f'minSdkVersion attr not patched; found name occurrences={len(dbg)} details={dbg[:5]}')
            data = bytes(b)
            print(f'patched AndroidManifest.xml minSdkVersion {found_any} -> {target} ({patched} occurrence(s))')
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
