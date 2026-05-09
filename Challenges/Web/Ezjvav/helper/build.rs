use std::collections::HashMap;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

fn mix(mut value: u64) -> u64 {
    value ^= value << 13;
    value ^= value >> 7;
    value ^= value << 17;
    value
}

fn parse_config(path: &Path) -> HashMap<String, String> {
    let mut values = HashMap::new();
    let raw = match fs::read_to_string(path) {
        Ok(content) => content,
        Err(_) => return values,
    };
    for line in raw.lines() {
        let text = line.trim();
        if text.is_empty() || text.starts_with('#') {
            continue;
        }
        if let Some(split) = text.find('=') {
            let key = text[..split].trim();
            let value = text[split + 1..].trim();
            if !key.is_empty() && !value.is_empty() {
                values.insert(key.to_string(), value.to_string());
            }
        }
    }
    values
}

fn read_u8(config: &HashMap<String, String>, key: &str, default: u8) -> u8 {
    config
        .get(key)
        .and_then(|value| value.trim().parse::<u16>().ok())
        .map(|value| (value & 0xff) as u8)
        .unwrap_or(default)
}

fn read_u32(config: &HashMap<String, String>, key: &str, default: u32) -> u32 {
    config
        .get(key)
        .and_then(|value| value.trim().parse::<u32>().ok())
        .unwrap_or(default)
}

fn read_usize(config: &HashMap<String, String>, key: &str, default: usize) -> usize {
    config
        .get(key)
        .and_then(|value| value.trim().parse::<usize>().ok())
        .unwrap_or(default)
}

fn random_perm(seed: &mut u64, size: usize) -> Vec<usize> {
    let mut items: Vec<usize> = (0..size).collect();
    let mut index = size;
    while index > 1 {
        index -= 1;
        *seed = mix(*seed ^ ((index as u64) << 11) ^ 0x9e37_79b9_7f4a_7c15);
        let swap = (*seed as usize) % (index + 1);
        items.swap(index, swap);
    }
    items
}

fn parse_perm(config: &HashMap<String, String>, key: &str, size: usize, seed: &mut u64) -> Vec<usize> {
    if let Some(value) = config.get(key) {
        let parts: Vec<usize> = value
            .split(',')
            .filter_map(|item| item.trim().parse::<usize>().ok())
            .collect();
        if parts.len() == size {
            let mut seen = vec![false; size];
            for current in &parts {
                if *current >= size || seen[*current] {
                    return random_perm(seed, size);
                }
                seen[*current] = true;
            }
            return parts;
        }
    }
    random_perm(seed, size)
}

fn array_literal(items: &[usize]) -> String {
    let joined = items
        .iter()
        .map(|item| format!("{item}usize"))
        .collect::<Vec<String>>()
        .join(", ");
    format!("[{joined}]")
}

fn main() {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let config = parse_config(&manifest_dir.join(".build-config.env"));

    let base = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos() as u64;
    let mut seed = mix(base ^ ((std::process::id() as u64) << 19) ^ 0x9e37_79b9_7f4a_7c15);

    let op_mask = read_u8(&config, "HELPER_OP_MASK", ((seed as u8) | 1).wrapping_add(0x23));
    let branch_seed = read_u32(&config, "HELPER_BRANCH_SEED", mix(seed ^ 0xa5a5_5a5a_c3c3_3c3c) as u32);

    let ping_opcode = read_u8(&config, "HELPER_OP_PING", 1);
    let preview_opcode = read_u8(&config, "HELPER_OP_PREVIEW", 2);
    let install_opcode = read_u8(&config, "HELPER_OP_INSTALL", 3);
    let prewarm_opcode = read_u8(&config, "HELPER_OP_PREWARM", 4);

    let preview_order = parse_perm(&config, "HELPER_ORDER_PREVIEW", 5, &mut seed);
    let install_order = parse_perm(&config, "HELPER_ORDER_INSTALL", 7, &mut seed);

    let preview_tail_handle = read_usize(&config, "HELPER_PREVIEW_TAIL_HANDLE", 4);
    let preview_tail_witness = read_usize(&config, "HELPER_PREVIEW_TAIL_WITNESS", 3);
    let preview_head_token = read_usize(&config, "HELPER_PREVIEW_HEAD_TOKEN", 4);
    let preview_head_client = read_usize(&config, "HELPER_PREVIEW_HEAD_CLIENT", 3);
    let preview_head_witness = read_usize(&config, "HELPER_PREVIEW_HEAD_WITNESS", 2);
    let preview_head_handle = read_usize(&config, "HELPER_PREVIEW_HEAD_HANDLE", 2);
    let preview_hash_head = read_usize(&config, "HELPER_PREVIEW_HASH_HEAD", 3);
    let preview_hash_tail = read_usize(&config, "HELPER_PREVIEW_HASH_TAIL", 4);
    let preview_slice_start = read_usize(&config, "HELPER_PREVIEW_SLICE_START", 0);
    let install_slice_start = read_usize(&config, "HELPER_INSTALL_SLICE_START", 8);

    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR"));
    let generated = format!(
        "pub const OP_MASK: u8 = {op_mask};\n\
         pub const BRANCH_SEED: u32 = {branch_seed}u32;\n\
         pub const PING_OPCODE: u8 = {ping_opcode};\n\
         pub const PREVIEW_OPCODE: u8 = {preview_opcode};\n\
         pub const INSTALL_OPCODE: u8 = {install_opcode};\n\
         pub const PREWARM_OPCODE: u8 = {prewarm_opcode};\n\
         pub const PREVIEW_ORDER: [usize; 5] = {preview_order};\n\
         pub const INSTALL_ORDER: [usize; 7] = {install_order};\n\
         pub const PREVIEW_TAIL_HANDLE: usize = {preview_tail_handle};\n\
         pub const PREVIEW_TAIL_WITNESS: usize = {preview_tail_witness};\n\
         pub const PREVIEW_HEAD_TOKEN: usize = {preview_head_token};\n\
         pub const PREVIEW_HEAD_CLIENT: usize = {preview_head_client};\n\
         pub const PREVIEW_HEAD_WITNESS: usize = {preview_head_witness};\n\
         pub const PREVIEW_HEAD_HANDLE: usize = {preview_head_handle};\n\
         pub const PREVIEW_HASH_HEAD: usize = {preview_hash_head};\n\
         pub const PREVIEW_HASH_TAIL: usize = {preview_hash_tail};\n\
         pub const PREVIEW_SLICE_START: usize = {preview_slice_start};\n\
         pub const INSTALL_SLICE_START: usize = {install_slice_start};\n",
        preview_order = array_literal(&preview_order),
        install_order = array_literal(&install_order),
    );
    fs::write(out_dir.join("generated.rs"), generated).expect("write generated.rs");
}
