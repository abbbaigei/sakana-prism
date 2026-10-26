"""Extract the embedded cover of an audio file (FLAC picture / ID3 APIC / MP4 covr).

Usage: python extract_cover.py <audio> <out-image-or-dir>
"""

import struct
import sys
from pathlib import Path


def flac_cover(f):
    f.seek(4)
    while True:
        header = f.read(4)
        if len(header) < 4:
            return None
        last = header[0] & 0x80
        kind = header[0] & 0x7F
        length = int.from_bytes(header[1:4], "big")
        block = f.read(length)
        if kind == 6:
            p = 0
            p += 4  # picture type
            mime_len = int.from_bytes(block[p:p + 4], "big"); p += 4 + mime_len
            desc_len = int.from_bytes(block[p:p + 4], "big"); p += 4 + desc_len
            p += 16
            data_len = int.from_bytes(block[p:p + 4], "big"); p += 4
            return block[p:p + data_len]
        if last:
            return None


def id3_cover(data):
    version = data[3]
    size = ((data[6] & 0x7F) << 21) | ((data[7] & 0x7F) << 14) | ((data[8] & 0x7F) << 7) | (data[9] & 0x7F)
    p = 10
    end = min(10 + size, len(data))
    while p + 10 <= end:
        fid = data[p:p + 4].decode("latin-1")
        if version >= 4:
            fsize = ((data[p + 4] & 0x7F) << 21) | ((data[p + 5] & 0x7F) << 14) | ((data[p + 6] & 0x7F) << 7) | (data[p + 7] & 0x7F)
        else:
            fsize = int.from_bytes(data[p + 4:p + 8], "big")
        if fsize <= 0:
            return None
        body = data[p + 10:p + 10 + fsize]
        if fid == "APIC":
            enc = body[0]
            q = 1
            q = body.index(b"\x00", q) + 1
            q += 1  # picture type
            if enc in (1, 2):
                while q + 1 < len(body) and body[q:q + 2] != b"\x00\x00":
                    q += 2
                q += 2
            else:
                q = body.index(b"\x00", q) + 1
            return body[q:]
        p += 10 + fsize
    return None


def mp4_cover(f, size):
    def atoms(start, end):
        out = []
        p = start
        while p + 8 <= end:
            f.seek(p)
            head = f.read(8)
            if len(head) < 8:
                break
            asize, kind = struct.unpack(">I4s", head)
            header = 8
            if asize == 1:
                asize = struct.unpack(">Q", f.read(8))[0]
                header = 16
            elif asize == 0:
                asize = end - p
            if asize < header:
                break
            out.append((kind.decode("latin-1"), p + header, min(p + asize, end)))
            p += asize
        return out

    moov = next((a for a in atoms(0, size) if a[0] == "moov"), None)
    if not moov:
        return None
    udta = next((a for a in atoms(moov[1], moov[2]) if a[0] == "udta"), None)
    if udta:
        meta = next((a for a in atoms(udta[1], udta[2]) if a[0] == "meta"), None)
    else:
        meta = next((a for a in atoms(moov[1], moov[2]) if a[0] == "meta"), None)
    if not meta:
        return None
    ilst = next((a for a in atoms(meta[1] + 4, meta[2]) if a[0] == "ilst"), None)
    if not ilst:
        return None
    covr = next((a for a in atoms(ilst[1], ilst[2]) if a[0] == "covr"), None)
    if not covr:
        return None
    for kind, start, end in atoms(covr[1], covr[2]):
        if kind == "data":
            f.seek(start + 8)
            return f.read(end - start - 8)
    return None


def main():
    src = Path(sys.argv[1])
    out = Path(sys.argv[2])
    size = src.stat().st_size
    with src.open("rb") as f:
        head = f.read(12)
        if head[:4] == b"fLaC":
            data = flac_cover(f)
        elif head[:3] == b"ID3":
            data = id3_cover(src.read_bytes())
        else:
            data = mp4_cover(f, size)
    if not data:
        print("no embedded cover")
        return 1
    if data[:3] == b"\xff\xd8\xff":
        ext = ".jpg"
    elif data[:8] == b"\x89PNG\r\n\x1a\n":
        ext = ".png"
    else:
        ext = ".bin"
    out = out if out.suffix else out.with_suffix(ext)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(data)
    print(f"{len(data)} bytes -> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
