#!/usr/bin/env python3
"""Patch the compiled APK manifest minSdkVersion for v1-signature compatibility.

The app is compiled with minSdk 24. The release signer omits the legacy JAR (v1)
block at that level, while a few OEM package installers reject v2/v3-only APKs as
"package appears to be invalid". For the signed sideload APK, lower only the final
binary-manifest value to 23 before signing all three schemes. Gradle compilation and
tests continue to use the real minSdk 24.
"""
from __future__ import annotations

import copy
import os
import struct
import sys
import zipfile
from pathlib import Path

RES_XML_TYPE = 0x0003
RES_STRING_POOL_TYPE = 0x0001
RES_XML_START_ELEMENT_TYPE = 0x0102
UTF8_FLAG = 0x00000100
TYPE_INT_DEC = 0x10


def _length8(data: bytes, offset: int) -> tuple[int, int]:
    first = data[offset]
    if first & 0x80:
        return ((first & 0x7F) << 8) | data[offset + 1], offset + 2
    return first, offset + 1


def _length16(data: bytes, offset: int) -> tuple[int, int]:
    first = struct.unpack_from("<H", data, offset)[0]
    if first & 0x8000:
        second = struct.unpack_from("<H", data, offset + 2)[0]
        return ((first & 0x7FFF) << 16) | second, offset + 4
    return first, offset + 2


def _string_pool(data: bytes, offset: int, chunk_size: int, header_size: int) -> list[str]:
    if header_size < 28 or offset + header_size > len(data):
        raise ValueError("invalid Android string-pool header")
    string_count, _style_count, flags, strings_start, _styles_start = struct.unpack_from(
        "<IIIII", data, offset + 8
    )
    offsets_start = offset + header_size
    offsets_end = offsets_start + string_count * 4
    chunk_end = offset + chunk_size
    if offsets_end > chunk_end or offset + strings_start > chunk_end:
        raise ValueError("invalid Android string-pool offsets")

    result: list[str] = []
    for index in range(string_count):
        relative = struct.unpack_from("<I", data, offsets_start + index * 4)[0]
        cursor = offset + strings_start + relative
        if cursor >= chunk_end:
            raise ValueError("Android string lies outside its chunk")
        if flags & UTF8_FLAG:
            _utf16_length, cursor = _length8(data, cursor)
            byte_length, cursor = _length8(data, cursor)
            end = cursor + byte_length
            if end > chunk_end:
                raise ValueError("truncated UTF-8 string in Android manifest")
            result.append(data[cursor:end].decode("utf-8"))
        else:
            char_length, cursor = _length16(data, cursor)
            end = cursor + char_length * 2
            if end > chunk_end:
                raise ValueError("truncated UTF-16 string in Android manifest")
            result.append(data[cursor:end].decode("utf-16le"))
    return result


def patch_manifest_min_sdk(manifest: bytes, old: int, new: int) -> tuple[bytes, int]:
    if len(manifest) < 8 or struct.unpack_from("<H", manifest, 0)[0] != RES_XML_TYPE:
        raise ValueError("AndroidManifest.xml is not binary Android XML")
    xml_size = struct.unpack_from("<I", manifest, 4)[0]
    if xml_size < 8 or xml_size > len(manifest):
        raise ValueError("invalid binary Android XML size")

    data = bytearray(manifest[:xml_size])
    strings: list[str] = []
    patched = 0
    offset = 8
    while offset < xml_size:
        if offset + 8 > xml_size:
            raise ValueError("truncated Android XML chunk header")
        chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", data, offset)
        chunk_end = offset + chunk_size
        if header_size < 8 or chunk_size < header_size or chunk_end > xml_size:
            raise ValueError("invalid Android XML chunk size")

        if chunk_type == RES_STRING_POOL_TYPE:
            strings = _string_pool(data, offset, chunk_size, header_size)
        elif chunk_type == RES_XML_START_ELEMENT_TYPE:
            if offset + 36 > chunk_end:
                raise ValueError("truncated start-element chunk")
            tag_index = struct.unpack_from("<I", data, offset + 20)[0]
            tag_name = strings[tag_index] if tag_index < len(strings) else None
            if tag_name == "uses-sdk":
                attribute_start, attribute_size, attribute_count = struct.unpack_from(
                    "<HHH", data, offset + 24
                )
                if attribute_size < 20:
                    raise ValueError("invalid Android manifest attribute size")
                attributes_offset = offset + 16 + attribute_start
                for index in range(attribute_count):
                    attr = attributes_offset + index * attribute_size
                    if attr + 20 > chunk_end:
                        raise ValueError("truncated Android manifest attribute")
                    name_index = struct.unpack_from("<I", data, attr + 4)[0]
                    name = strings[name_index] if name_index < len(strings) else None
                    value_type = data[attr + 15]
                    value = struct.unpack_from("<I", data, attr + 16)[0]
                    if name == "minSdkVersion" and value_type == TYPE_INT_DEC and value == old:
                        struct.pack_into("<I", data, attr + 16, new)
                        patched += 1

        offset = chunk_end

    if patched == 0:
        raise ValueError(f"uses-sdk minSdkVersion {old} was not found in AndroidManifest.xml")
    return bytes(data), patched


def patch_apk(apk_path: Path, old: int, new: int) -> int:
    temp_path = apk_path.with_name(apk_path.name + ".patching")
    try:
        with zipfile.ZipFile(apk_path, "r") as source:
            try:
                manifest_info = source.getinfo("AndroidManifest.xml")
            except KeyError as exc:
                raise ValueError("APK has no AndroidManifest.xml") from exc
            manifest, patched = patch_manifest_min_sdk(source.read(manifest_info), old, new)
            with zipfile.ZipFile(temp_path, "w") as output:
                output.comment = source.comment
                for info in source.infolist():
                    payload = manifest if info.filename == "AndroidManifest.xml" else source.read(info)
                    output_info = copy.copy(info)
                    if info.compress_type == zipfile.ZIP_DEFLATED:
                        output.writestr(output_info, payload, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
                    else:
                        output.writestr(output_info, payload, compress_type=info.compress_type)
        os.replace(temp_path, apk_path)
        return patched
    finally:
        if temp_path.exists():
            temp_path.unlink()


def main() -> int:
    if len(sys.argv) not in (2, 4):
        print(f"Usage: {sys.argv[0]} APK [OLD_MIN_SDK NEW_MIN_SDK]", file=sys.stderr)
        return 2
    apk = Path(sys.argv[1])
    old, new = (24, 23) if len(sys.argv) == 2 else (int(sys.argv[2]), int(sys.argv[3]))
    try:
        count = patch_apk(apk, old, new)
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print(f"Could not patch minSdk in {apk}: {exc}", file=sys.stderr)
        return 1
    print(f"Patched AndroidManifest.xml minSdkVersion {old}->{new} ({count} occurrence(s))")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
