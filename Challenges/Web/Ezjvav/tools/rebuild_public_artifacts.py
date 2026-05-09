import argparse
import hashlib
import io
import re
import secrets
import shlex
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path


MANIFEST_NAME = "META-INF/MANIFEST.MF"
PRIVATE_BRIDGE_PREFIX = "ctf/ghostvalve/vault/ServiceMain"
PRIVATE_VAULT_PREFIX = "ctf/ghostvalve/vault/VaultMain"
RESOURCE_SUFFIXES = (".bin", ".dat", ".cfg", ".idx")
PUBLIC_HELPER_NAME = "studio-agent.bin"
DEFAULT_BASE_IMAGE = "eclipse-temurin:8-jdk-noble"
HELPER_RUNTIME_ENV = "helper-runtime.env"
HELPER_BUILD_ENV = ".build-config.env"
DOCKER_STAMP_FILE = "docker-artifact-stamp.txt"


def rotl32(value: int, bits: int) -> int:
    value &= 0xFFFFFFFF
    return ((value << bits) | (value >> (32 - bits))) & 0xFFFFFFFF


def random_u32() -> int:
    return secrets.randbits(32)


def random_odd_u32() -> int:
    return random_u32() | 1


def java_hex_u32(value: int) -> str:
    return f"0x{value & 0xFFFFFFFF:08x}"


def c_hex_u32(value: int) -> str:
    return f"0x{value & 0xFFFFFFFF:08X}u"


def c_byte_list(value: bytes) -> str:
    return ", ".join(f"0x{current:02X}u" for current in value)


def random_java_name(prefix: str, hex_bytes: int = 5) -> str:
    return prefix + secrets.token_hex(hex_bytes)


def random_perm(size: int) -> list[int]:
    values = list(range(size))
    secrets.SystemRandom().shuffle(values)
    return values


def random_opcode(used: set[int]) -> int:
    while True:
        value = 8 + secrets.randbelow(220)
        if value not in used:
            used.add(value)
            return value


def build_helper_config() -> dict:
    used: set[int] = set()
    preview_tail_handle = 4 + secrets.randbelow(3)
    preview_tail_witness = 2 + secrets.randbelow(4)
    preview_head_token = 3 + secrets.randbelow(3)
    preview_head_client = 2 + secrets.randbelow(3)
    preview_head_witness = 1 + secrets.randbelow(4)
    preview_head_handle = 2 + secrets.randbelow(3)
    return {
        "HELPER_OP_MASK": str(((secrets.randbits(8) | 1) + 0x23) & 0xFF),
        "HELPER_BRANCH_SEED": str(random_u32()),
        "HELPER_OP_PING": str(random_opcode(used)),
        "HELPER_OP_PREVIEW": str(random_opcode(used)),
        "HELPER_OP_INSTALL": str(random_opcode(used)),
        "HELPER_OP_PREWARM": str(random_opcode(used)),
        "HELPER_ORDER_PREVIEW": ",".join(str(item) for item in random_perm(5)),
        "HELPER_ORDER_INSTALL": ",".join(str(item) for item in random_perm(7)),
        "HELPER_PREVIEW_TAIL_HANDLE": str(preview_tail_handle),
        "HELPER_PREVIEW_TAIL_WITNESS": str(preview_tail_witness),
        "HELPER_PREVIEW_HEAD_TOKEN": str(preview_head_token),
        "HELPER_PREVIEW_HEAD_CLIENT": str(preview_head_client),
        "HELPER_PREVIEW_HEAD_WITNESS": str(preview_head_witness),
        "HELPER_PREVIEW_HEAD_HANDLE": str(preview_head_handle),
        "HELPER_PREVIEW_HASH_HEAD": str(1 + secrets.randbelow(preview_head_handle)),
        "HELPER_PREVIEW_HASH_TAIL": str(2 + secrets.randbelow(max(1, preview_tail_handle - 1))),
        "HELPER_PREVIEW_SLICE_START": str(secrets.randbelow(17)),
        "HELPER_INSTALL_SLICE_START": str(4 + secrets.randbelow(17)),
    }


