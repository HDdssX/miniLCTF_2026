#!/bin/sh
set -eu

SITE=/app/site
FLAG_VALUE="$(printenv FLAG 2>/dev/null || printf 'miniL{no_FLAG_env}')"
KEY="$(python3 - "$FLAG_VALUE" <<'PY'
import hashlib
import sys

sys.stdout.write(hashlib.sha3_512(sys.argv[1].encode()).hexdigest()[:32])
PY
)"

git config --global --add safe.directory "$SITE"

git -C "$SITE" update-ref -d refs/notes/commits >/dev/null 2>&1 || true
rm -f "$SITE/.git/logs/refs/notes/commits"
git -C "$SITE" \
  -c user.name='runtime' \
  -c user.email='runtime@example.com' \
  notes add -m "cache snapshot key: $KEY" HEAD
rm -f "$SITE/.git/logs/refs/notes/commits"

git -C "$SITE" update-ref -d refs/stash >/dev/null 2>&1 || true
rm -f "$SITE/.git/logs/refs/stash"
rm -rf "$SITE/runtime_cache"
mkdir -p "$SITE/runtime_cache"

python3 - "$KEY" "$FLAG_VALUE" "$SITE/runtime_cache/bundle.txt" <<'PY'
import base64
import sys
from pathlib import Path

key = sys.argv[1].encode()
flag = sys.argv[2].encode()
out_path = Path(sys.argv[3])

cipher = bytes(flag[i] ^ key[i % len(key)] for i in range(len(flag)))
out_path.write_text(
    "snapshot_kind=runtime-cache\n"
    f"cipher_b64={base64.b64encode(cipher).decode()}\n",
    encoding="utf-8",
)
PY

git -C "$SITE" \
  -c user.name='runtime' \
  -c user.email='runtime@example.com' \
  stash push -u -m "runtime encrypted snapshot" -- runtime_cache >/dev/null
rm -f "$SITE/.git/logs/refs/stash"

rm -rf "$SITE/runtime_cache"

unset FLAG
unset FLAG_VALUE
unset KEY
unset SITE

exec python /app/app.py
