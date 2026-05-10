import math
import struct


DEFAULT_WIDTH = 16
PAYLOAD_GROUP_SIZE = 3
COLOR_GROUP_SIZE = 6
LENGTH_FIELD_SIZE = 4


def color_index_to_color(color_index: int) -> int:
    return color_index * 16 + 8


def color_to_color_index(color: int) -> int:
    return color // 16


def chunk3_to_chunk6(chunk: bytes) -> bytes:
    colors = bytearray()
    for value in chunk:
        colors.append(color_index_to_color(value >> 4))
        colors.append(color_index_to_color(value & 0x0F))
    return bytes(colors)


def chunk6_to_chunk3(chunk: bytes) -> bytes:
    raw = bytearray()
    for offset in range(0, COLOR_GROUP_SIZE, 2):
        high = color_to_color_index(chunk[offset])
        low = color_to_color_index(chunk[offset + 1])
        raw.append((high << 4) | low)
    return bytes(raw)


def bytes_to_colors(raw: bytes) -> bytearray:
    data = bytearray(raw)
    if len(data) % PAYLOAD_GROUP_SIZE != 0:
        data += b"\x00" * (PAYLOAD_GROUP_SIZE - len(data) % PAYLOAD_GROUP_SIZE)

    colors = bytearray()
    for offset in range(0, len(data), PAYLOAD_GROUP_SIZE):
        colors += chunk3_to_chunk6(data[offset : offset + PAYLOAD_GROUP_SIZE])
    return colors


def colors_to_bytes(colors: bytes) -> bytearray:
    data = bytearray(colors)
    if len(data) % COLOR_GROUP_SIZE != 0:
        data += b"\x00" * (COLOR_GROUP_SIZE - len(data) % COLOR_GROUP_SIZE)

    raw = bytearray()
    for offset in range(0, len(data), COLOR_GROUP_SIZE):
        raw += chunk6_to_chunk3(data[offset : offset + COLOR_GROUP_SIZE])
    return raw


def build_bmp_data(colors: bytes, width: int) -> tuple[bytearray, int]:
    row_bytes = width * 3
    row_padding = (4 - row_bytes % 4) % 4
    stride = row_bytes + row_padding

    data = bytearray()
    for offset in range(0, len(colors), row_bytes):
        row = colors[offset : offset + row_bytes]
        if len(row) < row_bytes:
            row += b"\x00" * (row_bytes - len(row))
        data += row
        data += b"\x00" * row_padding

    if not data:
        data += b"\x00" * stride

    height = len(data) // stride
    return data, height


def build_bmp(width: int, height: int, pixel_data: bytes) -> bytes:
    file_size = 54 + len(pixel_data)
    header = bytearray()
    header += b"BM"
    header += struct.pack("<I", file_size)
    header += b"\x00\x00\x00\x00"
    header += struct.pack("<I", 54)
    header += struct.pack("<I", 40)
    header += struct.pack("<i", width)
    header += struct.pack("<i", height)
    header += struct.pack("<H", 1)
    header += struct.pack("<H", 24)
    header += struct.pack("<I", 0)
    header += struct.pack("<I", len(pixel_data))
    header += struct.pack("<I", 2835)
    header += struct.pack("<I", 2835)
    header += struct.pack("<I", 0)
    header += struct.pack("<I", 0)
    return bytes(header + pixel_data)


def payload_block_size(width: int) -> int:
    if width % 2 != 0:
        raise ValueError("width must be even")
    row_bytes = width * 3
    return row_bytes // COLOR_GROUP_SIZE * PAYLOAD_GROUP_SIZE


def text_to_payload(text: str, width: int = DEFAULT_WIDTH) -> bytes:
    encoded = text.encode("utf-8")
    block_size = payload_block_size(width)
    base_len = len(encoded) + LENGTH_FIELD_SIZE
    pad_len = (-base_len) % block_size
    return encoded + (b"\x00" * pad_len) + struct.pack("<I", len(encoded))


def payload_to_text(payload: bytes) -> str:
    if len(payload) < LENGTH_FIELD_SIZE:
        raise ValueError("payload too short")

    text_length = struct.unpack("<I", payload[-LENGTH_FIELD_SIZE:])[0]
    if text_length > len(payload) - LENGTH_FIELD_SIZE:
        raise ValueError("truncated payload")
    return payload[:text_length].decode("utf-8")


def text_to_bmp_bytes(text: str, width: int = DEFAULT_WIDTH) -> bytes:
    if width <= 0:
        raise ValueError("width must be positive")

    colors = bytes_to_colors(text_to_payload(text, width))
    pixel_data, height = build_bmp_data(colors, width)
    return build_bmp(width, height, pixel_data)


def parse_bmp_pixels(bmp_bytes: bytes) -> tuple[int, int, bytes]:
    if len(bmp_bytes) < 54 or bmp_bytes[:2] != b"BM":
        raise ValueError("invalid bmp file")

    pixel_offset = struct.unpack("<I", bmp_bytes[10:14])[0]
    dib_size = struct.unpack("<I", bmp_bytes[14:18])[0]
    if dib_size < 40:
        raise ValueError("unsupported bmp header")

    width = struct.unpack("<i", bmp_bytes[18:22])[0]
    height = struct.unpack("<i", bmp_bytes[22:26])[0]
    planes = struct.unpack("<H", bmp_bytes[26:28])[0]
    bit_count = struct.unpack("<H", bmp_bytes[28:30])[0]
    compression = struct.unpack("<I", bmp_bytes[30:34])[0]

    if planes != 1 or bit_count != 24 or compression != 0:
        raise ValueError("only uncompressed 24-bit bmp is supported")

    width = abs(width)
    height = abs(height)
    row_bytes = width * 3
    row_padding = (4 - row_bytes % 4) % 4
    stride = row_bytes + row_padding
    pixel_size = stride * height
    pixel_data = bmp_bytes[pixel_offset : pixel_offset + pixel_size]
    if len(pixel_data) != pixel_size:
        raise ValueError("truncated bmp pixel data")

    colors = bytearray()
    for row in range(height):
        start = row * stride
        colors += pixel_data[start : start + row_bytes]
    return width, height, bytes(colors)


def bmp_bytes_to_text(bmp_bytes: bytes) -> str:
    _, _, colors = parse_bmp_pixels(bmp_bytes)
    payload = colors_to_bytes(colors)
    return payload_to_text(payload)


def expected_height_for_text(text: str, width: int = DEFAULT_WIDTH) -> int:
    payload_len = len(text_to_payload(text, width))
    color_len = payload_len // PAYLOAD_GROUP_SIZE * COLOR_GROUP_SIZE
    row_bytes = width * 3
    return max(1, int(math.ceil(color_len / row_bytes)))