def write_helper_config(root: Path, config: dict) -> None:
    payload = "".join(f"{key}={value}\n" for key, value in sorted(config.items()))
    (root / HELPER_RUNTIME_ENV).write_text(payload, encoding="utf-8")
    (root / "helper" / HELPER_BUILD_ENV).write_text(payload, encoding="utf-8")


def build_native_config() -> dict:
    pack_min = 2 + secrets.randbelow(3)
    pack_max = 5 + secrets.randbelow(4)
    if pack_min > 4:
        pack_min = 4
    if pack_max < 4:
        pack_max = 4
    bridge_tag = secrets.token_bytes(5 + secrets.randbelow(4))
    pack_tag = secrets.token_bytes(5 + secrets.randbelow(4))
    while pack_tag == bridge_tag:
        pack_tag = secrets.token_bytes(5 + secrets.randbelow(4))
    preview_route = 1 + secrets.randbelow(0x3FFFFFFF)
    install_route = 1 + secrets.randbelow(0x3FFFFFFF)
    while install_route == preview_route:
        install_route = 1 + secrets.randbelow(0x3FFFFFFF)
    return {
        "pack_magic": random_u32() or 0x455A5650,
        "pack_version": 0x100 + secrets.randbelow(0x7EFF),
        "pack_min_items": pack_min,
        "pack_max_items": max(pack_max, pack_min + 1),
        "bridge_tag": bridge_tag,
        "pack_tag": pack_tag,
        "bridge_salt": 12 + secrets.randbelow(9),
        "pack_salt": 8 + secrets.randbelow(9),
        "bridge_seed_base": random_odd_u32(),
        "bridge_seed_mul": random_odd_u32(),
        "bridge_seed_rot": 3 + secrets.randbelow(8),
        "bridge_seed_xor": random_u32(),
        "bridge_index_mul": random_odd_u32(),
        "bridge_rot_mask": 3 if secrets.randbelow(2) == 0 else 7,
        "bridge_rot_base": 2 + secrets.randbelow(4),
        "bridge_tail_mul": random_odd_u32(),
        "bridge_public_class": random_java_name("Latch", 4),
        "bridge_public_method": random_java_name("f", 4),
        "bridge_dispatch_method": random_java_name("d", 4),
        "bridge_preview_route": preview_route,
        "bridge_install_route": install_route,
        "vault_name_scan": random_java_name("s", 4),
        "vault_name_pack_loader": random_java_name("l", 4),
        "vault_name_hidden_entry": random_java_name("h", 4),
        "vault_name_pack_parser": random_java_name("p", 4),
        "vault_name_read_all": random_java_name("r", 4),
        "vault_name_open_jar": random_java_name("o", 4),
        "vault_name_native_invoke": random_java_name("n", 4),
        "vault_name_native_locator": random_java_name("g", 4),
        "vault_name_wait": random_java_name("w", 4),
        "pack_seed_base": random_odd_u32(),
        "pack_seed_mul": random_odd_u32(),
        "pack_seed_rot": 3 + secrets.randbelow(8),
        "pack_seed_xor": random_u32(),
        "pack_index_mul": random_odd_u32(),
        "pack_rot_mask": 3 if secrets.randbelow(2) == 0 else 7,
        "pack_rot_base": 2 + secrets.randbelow(4),
        "pack_tail_mul": random_odd_u32(),
    }


def bridge_mix(salt: bytes, config: dict) -> int:
    value = config["bridge_seed_base"]
    for index, current in enumerate(salt):
        value ^= (current & 0xFF) << ((index & 3) * 8)
        value = rotl32((value * config["bridge_seed_mul"]) & 0xFFFFFFFF, config["bridge_seed_rot"]) ^ config["bridge_seed_xor"]
    return value & 0xFFFFFFFF


def bridge_mask(seed: int, index: int, salt: bytes, config: dict) -> int:
    value = (seed ^ ((index * config["bridge_index_mul"]) & 0xFFFFFFFF)) & 0xFFFFFFFF
    value = rotl32(value, (index & config["bridge_rot_mask"]) + config["bridge_rot_base"])
    value ^= ((salt[index % len(salt)] & 0xFF) << ((index & 1) * 8)) & 0xFFFFFFFF
    value ^= (((index >> 1) * config["bridge_tail_mul"]) & 0xFFFFFFFF)
    return value & 0xFF


