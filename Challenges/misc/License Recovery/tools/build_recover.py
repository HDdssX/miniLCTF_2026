#!/usr/bin/env python3
"""
生成要被 whiteout 隐藏的恢复脚本。

输入：
- 一个 8 位十六进制形式的 32 位 key
- 一个 flag 字符串

输出：
- 一个自包含的 recover_flag.py
"""

from __future__ import annotations

import argparse
import hashlib
import secrets
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--key", required=True, help="8 位十六进制 key")
    parser.add_argument("--flag", required=True, help="需要保护的 flag 字符串")
    parser.add_argument("--out", required=True, type=Path, help="生成脚本的输出路径")
    return parser.parse_args()


def normalize_key(key_text: str) -> int:
    key_text = key_text.strip()
    if key_text.lower().startswith("0x"):
        key_text = key_text[2:]
    if len(key_text) != 8:
        raise ValueError("key 必须恰好为 8 位十六进制字符")
    return int(key_text, 16)


def derive_keystream(key: int, salt: bytes, length: int) -> bytes:
    key_bytes = key.to_bytes(4, "big")
    stream = bytearray()
    counter = 0
    while len(stream) < length:
        block = hashlib.sha256(key_bytes + salt + counter.to_bytes(4, "big")).digest()
        stream.extend(block)
        counter += 1
    return bytes(stream[:length])


def encrypt_flag(plaintext: bytes, key: int, salt: bytes) -> bytes:
    keystream = derive_keystream(key, salt, len(plaintext))
    return bytes(byte ^ keystream[index] for index, byte in enumerate(plaintext))


def format_bytes_for_python(data: bytes) -> str:
    chunks: list[str] = []
    for offset in range(0, len(data), 12):
        chunk = data[offset : offset + 12]
        chunks.append("    " + ", ".join(f"0x{byte:02x}" for byte in chunk))
    return ",\n".join(chunks)


def emit_script(ciphertext: bytes, salt: bytes, plaintext_digest: bytes) -> str:
    ciphertext_literal = format_bytes_for_python(ciphertext)
    salt_literal = format_bytes_for_python(salt)
    digest_literal = format_bytes_for_python(plaintext_digest)
    return f"""#!/usr/bin/env python3
import hashlib
import sys

CIPHERTEXT = bytes([
{ciphertext_literal}
])
SALT = bytes([
{salt_literal}
])
PLAINTEXT_DIGEST = bytes([
{digest_literal}
])

def parse_key(text: str) -> int:
    value = text.strip()
    if value.startswith("LICENSE-"):
        value = value[len("LICENSE-"):]
    if value.lower().startswith("0x"):
        value = value[2:]
    if len(value) != 8:
        raise ValueError("need exactly 8 hex digits")
    return int(value, 16)

def derive_keystream(key: int, salt: bytes, length: int) -> bytes:
    key_bytes = key.to_bytes(4, "big")
    stream = bytearray()
    counter = 0
    while len(stream) < length:
        block = hashlib.sha256(key_bytes + salt + counter.to_bytes(4, "big")).digest()
        stream.extend(block)
        counter += 1
    return bytes(stream[:length])

def decrypt(ciphertext: bytes, key: int, salt: bytes) -> bytes:
    keystream = derive_keystream(key, salt, len(ciphertext))
    return bytes(byte ^ keystream[index] for index, byte in enumerate(ciphertext))

def main() -> int:
    if len(sys.argv) > 1:
        key_text = sys.argv[1]
    else:
        key_text = input("Enter 32-bit key or LICENSE-XXXXXXXX: ")

    try:
        key = parse_key(key_text)
    except ValueError as exc:
        print(f"invalid key: {{exc}}", file=sys.stderr)
        return 1

    plaintext = decrypt(CIPHERTEXT, key, SALT)
    if hashlib.sha256(plaintext).digest() != PLAINTEXT_DIGEST:
        print("Invalid key.", file=sys.stderr)
        return 1

    try:
        decoded = plaintext.decode("utf-8")
    except UnicodeDecodeError:
        print("Invalid key.", file=sys.stderr)
        return 1

    if not decoded.startswith("miniL{{") or not decoded.endswith("}}"):
        print("Invalid key.", file=sys.stderr)
        return 1

    sys.stdout.write(decoded)
    sys.stdout.write("\\n")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
"""


def main() -> int:
    args = parse_args()
    key = normalize_key(args.key)
    plaintext = args.flag.encode("utf-8")
    salt = secrets.token_bytes(16)
    ciphertext = encrypt_flag(plaintext, key, salt)
    plaintext_digest = hashlib.sha256(plaintext).digest()

    out_path = args.out.resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(emit_script(ciphertext, salt, plaintext_digest))
    out_path.chmod(0o755)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
