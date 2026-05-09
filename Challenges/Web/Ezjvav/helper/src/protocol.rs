use std::io::{self, Read, Write};

pub const VERSION: u8 = 1;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Status {
    Reject = 0,
    Ok = 1,
    Error = 2,
}

#[derive(Clone, Debug)]
pub struct Request {
    pub opcode: u8,
    pub fields: Vec<Vec<u8>>,
}

pub fn read_request<R: Read>(mut reader: R) -> io::Result<Request> {
    let mut header = [0u8; 4];
    reader.read_exact(&mut header)?;
    let declared = u32::from_be_bytes(header) as usize;
    if declared == 0 || declared > 8192 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "frame"));
    }
    let mut body = vec![0u8; declared];
    reader.read_exact(&mut body)?;
    if body.len() < 4 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "body"));
    }
    let version = body[0];
    if version != VERSION {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "version"));
    }
    let opcode = body[1];
    let field_count = u16::from_be_bytes([body[2], body[3]]) as usize;
    let mut offset = 4usize;
    let mut fields = Vec::with_capacity(field_count);
    for _ in 0..field_count {
        if offset + 2 > body.len() {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "field-len"));
        }
        let size = u16::from_be_bytes([body[offset], body[offset + 1]]) as usize;
        offset += 2;
        if size == 0 || offset + size > body.len() {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "field"));
        }
        fields.push(body[offset..offset + size].to_vec());
        offset += size;
    }
    if offset != body.len() {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "tail"));
    }
    Ok(Request { opcode, fields })
}

pub fn write_response<W: Write>(mut writer: W, status: Status, fields: &[Vec<u8>]) -> io::Result<()> {
    let mut body = Vec::with_capacity(8);
    body.push(VERSION);
    body.push(status as u8);
    body.extend_from_slice(&(fields.len() as u16).to_be_bytes());
    for field in fields {
        if field.is_empty() || field.len() > u16::MAX as usize {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "response-field"));
        }
        body.extend_from_slice(&(field.len() as u16).to_be_bytes());
        body.extend_from_slice(field);
    }
    writer.write_all(&(body.len() as u32).to_be_bytes())?;
    writer.write_all(&body)?;
    writer.flush()
}