def render_helper(template: str, helper_class: str) -> str:
    return template.replace("__CLASS_NAME__", helper_class).replace("__METHOD_NAME__", "a")


def render_public_bridge(template: str, config: dict) -> str:
    return (
        template.replace("__CLASS_NAME__", config["bridge_public_class"])
        .replace("__METHOD_NAME__", config["bridge_public_method"])
        .replace("__DISPATCH_METHOD__", config["bridge_dispatch_method"])
        .replace("__PREVIEW_ROUTE__", str(config["bridge_preview_route"]))
        .replace("__INSTALL_ROUTE__", str(config["bridge_install_route"]))
    )


def render_vault_main(template: str, config: dict) -> str:
    rendered = (
        template.replace("__PACK_MAGIC__", java_hex_u32(config["pack_magic"]))
        .replace("__PACK_VERSION__", str(config["pack_version"]))
        .replace("__PACK_MIN_ITEMS__", str(config["pack_min_items"]))
        .replace("__PACK_MAX_ITEMS__", str(config["pack_max_items"]))
        .replace("__BRIDGE_DISPATCH_METHOD__", config["bridge_dispatch_method"])
        .replace("__WAIT_HELPER__", config["vault_name_wait"])
    )
    for original, replacement in (
        ("scanHiddenDecoded", config["vault_name_scan"]),
        ("loadPack", config["vault_name_pack_loader"]),
        ("looksLikeHiddenEntry", config["vault_name_hidden_entry"]),
        ("parsePack", config["vault_name_pack_parser"]),
        ("readAll", config["vault_name_read_all"]),
        ("openJar", config["vault_name_open_jar"]),
        ("runNativeHelper", config["vault_name_native_invoke"]),
        ("nativeHelper", config["vault_name_native_locator"]),
    ):
        rendered = re.sub(rf"\b{re.escape(original)}\b", replacement, rendered)
    return rendered


def render_native_helper(template: str, config: dict) -> str:
    return (
        template.replace("__BRIDGE_SALT__", str(config["bridge_salt"]))
        .replace("__PACK_SALT__", str(config["pack_salt"]))
        .replace("__BRIDGE_TAG_LEN__", str(len(config["bridge_tag"])))
        .replace("__PACK_TAG_LEN__", str(len(config["pack_tag"])))
        .replace("__BRIDGE_TAG_BYTES__", c_byte_list(config["bridge_tag"]))
        .replace("__PACK_TAG_BYTES__", c_byte_list(config["pack_tag"]))
        .replace("__PACK_MAGIC_C__", c_hex_u32(config["pack_magic"]))
        .replace("__PACK_VERSION_C__", f"{config['pack_version']}u")
        .replace("__PACK_MIN_ITEMS_C__", f"{config['pack_min_items']}u")
        .replace("__PACK_MAX_ITEMS_C__", f"{config['pack_max_items']}u")
        .replace("__BRIDGE_SEED_BASE_C__", c_hex_u32(config["bridge_seed_base"]))
        .replace("__BRIDGE_SEED_MUL_C__", c_hex_u32(config["bridge_seed_mul"]))
        .replace("__BRIDGE_SEED_ROT__", f"{config['bridge_seed_rot']}u")
        .replace("__BRIDGE_SEED_XOR_C__", c_hex_u32(config["bridge_seed_xor"]))
        .replace("__BRIDGE_INDEX_MUL_C__", c_hex_u32(config["bridge_index_mul"]))
        .replace("__BRIDGE_ROT_MASK__", f"{config['bridge_rot_mask']}u")
        .replace("__BRIDGE_ROT_BASE__", f"{config['bridge_rot_base']}u")
        .replace("__BRIDGE_TAIL_MUL_C__", c_hex_u32(config["bridge_tail_mul"]))
        .replace("__PACK_SEED_BASE_C__", c_hex_u32(config["pack_seed_base"]))
        .replace("__PACK_SEED_MUL_C__", c_hex_u32(config["pack_seed_mul"]))
        .replace("__PACK_SEED_ROT__", f"{config['pack_seed_rot']}u")
        .replace("__PACK_SEED_XOR_C__", c_hex_u32(config["pack_seed_xor"]))
        .replace("__PACK_INDEX_MUL_C__", c_hex_u32(config["pack_index_mul"]))
        .replace("__PACK_ROT_MASK__", f"{config['pack_rot_mask']}u")
        .replace("__PACK_ROT_BASE__", f"{config['pack_rot_base']}u")
        .replace("__PACK_TAIL_MUL_C__", c_hex_u32(config["pack_tail_mul"]))
    )


