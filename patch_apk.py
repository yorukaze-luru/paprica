#!/usr/bin/env python3
"""
patch_apk.py — turn the WebGen template APK into a stand-alone site app APK.

What it does:
  1. Replaces assets/site_config.json inside the APK (zip entry swap).
  2. Rebuilds the binary AndroidManifest.xml (AXML) string pool so the
     package name and the literal app label can be replaced with values of
     ANY length (package rename + app rename).
  3. Replaces the launcher icon PNG entries (res/mipmap-*/ic_launcher.png)
     with a user-supplied PNG.
  4. Strips the old signature, zipaligns, and re-signs with apksigner
     (v1+v2 requested; see REPORT.md for what apksigner actually emits).

Usage:
  python3 patch_apk.py \
      --in template.apk --out sample-example.apk \
      --url https://example.com --name "Exampleサイト" \
      --package app.webgen.sample.example --icon sample_icon.png

Defaults for --old-package / --old-label match the bundled template.
The default keystore is a TEST-ONLY keystore. Never use it for real
distribution; real releases must use a properly kept release key.
"""

import argparse
import json
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
BT = HERE / "tools" / "sdk" / "build-tools" / "34.0.0"

UTF8_FLAG = 0x00000100
RES_STRING_POOL_TYPE = 0x0001
RES_XML_TYPE = 0x0003


# ---------------------------------------------------------------- AXML pool

def read_length8(data, off):
    """AXML UTF-8 string length (1 or 2 bytes). Returns (value, new_off)."""
    b0 = data[off]
    if b0 & 0x80:
        return ((b0 & 0x7F) << 8) | data[off + 1], off + 2
    return b0, off + 1


def read_length16(data, off):
    w0 = struct.unpack_from("<H", data, off)[0]
    if w0 & 0x8000:
        w1 = struct.unpack_from("<H", data, off + 2)[0]
        return ((w0 & 0x7FFF) << 16) | w1, off + 4
    return w0, off + 2


def encode_length8(out, value):
    if value > 0x7F:
        out.append(0x80 | (value >> 8))
        out.append(value & 0xFF)
    else:
        out.append(value)


def encode_length16(out, value):
    if value > 0x7FFF:
        out += struct.pack("<HH", 0x8000 | (value >> 16), value & 0xFFFF)
    else:
        out += struct.pack("<H", value)


def parse_string_pool(chunk):
    """Parse a ResStringPool chunk (bytes). Returns (strings, is_utf8, header_size)."""
    assert struct.unpack_from("<H", chunk, 0)[0] == RES_STRING_POOL_TYPE
    header_size, _size = struct.unpack_from("<II", chunk, 2)
    # note: at offset 2: headerSize(u16) then size(u32) actually:
    header_size = struct.unpack_from("<H", chunk, 2)[0]
    string_count, style_count, flags, strings_start, _styles_start = struct.unpack_from(
        "<IIIII", chunk, 8
    )
    if style_count != 0:
        raise ValueError("string pool with styles is not supported by this spike")
    is_utf8 = bool(flags & UTF8_FLAG)
    offsets = [struct.unpack_from("<I", chunk, header_size + 4 * i)[0]
               for i in range(string_count)]
    strings = []
    for rel in offsets:
        off = strings_start + rel
        if is_utf8:
            _chars, p = read_length8(chunk, off)   # char count (unused)
            blen, p = read_length8(chunk, p)       # byte count
            raw = chunk[p:p + blen]
            strings.append(raw.decode("utf-8"))
        else:
            clen, p = read_length16(chunk, off)
            raw = chunk[p:p + clen * 2]
            strings.append(raw.decode("utf-16-le"))
    return strings, is_utf8, header_size, flags


def build_string_pool(strings, is_utf8, flags):
    """Rebuild a ResStringPool chunk from a list of strings."""
    header_size = 28
    strings_start = header_size + 4 * len(strings)
    blob = bytearray()
    offsets = []
    for s in strings:
        offsets.append(len(blob))
        if is_utf8:
            raw = s.encode("utf-8")
            encode_length8(blob, len(s))       # character count
            encode_length8(blob, len(raw))     # byte count
            blob += raw
            blob.append(0)
        else:
            raw = s.encode("utf-16-le")
            encode_length16(blob, len(s))
            blob += raw
            blob += b"\x00\x00"
    total = strings_start + len(blob)
    # pad chunk to 4-byte boundary
    pad = (-total) % 4
    total += pad
    blob += b"\x00" * pad
    out = bytearray()
    out += struct.pack("<HHI", RES_STRING_POOL_TYPE, header_size, total)
    out += struct.pack("<IIIII", len(strings), 0, flags, strings_start, 0)
    for o in offsets:
        out += struct.pack("<I", o)
    out += blob
    assert len(out) == total
    return bytes(out)


def patch_axml(axml, replacements, optional_replacements=None):
    """Replace exact-match strings in the AXML string pool.

    replacements: dict old_value -> new_value. Every key MUST occur in
    the pool (a missing key raises). Returns new AXML bytes.

    optional_replacements: dict old_value -> new_value applied only
    when the old value actually occurs in the pool; absent keys are
    silently ignored (used for the GeckoView clipboard authority rule
    in main(), which must not fire for the WebView template).
    """
    file_type, header_size, file_size = struct.unpack_from("<HHI", axml, 0)
    if file_type != RES_XML_TYPE:
        raise ValueError("not an AXML file")
    # first chunk after the file header is the string pool
    pool_off = header_size
    pool_size = struct.unpack_from("<I", axml, pool_off + 4)[0]
    pool = axml[pool_off:pool_off + pool_size]
    strings, is_utf8, _hs, flags = parse_string_pool(pool)
    optional = optional_replacements or {}
    hits = {k: 0 for k in replacements}
    new_strings = []
    for s in strings:
        if s in replacements:
            hits[s] += 1
            new_strings.append(replacements[s])
        elif s in optional:
            new_strings.append(optional[s])
        else:
            new_strings.append(s)
    missing = [k for k, v in hits.items() if v == 0]
    if missing:
        raise ValueError(f"strings not found in AXML pool: {missing}")
    new_pool = build_string_pool(new_strings, is_utf8, flags)
    out = bytearray()
    out += axml[:pool_off]
    out += new_pool
    out += axml[pool_off + pool_size:]
    # fix total file size in the header
    struct.pack_into("<I", out, 4, len(out))
    return bytes(out)


