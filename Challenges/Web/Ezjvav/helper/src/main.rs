mod ops;
mod protocol;
mod server;

fn main() {
    if let Err(err) = server::run_stdio() {
        let _ = protocol::write_response(
            std::io::stdout(),
            protocol::Status::Error,
            &[err.into_bytes()],
        );
        std::process::exit(1);
    }
}