def render_vault_payload(
    template: str,
    class_name: str,
    field_name: str,
    method_name: str,
    lane: int,
    fold: int,
) -> str:
    return (
        template.replace("__CLASS_NAME__", class_name)
        .replace("__FIELD_NAME__", field_name)
        .replace("__METHOD_NAME__", method_name)
        .replace("__LANE__", str(lane))
        .replace("__FOLD__", str(fold))
    )


def render_decoy(class_name: str, method_name: str, variant: int) -> str:
    if variant == 0:
        body = f"""
package ctf.ghostvalve.vault;

public final class {class_name} {{
    private {class_name}() {{
    }}

    public static String {method_name}(int lane, String[] values) {{
        return values == null ? null : String.valueOf(lane);
    }}
}}
"""
    else:
        body = f"""
package ctf.ghostvalve.vault;

public final class {class_name} {{
    private {class_name}() {{
    }}

    public static Object {method_name}(String[] values) {{
        return values;
    }}
}}
"""
    return body.strip() + "\n"


def pack_rotl32(value: int, bits: int) -> int:
    value &= 0xFFFFFFFF
    return ((value << bits) | (value >> (32 - bits))) & 0xFFFFFFFF


def pack_seed(salt: bytes, config: dict) -> int:
    value = config["pack_seed_base"]
    for index, current in enumerate(salt):
        value ^= (current & 0xFF) << ((index & 3) * 8)
        value = pack_rotl32((value * config["pack_seed_mul"]) & 0xFFFFFFFF, config["pack_seed_rot"]) ^ config["pack_seed_xor"]
    return value & 0xFFFFFFFF


def pack_mask(seed: int, index: int, salt: bytes, config: dict) -> int:
    value = (seed + ((index * config["pack_index_mul"]) & 0xFFFFFFFF)) & 0xFFFFFFFF
    value = pack_rotl32(value, (index & config["pack_rot_mask"]) + config["pack_rot_base"])
    value ^= ((salt[index % len(salt)] & 0xFF) << ((index & 3) * 8)) & 0xFFFFFFFF
    value ^= (((index + 1) * config["pack_tail_mul"]) & 0xFFFFFFFF)
    return value & 0xFF


def write_short_string(buffer: io.BytesIO, value: str) -> None:
    raw = value.encode("utf-8")
    if not raw or len(raw) > 0xFFFF:
        raise ValueError("invalid short string")
    buffer.write(len(raw).to_bytes(2, "big"))
    buffer.write(raw)


def build_vault_blob(specs: list[dict], config: dict) -> bytes:
    payload = io.BytesIO()
    payload.write(config["pack_magic"].to_bytes(4, "big"))
    payload.write(config["pack_version"].to_bytes(2, "big"))
    payload.write(len(specs).to_bytes(2, "big"))
    for spec in specs:
        payload.write(b"\x01" if spec["real"] else b"\x00")
        write_short_string(payload, spec["class_name"])
        write_short_string(payload, spec["field_name"])
        write_short_string(payload, spec["method_name"])
        payload.write(int(spec["lane"]).to_bytes(8, "big", signed=True))
        payload.write(int(spec["fold"]).to_bytes(4, "big", signed=True))
        class_bytes = spec["class_bytes"]
        payload.write(len(class_bytes).to_bytes(4, "big", signed=True))
        payload.write(class_bytes)

    raw = payload.getvalue()
    salt = secrets.token_bytes(config["pack_salt"])
    seed = pack_seed(salt, config)
    encoded = bytearray(len(salt) + len(raw))
    encoded[: len(salt)] = salt
    for index, current in enumerate(raw):
        encoded[index + len(salt)] = current ^ pack_mask(seed, index, salt, config)
    return config["pack_tag"] + bytes(encoded)


