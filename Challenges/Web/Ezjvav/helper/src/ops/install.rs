include!(concat!(env!("OUT_DIR"), "/generated.rs"));

fn normalize_hex(value: &[u8]) -> Option<String> {
    let text = std::str::from_utf8(value).ok()?.trim().to_ascii_lowercase();
    if text.len() < 16 || text.len() > 64 || !text.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    Some(text)
}

fn sha256_hex(value: &str) -> String {
    crate::ops::preview::sha256::digest(value)
}

fn slot<'a>(fields: &'a [Vec<u8>], order: &[usize], canonical: usize) -> Option<&'a [u8]> {
    for (index, current) in order.iter().enumerate() {
        if *current == canonical {
            return fields.get(index).map(|value| value.as_slice());
        }
    }
    None
}

pub fn verify(fields: &[Vec<u8>]) -> bool {
    if fields.len() < INSTALL_ORDER.len() {
        return false;
    }
    let client = match normalize_hex(slot(fields, &INSTALL_ORDER, 0).unwrap_or(&[])) {
        Some(value) => value,
        None => return false,
    };
    let token = match normalize_hex(slot(fields, &INSTALL_ORDER, 1).unwrap_or(&[])) {
        Some(value) => value,
        None => return false,
    };
    let flow = match normalize_hex(slot(fields, &INSTALL_ORDER, 2).unwrap_or(&[])) {
        Some(value) => value,
        None => return false,
    };
    let marker = match normalize_hex(slot(fields, &INSTALL_ORDER, 3).unwrap_or(&[])) {
        Some(value) => value,
        None => return false,
    };
    let digest = match normalize_hex(slot(fields, &INSTALL_ORDER, 4).unwrap_or(&[])) {
        Some(value) => value,
        None => return false,
    };
    let proof = match normalize_hex(slot(fields, &INSTALL_ORDER, 5).unwrap_or(&[])) {
        Some(value) => value,
        None => return false,
    };
    let witness = match normalize_hex(slot(fields, &INSTALL_ORDER, 6).unwrap_or(&[])) {
        Some(value) => value,
        None => return false,
    };
    let reversed_flow: String = flow.chars().rev().collect();
    let expected = sha256_hex(&format!(
        "{}:{}:{}:{}:{}:{}",
        client, token, reversed_flow, marker, digest, witness
    ));
    let end = INSTALL_SLICE_START + 32;
    if end > expected.len() {
        return false;
    }
    expected.get(INSTALL_SLICE_START..end).map(|value| value == proof).unwrap_or(false)
}
