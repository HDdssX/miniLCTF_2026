import os
import sys
from pathlib import Path

APP_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(APP_DIR))

from codec import DEFAULT_WIDTH, text_to_bmp_bytes


def main() -> None:
    flag = os.environ.get("FLAG", "miniL{demo_flag}")
    static_dir = APP_DIR / "static"
    static_dir.mkdir(parents=True, exist_ok=True)
    flag_path = static_dir / "flag.bmp"
    flag_path.write_bytes(text_to_bmp_bytes(flag, width=DEFAULT_WIDTH))


if __name__ == "__main__":
    main()