BRIDGE_META_MAGIC = 0x47764231
BRIDGE_META_VERSION = 1


def bridge_meta_mask(salt: bytes, index: int) -> int:
    left = salt[index % len(salt)] & 0xFF
    right = salt[(index + 1) % len(salt)] & 0xFF
    tail = salt[(index + len(salt) - 1) % len(salt)] & 0xFF
    return (left + (right ^ index) + ((tail * 3) & 0xFF) + ((index * 17) & 0xFF)) & 0xFF


def build_bridge_meta(config: dict) -> bytes:
    class_name = "ctf.ghostvalve.vault." + config["bridge_public_class"]
    method_name = config["bridge_public_method"]
    payload = io.BytesIO()
    payload.write(BRIDGE_META_MAGIC.to_bytes(4, "big"))
    payload.write(BRIDGE_META_VERSION.to_bytes(2, "big"))
    write_short_string(payload, class_name)
    write_short_string(payload, method_name)
    payload.write(int(config["bridge_preview_route"]).to_bytes(4, "big", signed=True))
    payload.write(int(config["bridge_install_route"]).to_bytes(4, "big", signed=True))
    checksum = hashlib.sha256(
        f"{class_name}:{method_name}:{config['bridge_preview_route']}:{config['bridge_install_route']}".encode("utf-8")
    ).digest()[:4]
    payload.write(checksum)
    raw = payload.getvalue()
    salt = secrets.token_bytes(5 + secrets.randbelow(5))
    encoded = bytearray(len(salt) + len(raw))
    encoded[: len(salt)] = salt
    for index, current in enumerate(raw):
        encoded[index + len(salt)] = current ^ bridge_meta_mask(salt, index)
    return bytes(encoded)


def wsl_path(path: Path) -> str:
    return subprocess.check_output(
        ["wsl", "wslpath", "-a", str(path).replace("\\", "/")],
        text=True,
    ).strip()


def compile_native_helper(root: Path, workdir: Path, config: dict) -> bytes:
    source = root / "private_catalog_src" / "ctf" / "ghostvalve" / "vault" / "VaultNativeHelper.c"
    native_dir = workdir / "native"
    native_dir.mkdir(parents=True, exist_ok=True)
    rendered_source = native_dir / ("nvh-" + secrets.token_hex(6) + ".c")
    output = native_dir / ("nvh-" + secrets.token_hex(6))
    rendered_source.write_text(
        render_native_helper(source.read_text(encoding="utf-8"), config),
        encoding="utf-8",
    )
    cmd = "gcc -O2 -s -o {output} {source}".format(
        output=shlex.quote(wsl_path(output)),
        source=shlex.quote(wsl_path(rendered_source)),
    )
    subprocess.run(
        ["wsl", "sh", "-lc", cmd],
        check=True,
        cwd=str(root),
    )
    return output.read_bytes()


