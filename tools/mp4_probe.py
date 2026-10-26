"""Dump ISO-BMFF (M4A/MP4) atom structure and report embedded cover art.

Usage: python mp4_probe.py <file> [more files...]
"""

import struct
import sys
from pathlib import Path

CONTAINERS = {
    "moov", "udta", "meta", "ilst", "trak", "mdia", "minf", "stbl",
    "edts", "tref", "mvex", "moof", "traf",
}


def walk(f, start, end, depth, path):
    f.seek(start)
    pos = start
    out = []
    while pos + 8 <= end:
        f.seek(pos)
        header = f.read(8)
        if len(header) < 8:
            break
        size32, kind = struct.unpack(">I4s", header)
        name = kind.decode("latin-1")
        header_size = 8
        if size32 == 1:
            ext = f.read(8)
            if len(ext) < 8:
                break
            size = struct.unpack(">Q", ext)[0]
            header_size = 16
        elif size32 == 0:
            size = end - pos
        else:
            size = size32
        if size < header_size:
            break
        payload_start = pos + header_size
        payload_end = min(pos + size, end)
        out.append((name, pos, size, depth, "/".join(path + [name])))
        if name in CONTAINERS and name != "ilst" and depth < 6:
            child_start = payload_start + (4 if name == "meta" else 0)
            out.extend(walk(f, child_start, payload_end, depth + 1, path + [name]))
        elif name == "ilst" and depth < 6:
            out.extend(walk(f, payload_start, payload_end, depth + 1, path + [name]))
        elif name == "covr" and depth < 6:
            out.extend(walk(f, payload_start, payload_end, depth + 1, path + [name]))
        pos += size
    return out


def main():
    for arg in sys.argv[1:]:
        path = Path(arg)
        print(f"=== {path} ===")
        with path.open("rb") as f:
            size = path.stat().st_size
            atoms = walk(f, 0, size, 0, [])
            covr = [a for a in atoms if a[0] == "covr"]
            for name, pos, sz, depth, full in atoms:
                if depth <= 2 or name in ("covr", "data"):
                    print(f"  {'  ' * depth}{name} @{pos} size={sz}")
            if covr:
                for name, pos, sz, depth, full in covr:
                    f.seek(pos)
                    header = f.read(8)
                    header_size = 16 if struct.unpack(">I", header[:4])[0] == 1 else 8
                    inner = walk(f, pos + header_size, pos + sz, depth + 1, [])
                    for iname, ipos, isz, idepth, ifull in inner:
                        if iname == "data":
                            f.seek(ipos + 8)
                            dtype, locale = struct.unpack(">II", f.read(8))
                            print(f"    data type=0x{dtype:02x} bytes={isz - 16}")
            else:
                print("  !! no covr atom")


if __name__ == "__main__":
    main()
