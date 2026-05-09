use crate::ops;
use crate::protocol::{self, Request, Status};

include!(concat!(env!("OUT_DIR"), "/generated.rs"));

pub fn run_stdio() -> Result<(), String> {
    let request = protocol::read_request(std::io::stdin()).map_err(|_| "request".to_string())?;
    let (status, fields) = dispatch(request);
    protocol::write_response(std::io::stdout(), status, &fields).map_err(|_| "response".to_string())
}

fn dispatch(request: Request) -> (Status, Vec<Vec<u8>>) {
    match lane(request.opcode) {
        value if value == encoded(PING_OPCODE) => (Status::Ok, Vec::new()),
        value if value == encoded(PREWARM_OPCODE) => (Status::Ok, Vec::new()),
        value if value == encoded(PREVIEW_OPCODE) => match ops::preview::verify(&request.fields) {
            Some(value) => (Status::Ok, vec![value.into_bytes()]),
            None => (Status::Reject, Vec::new()),
        },
        value if value == encoded(INSTALL_OPCODE) => {
            if ops::install::verify(&request.fields) {
                (Status::Ok, Vec::new())
            } else {
                (Status::Reject, Vec::new())
            }
        }
        _ => (Status::Reject, Vec::new()),
    }
}

fn lane(opcode: u8) -> u8 {
    opcode ^ OP_MASK
}

fn encoded(opcode: u8) -> u8 {
    opcode ^ OP_MASK ^ (((BRANCH_SEED >> 3) as u8) & 0)
}
