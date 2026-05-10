#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 3 && $# -ne 4 ]]; then
  echo "用法: $0 <image_name> <8位十六进制key> <flag> [output_path]" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHAL_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

BUILD_DIR="$CHAL_DIR/build"
OUT_DIR="$BUILD_DIR/out"
DIST_DIR="$CHAL_DIR/dist"

IMAGE_NAME="$1"
KEY_HEX="${2#0x}"
KEY_HEX="${KEY_HEX#0X}"
KEY_HEX="$(printf '%s' "$KEY_HEX" | tr '[:lower:]' '[:upper:]')"
FLAG="$3"
OUTPUT_PATH="${4:-$DIST_DIR/minil2026-license-recovery-image.tar}"

if [[ ! "$KEY_HEX" =~ ^[0-9A-F]{8}$ ]]; then
  echo "key 非法: 需要恰好 8 位十六进制字符" >&2
  exit 1
fi

rm -rf \
  "$BUILD_DIR/check.cpp" \
  "$BUILD_DIR/flag.txt" \
  "$BUILD_DIR/hand.png" \
  "$BUILD_DIR/key.txt" \
  "$BUILD_DIR/recover_flag.py" \
  "$BUILD_DIR/run_check.sh" \
  "$OUT_DIR"

mkdir -p "$OUT_DIR" "$DIST_DIR"
mkdir -p "$(dirname "$OUTPUT_PATH")"

printf '%s\n' "$KEY_HEX" > "$BUILD_DIR/key.txt"
printf '%s\n' "$FLAG" > "$BUILD_DIR/flag.txt"

g++ -std=c++17 -O2 \
  "$CHAL_DIR/tools/make_hand_png.cpp" \
  "$CHAL_DIR/vendor/lodepng.cpp" \
  -o "$OUT_DIR/make_hand_png"

"$OUT_DIR/make_hand_png" "$KEY_HEX" "$BUILD_DIR/hand.png"

python3 "$CHAL_DIR/tools/build_check.py" \
  --png "$BUILD_DIR/hand.png" \
  --out-cpp "$BUILD_DIR/check.cpp" \
  --compile \
  --out-bin "$OUT_DIR/check"

python3 "$CHAL_DIR/tools/build_recover.py" \
  --key "$KEY_HEX" \
  --flag "$FLAG" \
  --out "$BUILD_DIR/recover_flag.py"

cat > "$BUILD_DIR/run_check.sh" <<'EOF'
#!/usr/bin/env bash
set -u

while true; do
  printf "Enter license key: "
  if ! IFS= read -r license; then
    exit 1
  fi
  if /app/check "$license"; then
    exit 0
  fi
  echo "Try again."
done
EOF
chmod +x "$BUILD_DIR/run_check.sh"

docker build \
  -f "$CHAL_DIR/Dockerfile" \
  -t "$IMAGE_NAME" \
  "$CHAL_DIR"

if [[ "$OUTPUT_PATH" == *.gz ]]; then
  docker save "$IMAGE_NAME" | gzip -c > "$OUTPUT_PATH"
else
  docker save "$IMAGE_NAME" -o "$OUTPUT_PATH"
fi

echo "镜像已构建: $IMAGE_NAME"
echo "当前 key: $KEY_HEX"
echo "当前 flag: $FLAG"
echo "导出的镜像包: $OUTPUT_PATH"