# ------------------------------------------------------------------- driver

def is_signature_entry(name):
    u = name.upper()
    return u.startswith("META-INF/") and (
        u.endswith(".SF") or u.endswith(".RSA") or u.endswith(".DSA")
        or u.endswith(".EC") or u.endswith("MANIFEST.MF")
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="inp", required=True)
    ap.add_argument("--out", dest="out", required=True)
    ap.add_argument("--url", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--package", required=True)
    ap.add_argument("--icon", help="PNG file to use as launcher icon (all densities)")
    ap.add_argument("--old-package", default="app.webgen.template")
    ap.add_argument("--old-label", default="WebGen Template")
    ap.add_argument("--ks", default=str(HERE / "test-keystore-TEST-ONLY.jks"),
                    help="TEST-ONLY keystore by default")
    ap.add_argument("--ks-pass", default="change-me")
    args = ap.parse_args()

    icon_bytes = Path(args.icon).read_bytes() if args.icon else None
    config_bytes = json.dumps(
        {"url": args.url, "name": args.name}, ensure_ascii=False
    ).encode("utf-8")

    replacements = {args.old_package: args.package, args.old_label: args.name}

    # GeckoView's clipboard content provider has its authority baked
    # into the AAR manifest as "<package>.clipboard" at build time.
    # The exact-match replacement above cannot catch it (it is not the
    # bare package name), so without this rule every generated Gecko
    # app would declare the SAME provider authority — and installing a
    # second generated app would fail with
    # INSTALL_FAILED_CONFLICTING_PROVIDER. This is an optional rule:
    # it only fires when such an entry exists in the string pool, so
    # the WebView template flow (no ".clipboard" entry) is unaffected.
    # Class-name strings (e.g. "<package>.MainActivity") must NOT be
    # rewritten — the class keeps its original name inside the dex —
    # which is why this targets the ".clipboard" entry exactly.
    optional_replacements = {
        args.old_package + ".clipboard": args.package + ".clipboard",
        # Same collision class: the androidx.startup InitializationProvider
        # merged from the lifecycle AAR declares its authority as
        # "<package>.androidx-startup". Found in the fixed Gecko base
        # (round 4); without this, generated apps collide on it instead.
        args.old_package + ".androidx-startup": args.package + ".androidx-startup",
    }

    tmp_unsigned = Path(args.out + ".unsigned.tmp")
    tmp_aligned = Path(args.out + ".aligned.tmp")

    stats = {"config": False, "manifest": False, "icons": 0}
    with zipfile.ZipFile(args.inp) as zin, \
         zipfile.ZipFile(tmp_unsigned, "w") as zout:
        for info in zin.infolist():
            name = info.filename
            if is_signature_entry(name):
                continue  # strip old signature
            data = zin.read(name)
            if name == "assets/site_config.json":
                data = config_bytes
                stats["config"] = True
            elif name == "AndroidManifest.xml":
                data = patch_axml(data, replacements, optional_replacements)
                stats["manifest"] = True
            elif name.startswith("res/") and name.endswith("ic_launcher.png") \
                    and icon_bytes is not None:
                data = icon_bytes
                stats["icons"] += 1
            # preserve the original compression type per entry
            # (resources.arsc / PNGs stay STORED, as aapt2 produced them)
            ni = zipfile.ZipInfo(name, date_time=info.date_time)
            ni.compress_type = info.compress_type
            ni.external_attr = info.external_attr
            zout.writestr(ni, data)

    print(f"patched: {stats}")

    java_home = HERE / "tools" / "jdk"
    env_path = f"{java_home}/bin:"
    import os
    env = dict(os.environ)
    env["JAVA_HOME"] = str(java_home)
    env["PATH"] = env_path + env.get("PATH", "")

    subprocess.run([str(BT / "zipalign"), "-f", "4",
                    str(tmp_unsigned), str(tmp_aligned)], check=True)
    subprocess.run([
        str(BT / "apksigner"), "sign",
        "--ks", args.ks, "--ks-pass", f"pass:{args.ks_pass}",
        "--key-pass", f"pass:{args.ks_pass}",
        "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
        "--out", args.out, str(tmp_aligned),
    ], check=True, env=env)

    tmp_unsigned.unlink(missing_ok=True)
    tmp_aligned.unlink(missing_ok=True)

    print("== apksigner verify ==")
    r = subprocess.run([str(BT / "apksigner"), "verify", "--verbose", args.out],
                       env=env, capture_output=True, text=True)
    print(r.stdout.strip())
    print("== aapt2 dump badging (package / label / icon) ==")
    r = subprocess.run([str(BT / "aapt2"), "dump", "badging", args.out],
                       capture_output=True, text=True)
    for line in r.stdout.splitlines():
        if line.startswith(("package:", "application-label:", "application:",
                             "launchable-activity:")):
            print(line)
    print(f"OK -> {args.out}")


if __name__ == "__main__":
    main()