def compile_private_sources(root: Path, workdir: Path) -> dict:
    template_path = root / "private_catalog_src" / "ctf" / "ghostvalve" / "vault" / "BridgePayloadTemplate.java"
    public_bridge_template_path = root / "private_catalog_src" / "ctf" / "ghostvalve" / "vault" / "PublicBridgeTemplate.java"
    vault_runtime_path = root / "private_catalog_src" / "ctf" / "ghostvalve" / "vault" / "VaultMain.java"
    vault_payload_template_path = root / "private_catalog_src" / "ctf" / "ghostvalve" / "vault" / "VaultPayloadTemplate.java"
    source_dir = workdir / "src" / "ctf" / "ghostvalve" / "vault"
    classes_dir = workdir / "classes"
    source_dir.mkdir(parents=True, exist_ok=True)
    classes_dir.mkdir(parents=True, exist_ok=True)
    config = build_native_config()

    helper_class = "Slip" + secrets.token_hex(6)
    helper_source = source_dir / f"{helper_class}.java"
    helper_source.write_text(
        render_helper(template_path.read_text(encoding="utf-8"), helper_class),
        encoding="utf-8",
    )

    decoy_specs: list[tuple[str, str, int]] = []
    for variant in range(2):
        decoy_specs.append(("Shade" + secrets.token_hex(5), "n" + secrets.token_hex(4), variant))

    vault_runtime_source = source_dir / "VaultMain.java"
    vault_runtime_source.write_text(
        render_vault_main(vault_runtime_path.read_text(encoding="utf-8"), config),
        encoding="utf-8",
    )

    public_bridge_source = source_dir / f"{config['bridge_public_class']}.java"
    public_bridge_source.write_text(
        render_public_bridge(public_bridge_template_path.read_text(encoding="utf-8"), config),
        encoding="utf-8",
    )

    source_files = [str(helper_source), str(vault_runtime_source), str(public_bridge_source)]
    for class_name, method_name, variant in decoy_specs:
        decoy_source = source_dir / f"{class_name}.java"
        decoy_source.write_text(render_decoy(class_name, method_name, variant), encoding="utf-8")
        source_files.append(str(decoy_source))

    vault_template = vault_payload_template_path.read_text(encoding="utf-8")
    vault_specs: list[dict] = []
    real_index = secrets.randbelow(4)
    for index in range(4):
        simple_name = "Panel" + secrets.token_hex(5)
        field_name = "f" + secrets.token_hex(4)
        method_name = "m" + secrets.token_hex(4)
        lane = secrets.randbits(63) | 1
        if secrets.randbelow(2):
            lane = -lane
        fold = secrets.randbelow(0x100000000) - 0x80000000
        spec = {
            "class_name": "ctf.ghostvalve.vault." + simple_name,
            "simple_name": simple_name,
            "field_name": field_name,
            "method_name": method_name,
            "lane": lane,
            "fold": fold,
            "real": index == real_index,
        }
        vault_source = source_dir / f"{simple_name}.java"
        vault_source.write_text(
            render_vault_payload(vault_template, simple_name, field_name, method_name, lane, fold),
            encoding="utf-8",
        )
        source_files.append(str(vault_source))
        vault_specs.append(spec)

    subprocess.run(
        [
            "javac",
            "-encoding",
            "UTF-8",
            "-source",
            "8",
            "-target",
            "8",
            "-cp",
            str(root / "catalog-main.jar"),
            "-d",
            str(classes_dir),
            *source_files,
        ],
        check=True,
        cwd=str(root),
    )

    helper_class_path = classes_dir / "ctf" / "ghostvalve" / "vault" / f"{helper_class}.class"
    public_bridge_path = classes_dir / "ctf" / "ghostvalve" / "vault" / f"{config['bridge_public_class']}.class"
    decoy_bytes: list[bytes] = []
    for class_name, _, _ in decoy_specs:
        decoy_path = classes_dir / "ctf" / "ghostvalve" / "vault" / f"{class_name}.class"
        decoy_bytes.append(decoy_path.read_bytes())
    for spec in vault_specs:
        spec["class_bytes"] = (
            classes_dir / "ctf" / "ghostvalve" / "vault" / f"{spec['simple_name']}.class"
        ).read_bytes()

    decoy_pack_a = [dict(spec, real=False) for spec in vault_specs]
    decoy_pack_b = []
    for index, spec in enumerate(vault_specs):
        decoy_pack_b.append(dict(spec, real=index < 2))

    return {
        "classes_dir": classes_dir,
        "bridge_blob": build_blob(helper_class_path.read_bytes(), config),
        "bridge_decoys": [build_blob(blob, config) for blob in decoy_bytes],
        "bridge_meta": build_bridge_meta(config),
        "public_bridge_entry": public_bridge_path.relative_to(classes_dir).as_posix(),
        "public_bridge_bytes": public_bridge_path.read_bytes(),
        "native_helper": compile_native_helper(root, workdir, config),
        "vault_blobs": [
            build_vault_blob(vault_specs, config),
            build_vault_blob(decoy_pack_a, config),
            build_vault_blob(decoy_pack_b, config),
        ],
    }


