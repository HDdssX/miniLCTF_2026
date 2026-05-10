#!/usr/bin/env python3
from __future__ import annotations

import base64
import re
import sys
from pathlib import Path


def load_ciphertext(arg: str) -> bytes:
    path = Path(arg)
    if path.exists():
        text = path.read_text(encoding="utf-8", errors="replace")
    else:
        text = arg

    for line in text.splitlines():
        if line.startswith("cipher_b64="):
            text = line.split("=", 1)[1].strip()
            break

    text = re.sub(r"\s+", "", text)
    return base64.b64decode(text)


def xor_bytes(data: bytes, key: bytes) -> bytes:
    return bytes(data[i] ^ key[i % len(key)] for i in range(len(data)))


def main() -> int:
    if len(sys.argv) != 3:
        print(f"usage: {Path(sys.argv[0]).name} <key> <cipher-b64-or-file>", file=sys.stderr)
        return 1

    key = sys.argv[1].encode()
    if not key:
        print("key must not be empty", file=sys.stderr)
        return 1

    cipher = load_ciphertext(sys.argv[2])
    plain = xor_bytes(cipher, key)
    sys.stdout.write(plain.decode("utf-8", "replace"))
    if not plain.endswith(b"\n"):
        sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
