from __future__ import annotations

import base64
import shlex
import socket
import socketserver
from pathlib import Path


ROOT = Path("/app/site").resolve()
HOST = "0.0.0.0"
PORT = 1337
PROMPT = b"debug> "
HEARTBEAT_INTERVAL = 50
HEARTBEAT_PAYLOAD = b"\n# keepalive\ndebug> "
HTTP_PREFIXES = (b"GET ", b"HEAD ", b"POST ", b"OPTIONS ")
HTTP_REDIRECT = "https://ctf.xidian.edu.cn/wiki/13"
WELCOME = (
    "Recovery pod ready.\n"
    "Limited commands: help, cat <path>, gitcat <sha1>, quit\n"
    "Directory listing is disabled.\n"
)


def resolve_path(raw_path: str) -> Path:
    candidate = (ROOT / raw_path).resolve()
    if candidate != ROOT and ROOT not in candidate.parents:
        raise ValueError("path escapes workspace")
    return candidate


class Handler(socketserver.StreamRequestHandler):
    def handle(self) -> None:
        if self.maybe_handle_http():
            return
        self.connection.settimeout(HEARTBEAT_INTERVAL)
        try:
            self.write_line(WELCOME)
            while True:
                self.wfile.write(PROMPT)
                self.wfile.flush()
                line = self.read_command()
                if not line:
                    break
                response, should_quit = self.dispatch(line.decode("utf-8", "replace").strip())
                self.write_line(response)
                if should_quit:
                    break
        except OSError:
            return

    def write_line(self, text: str) -> None:
        self.wfile.write(text.encode("utf-8", "replace"))
        if not text.endswith("\n"):
            self.wfile.write(b"\n")
        self.wfile.flush()

    def read_command(self) -> bytes:
        buf = bytearray()
        while True:
            try:
                chunk = self.connection.recv(1)
            except TimeoutError:
                # Keep the websocket tunnel alive while preserving the current input buffer.
                self.wfile.write(HEARTBEAT_PAYLOAD)
                self.wfile.flush()
                continue
            if not chunk:
                return b""
            if chunk == b"\n":
                return bytes(buf)
            if chunk == b"\r":
                continue
            buf.extend(chunk)

    def maybe_handle_http(self) -> bool:
        self.connection.settimeout(0.15)
        try:
            first = self.connection.recv(4096, socket.MSG_PEEK)
        except TimeoutError:
            first = b""
        except OSError:
            first = b""
        finally:
            self.connection.settimeout(None)

        if not first.startswith(HTTP_PREFIXES):
            return False

        request_line = self.rfile.readline().decode("utf-8", "replace").strip()
        headers: dict[str, str] = {}
        while True:
            line = self.rfile.readline().decode("utf-8", "replace")
            if line in {"\r\n", "\n", ""}:
                break
            if ":" in line:
                key, value = line.split(":", 1)
                headers[key.strip().lower()] = value.strip()

        self.write_http(
            "302 Found",
            [
                ("Location", HTTP_REDIRECT),
                ("Content-Length", "0"),
            ],
            b"",
        )
        return True

    def write_http(self, status: str, headers: list[tuple[str, str]], body: bytes) -> None:
        response = [f"HTTP/1.1 {status}\r\n"]
        for key, value in headers:
            response.append(f"{key}: {value}\r\n")
        response.append("Connection: close\r\n\r\n")
        self.wfile.write("".join(response).encode("utf-8"))
        if body:
            self.wfile.write(body)
        self.wfile.flush()

    def dispatch(self, line: str) -> tuple[str, bool]:
        if not line:
            return ("", False)

        try:
            parts = shlex.split(line)
        except ValueError as exc:
            return (f"ERR parse: {exc}", False)

        cmd = parts[0].lower()

        if cmd in {"quit", "exit"}:
            return ("bye", True)
        if cmd == "help":
            return (
                "help                     show this message\n"
                "cat <path>               print a file under /app/site\n"
                "gitcat <sha1>            print base64(raw git object)\n"
                "quit                     close the session",
                False,
            )
        if cmd == "cat":
            return (self.cmd_cat(parts[1:]), False)
        if cmd == "gitcat":
            return (self.cmd_gitcat(parts[1:]), False)
        return (f"ERR unknown command: {cmd}", False)

    def cmd_cat(self, args: list[str]) -> str:
        if len(args) != 1:
            return "ERR usage: cat <path>"
        try:
            target = resolve_path(args[0])
        except ValueError:
            return "ERR invalid path"
        if not target.exists():
            return "ERR not found"
        if target.is_dir():
            return "ERR directory listing disabled"
        return target.read_text(encoding="utf-8", errors="replace")

    def cmd_gitcat(self, args: list[str]) -> str:
        if len(args) != 1:
            return "ERR usage: gitcat <sha1>"
        sha1 = args[0].strip()
        if len(sha1) != 40 or any(ch not in "0123456789abcdef" for ch in sha1):
            return "ERR sha1 must be 40 lowercase hex chars"
        obj_path = ROOT / ".git" / "objects" / sha1[:2] / sha1[2:]
        if not obj_path.exists() or obj_path.is_dir():
            return "ERR object not available"
        return base64.b64encode(obj_path.read_bytes()).decode()


class ReusableTCPServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    with ReusableTCPServer((HOST, PORT), Handler) as server:
        server.serve_forever()