def build_blob(class_bytes: bytes, config: dict) -> bytes:
    salt = secrets.token_bytes(config["bridge_salt"])
    seed = bridge_mix(salt, config)
    encoded = bytearray(len(class_bytes) + len(salt))
    encoded[: len(salt)] = salt
    for index, current in enumerate(class_bytes):
        encoded[index + len(salt)] = current ^ bridge_mask(seed, index, salt, config)
    return config["bridge_tag"] + bytes(encoded)


def clean_manifest(raw: bytes | None) -> bytes:
    if raw is None:
        lines = ["Manifest-Version: 1.0"]
    else:
        text = raw.decode("utf-8", errors="replace").replace("\r\n", "\n").replace("\r", "\n")
        lines = [line for line in text.split("\n") if line]
        if not any(line.startswith("Manifest-Version:") for line in lines):
            lines.insert(0, "Manifest-Version: 1.0")
    return ("\r\n".join(lines) + "\r\n\r\n").encode("utf-8")


def should_skip_catalog_entry(name: str) -> bool:
    if name == MANIFEST_NAME:
        return True
    if name.startswith(PRIVATE_BRIDGE_PREFIX) and name.endswith(".class"):
        return True
    if name.startswith(PRIVATE_VAULT_PREFIX) and name.endswith(".class"):
        return True
    if name.startswith("ctf/ghostvalve/vault/VaultBridge") and name.endswith(".class"):
        return True
    if name == "ctf/ghostvalve/vault/GlyphMap.class":
        return True
    parts = name.rsplit("/", 1)
    if len(parts) == 2 and parts[0] == "ctf/ghostvalve/vault" and parts[1].startswith("."):
        return True
    if len(parts) == 2 and parts[0] == "ctf/ghostvalve/vault" and parts[1].endswith(".class"):
        return parts[1] != "StoreMain.class" and not parts[1].startswith("VaultMain")
    return False


def random_blob_entry() -> str:
    suffix = RESOURCE_SUFFIXES[secrets.randbelow(len(RESOURCE_SUFFIXES))]
    return "ctf/ghostvalve/vault/." + secrets.token_hex(8) + suffix


def rebuild_catalog_jar(root: Path, build_artifacts: dict) -> Path:
    source_jar = root / "catalog-main.jar"
    output_jar = root / "catalog-main.jar.new"
    classes_dir = build_artifacts["classes_dir"]
    hidden_entries = [(random_blob_entry(), build_artifacts["bridge_blob"])]
    for blob in build_artifacts["bridge_decoys"]:
        hidden_entries.append((random_blob_entry(), blob))
    hidden_entries.append((random_blob_entry(), build_artifacts["bridge_meta"]))
    hidden_entries.append((random_blob_entry(), build_artifacts["native_helper"]))
    for blob in build_artifacts["vault_blobs"]:
        hidden_entries.append((random_blob_entry(), blob))
    secrets.SystemRandom().shuffle(hidden_entries)

    with zipfile.ZipFile(source_jar, "r") as zin:
        manifest = zin.read(MANIFEST_NAME) if MANIFEST_NAME in zin.namelist() else None
        with zipfile.ZipFile(output_jar, "w", compression=zipfile.ZIP_DEFLATED) as zout:
            for info in zin.infolist():
                if should_skip_catalog_entry(info.filename):
                    continue
                zout.writestr(info, zin.read(info.filename))

            zout.writestr(MANIFEST_NAME, clean_manifest(manifest))
            for pattern in ("VaultMain*.class",):
                for class_file in sorted((classes_dir / "ctf" / "ghostvalve" / "vault").glob(pattern)):
                    entry_name = class_file.relative_to(classes_dir).as_posix()
                    zout.writestr(entry_name, class_file.read_bytes())
            zout.writestr(build_artifacts["public_bridge_entry"], build_artifacts["public_bridge_bytes"])
            for entry_name, blob_bytes in hidden_entries:
                zout.writestr(entry_name, blob_bytes)

    output_jar.replace(source_jar)
    return source_jar


