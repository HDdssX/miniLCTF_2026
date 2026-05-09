#!/usr/bin/env python3
"""
根据 hand-seal PNG 生成最终检查程序。

输入：
- 一张由 tools/make_hand_png.cpp 生成的 PNG

输出：
- 一个自包含的 check.cpp 源文件
- 可选：直接编译出 check 可执行程序
"""

from __future__ import annotations

import argparse
import subprocess
import textwrap
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REPO_URL = "https://github.com/thebabush/llvm-jutsu"
SNIPPET_BEGIN = "// BEGIN_LLVM_JUTSU_RENDERER_SNIPPET"
SNIPPET_END = "// END_LLVM_JUTSU_RENDERER_SNIPPET"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--png", required=True, type=Path, help="输入的 hand-seal PNG")
    parser.add_argument("--out-cpp", required=True, type=Path, help="输出的 check.cpp 路径")
    parser.add_argument(
        "--repo-url",
        default=REPO_URL,
        help="写入二进制中的 llvm-jutsu 仓库链接",
    )
    parser.add_argument(
        "--compile",
        action="store_true",
        help="在生成 check.cpp 后自动编译",
    )
    parser.add_argument("--out-bin", type=Path, help="编译后 check 的输出路径")
    parser.add_argument("--cxx", default="g++", help="用于 --compile 的 C++ 编译器")
    parser.add_argument(
        "--cxxflag",
        action="append",
        default=[],
        help="额外编译参数，可重复传入",
    )
    return parser.parse_args()


def read_renderer_snippet() -> str:
    source = (ROOT / "tools" / "make_hand_png.cpp").read_text()
    start = source.find(SNIPPET_BEGIN)
    end = source.find(SNIPPET_END)
    if start == -1 or end == -1 or end <= start:
        raise RuntimeError("未能在 make_hand_png.cpp 中找到渲染片段标记")
    start += len(SNIPPET_BEGIN)
    return source[start:end].strip("\n")


def format_cpp_bytes(data: bytes) -> str:
    lines: list[str] = []
    for offset in range(0, len(data), 12):
        chunk = data[offset : offset + 12]
        rendered = ", ".join(f"0x{byte:02x}" for byte in chunk)
        lines.append(f"    {rendered},")
    if lines:
        lines[-1] = lines[-1].rstrip(",")
    return "\n".join(lines)


def emit_check_cpp(png_bytes: bytes, repo_url: str, renderer_snippet: str) -> str:
    png_array = format_cpp_bytes(png_bytes)
    png_len = len(png_bytes)
    return textwrap.dedent(
        f"""\
        /*
         * 由 tools/build_check.py 自动生成
         * 该文件嵌入了一张 hand-seal PNG 以及运行时比较逻辑
         */

        #include <algorithm>
        #include <cmath>
        #include <cstdint>
        #include <cstdio>
        #include <cstdlib>
        #include <cstring>
        #include <vector>
        #include "../vendor/lodepng.h"

        {renderer_snippet}

        namespace {{

        constexpr unsigned HAND_FUZZY_THRESHOLD = 100;

        int compare_hand_png_i32(uint32_t value, const uint8_t *expected_png, uint32_t expected_png_size) {{
            std::vector<uint8_t> actual_png = render_i32_png(value);
            if (actual_png.size() != expected_png_size) {{
                return 0;
            }}

            for (uint32_t i = 0; i < expected_png_size; i++) {{
                unsigned lhs = actual_png[i];
                unsigned rhs = expected_png[i];
                unsigned diff = lhs > rhs ? lhs - rhs : rhs - lhs;
                if (diff > HAND_FUZZY_THRESHOLD) {{
                    return 0;
                }}
            }}
            return 1;
        }}

        static const uint8_t hand_png[] = {{
        {png_array}
        }};
        static const uint32_t hand_png_len = {png_len}u;

        static constexpr const char *LICENSE_PREFIX = "LICENSE-";

        __attribute__((used)) static const char LLVM_JUTSU_REPO[] =
            "{repo_url}";

        static int parse_key(const char *license, uint32_t *out) {{
            if (std::strncmp(license, LICENSE_PREFIX, std::strlen(LICENSE_PREFIX)) != 0) {{
                return 0;
            }}

            const char *hex_part = license + std::strlen(LICENSE_PREFIX);
            char *endptr = nullptr;
            unsigned long value = std::strtoul(hex_part, &endptr, 16);
            if (endptr != hex_part + 8 || *endptr != '\\0') {{
                return 0;
            }}

            *out = static_cast<uint32_t>(value);
            return 1;
        }}

        static int check_license(const char *license) {{
            uint32_t value = 0;
            if (!parse_key(license, &value)) {{
                return 0;
            }}
            return compare_hand_png_i32(value, hand_png, hand_png_len);
        }}

        }}  // namespace

        int main(int argc, char **argv) {{
            char license[64];

            if (argc > 1) {{
                std::strncpy(license, argv[1], sizeof(license) - 1);
                license[sizeof(license) - 1] = '\\0';
            }} else {{
                std::printf("Enter license key: ");
                if (std::fgets(license, sizeof(license), stdin) == nullptr) {{
                    std::puts("Input error.");
                    return 1;
                }}
                license[std::strcspn(license, "\\n")] = '\\0';
            }}

            if (!check_license(license)) {{
                std::puts("Invalid license.");
                return 1;
            }}

            std::puts("License accepted.");
            std::puts("The old recovery helper used the same 32-bit token.");
            return 0;
        }}
        """
    )


def compile_check(cpp_path: Path, out_bin: Path, cxx: str, cxxflags: list[str]) -> None:
    cmd = [
        cxx,
        "-std=c++17",
        "-O2",
        *cxxflags,
        str(cpp_path),
        str(ROOT / "vendor" / "lodepng.cpp"),
        "-o",
        str(out_bin),
    ]
    subprocess.run(cmd, check=True)


def main() -> int:
    args = parse_args()
    png_path = args.png.resolve()
    out_cpp = args.out_cpp.resolve()
    out_cpp.parent.mkdir(parents=True, exist_ok=True)

    png_bytes = png_path.read_bytes()
    renderer_snippet = read_renderer_snippet()
    out_cpp.write_text(emit_check_cpp(png_bytes, args.repo_url, renderer_snippet))

    if args.compile:
        if args.out_bin is None:
            raise SystemExit("--compile 需要配合 --out-bin 使用")
        out_bin = args.out_bin.resolve()
        out_bin.parent.mkdir(parents=True, exist_ok=True)
        compile_check(out_cpp, out_bin, args.cxx, args.cxxflag)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