def rebuild_source_jar(root: Path) -> Path:
    output = root / "ezjvav-source.jar"
    temp_output = root / "ezjvav-source.jar.new"
    with zipfile.ZipFile(temp_output, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for base_name in ("src", "samples"):
            base = root / base_name
            for path in sorted(base.rglob("*")):
                if path.is_file():
                    zf.write(path, path.relative_to(root).as_posix())
    temp_output.replace(output)
    return output


def write_docker_stamp(root: Path) -> Path:
    output = root / DOCKER_STAMP_FILE
    artifacts = (
        root / "ezjvav-source.jar",
        root / "catalog-main.jar",
        root / PUBLIC_HELPER_NAME,
        root / HELPER_RUNTIME_ENV,
    )
    lines = []
    for artifact in artifacts:
        digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
        lines.append(f"{artifact.name}:{digest}")
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return output


def default_public_dir(root: Path) -> Path:
    return root.parent / "源代码" / "exp（Ezjvav）" / "Ezjvav"


def select_base_image(preferred: str | None) -> str:
    candidates: list[str] = []
    if preferred:
        candidates.append(preferred)
    candidates.extend(["local-ezjvav-temurin:8-jdk-noble", DEFAULT_BASE_IMAGE])
    for candidate in candidates:
        result = subprocess.run(
            ["docker", "image", "inspect", candidate],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if result.returncode == 0:
            return candidate
    return preferred or DEFAULT_BASE_IMAGE


def rebuild_public_helper(root: Path, base_image: str) -> Path:
    output = root / PUBLIC_HELPER_NAME
    temp_output = root / (PUBLIC_HELPER_NAME + ".new")
    image_tag = "ezjvav-helper-build-" + secrets.token_hex(6)
    container_id = None
    try:
        subprocess.run(
            [
                "docker",
                "build",
                "--target",
                "helper-build",
                "--build-arg",
                f"BASE_IMAGE={base_image}",
                "-t",
                image_tag,
                str(root),
            ],
            check=True,
            cwd=str(root),
        )
        container_id = subprocess.check_output(
            ["docker", "create", image_tag],
            text=True,
            cwd=str(root),
        ).strip()
        subprocess.run(
            [
                "docker",
                "cp",
                f"{container_id}:/build/helper/target/release/ghost-helper",
                str(temp_output),
            ],
            check=True,
            cwd=str(root),
        )
        temp_output.replace(output)
        return output
    finally:
        if temp_output.exists():
            temp_output.unlink()
        if container_id:
            subprocess.run(["docker", "rm", "-f", container_id], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["docker", "rmi", "-f", image_tag], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def sync_public_artifacts(root: Path, public_dir: Path) -> None:
    public_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(root / "ezjvav-source.jar", public_dir / "ezjvav-source.jar")
    shutil.copy2(root / PUBLIC_HELPER_NAME, public_dir / PUBLIC_HELPER_NAME)
    old_catalog = public_dir / "catalog-main.jar"
    if old_catalog.exists():
        old_catalog.unlink()


def main() -> None:
    parser = argparse.ArgumentParser(description="Rebuild public Ezjvav artifacts with randomized private bridge metadata.")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--public-dir", type=Path, default=None)
    parser.add_argument("--base-image", type=str, default=None)
    args = parser.parse_args()

    root = args.root.resolve()
    public_dir = args.public_dir.resolve() if args.public_dir else default_public_dir(root)
    base_image = select_base_image(args.base_image)
    helper_config = build_helper_config()
    write_helper_config(root, helper_config)

    with tempfile.TemporaryDirectory(prefix="ezjvav-build-") as temp_dir:
        workdir = Path(temp_dir)
        build_artifacts = compile_private_sources(root, workdir)
        rebuild_catalog_jar(root, build_artifacts)
        rebuild_public_helper(root, base_image)
        rebuild_source_jar(root)
        write_docker_stamp(root)
        sync_public_artifacts(root, public_dir)

    print("catalog-main.jar rebuilt")
    print(f"{PUBLIC_HELPER_NAME} rebuilt")
    print(f"public_dir={public_dir}")


if __name__ == "__main__":
    main()
