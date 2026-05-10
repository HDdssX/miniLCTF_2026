import hashlib
import hmac
import os
import subprocess
from base64 import urlsafe_b64decode
from pathlib import Path

import requests
from flask import Flask, Response, redirect, render_template, request, session, url_for
from werkzeug.utils import secure_filename

POSTGREST_TIMEOUT = 5
ARCHIVE_EXTRACT_TIMEOUT = 15
POSTGREST_INTERNAL_URL = "http://127.0.0.1:3000"
ADMIN_TABLE = "admins"
SESSION_ADMIN_KEY = "admin_username"


def resolve_app_root():
    container_root = Path("/app")
    if container_root.exists():
        return container_root
    return Path(__file__).resolve().parent


APP_ROOT = resolve_app_root()
STATIC_ROOT = APP_ROOT / "static"
UPLOAD_ROOT = APP_ROOT / "uploads"

app = Flask(__name__)
app.config["SECRET_KEY"] = "hakurei-shrine-console-session-2026"
app.config["TEMPLATES_AUTO_RELOAD"] = True
app.jinja_env.auto_reload = True


def fetch_admin_record(username):
    response = requests.get(
        f"{POSTGREST_INTERNAL_URL}/{ADMIN_TABLE}",
        params={
            "username": f"eq.{username}",
            "select": "username,password_hash",
        },
        timeout=POSTGREST_TIMEOUT,
    )
    response.raise_for_status()
    rows = response.json()
    return rows[0] if rows else None


def is_admin_authenticated():
    return bool(session.get(SESSION_ADMIN_KEY))


def ensure_admin_directories():
    UPLOAD_ROOT.mkdir(parents=True, exist_ok=True)


def verify_admin_password(password, stored_hash):
    try:
        algorithm_id, rounds_text, salt, digest = stored_hash.split("$")[1:]
    except ValueError:
        return False

    if algorithm_id != "pbkdf2-sha256":
        return False

    try:
        rounds = int(rounds_text)
    except ValueError:
        return False

    derived = hashlib.pbkdf2_hmac(
        "sha256",
        password.encode("utf-8"),
        salt.encode("utf-8"),
        rounds,
    )
    padded_digest = digest + "=" * (-len(digest) % 4)
    return hmac.compare_digest(derived, urlsafe_b64decode(padded_digest))


@app.route("/")
def index():
    return render_template("index.html")


@app.route("/admin/login", methods=["GET", "POST"])
def admin_login():
    if is_admin_authenticated():
        return redirect(url_for("admin_dashboard"))

    error_message = ""

    if request.method == "POST":
        username = request.form.get("username", "").strip()
        password = request.form.get("password", "")

        if not username or not password:
            error_message = "请填写完整的社务账号与通行口令。"
        else:
            try:
                admin_record = fetch_admin_record(username)
            except requests.RequestException:
                error_message = "河童后台暂时无法读取管理员名册。"
            else:
                if admin_record and verify_admin_password(password, admin_record.get("password_hash", "")):
                    session[SESSION_ADMIN_KEY] = username
                    return redirect(url_for("admin_dashboard"))
                error_message = "社务名册未通过核验。"

    return render_template("admin_login.html", error_message=error_message)


@app.route("/admin")
def admin_dashboard():
    if not is_admin_authenticated():
        return redirect(url_for("admin_login"))

    ensure_admin_directories()
    return render_template(
        "admin.html",
        admin_username=session[SESSION_ADMIN_KEY],
        upload_status=session.pop("upload_status", None),
        static_tree=build_directory_tree(STATIC_ROOT),
    )


def file_size_text(size):
    units = ["B", "KB", "MB", "GB"]
    value = float(size)
    for unit in units:
        if value < 1024 or unit == units[-1]:
            if unit == "B":
                return f"{int(value)} {unit}"
            return f"{value:.1f} {unit}"
        value /= 1024
    return f"{int(size)} B"


def build_directory_tree(root_path):
    root_path = Path(root_path)
    exists = root_path.exists() or root_path.is_symlink()
    is_link = root_path.is_symlink()
    is_dir = exists and root_path.is_dir() and not is_link
    node = {
        "name": root_path.name or str(root_path),
        "path": str(root_path),
        "exists": exists,
        "is_dir": is_dir,
        "is_link": is_link,
        "link_target": None,
        "resolved_path": None,
        "size_text": "",
        "url": None,
        "children": [],
    }

    if not exists:
        return node

    if is_link:
        node["link_target"] = str(root_path.readlink())
        try:
            node["resolved_path"] = str(root_path.resolve(strict=False))
        except OSError:
            node["resolved_path"] = None

    if root_path.is_file():
        try:
            node["size_text"] = file_size_text(root_path.stat().st_size)
        except OSError:
            node["size_text"] = ""
        try:
            relative_static = root_path.relative_to(STATIC_ROOT)
        except ValueError:
            relative_static = None
        if relative_static is not None:
            node["url"] = url_for("static", filename=relative_static.as_posix())
        return node

    if is_link:
        return node

    try:
        children = [build_directory_tree(entry) for entry in root_path.iterdir()]
    except OSError:
        return node

    node["children"] = sorted(
        children,
        key=lambda child: (not child["is_dir"], child["name"].lower()),
    )
    return node


@app.route("/admin/assets/upload", methods=["POST"])
def admin_assets_upload():
    if not is_admin_authenticated():
        return redirect(url_for("admin_login"))

    ensure_admin_directories()
    uploaded_file = request.files.get("asset_archive")
    if uploaded_file is None or not uploaded_file.filename:
        session["upload_status"] = {
            "kind": "error",
            "message": "未接收到打包后的静态资源。",
        }
        return redirect(url_for("admin_dashboard"))

    filename = secure_filename(uploaded_file.filename) or "upload.tar"
    archive_path = UPLOAD_ROOT / filename
    uploaded_file.save(archive_path)

    try:
        subprocess.run(
            ["tar", "-xf", str(archive_path), "-C", str(STATIC_ROOT)],
            capture_output=True,
            text=True,
            check=True,
            timeout=ARCHIVE_EXTRACT_TIMEOUT,
        )
    except subprocess.CalledProcessError as exc:
        stderr = (exc.stderr or exc.stdout or "").strip()
        session["upload_status"] = {
            "kind": "error",
            "message": f"解包失败：{stderr or 'tar returned a non-zero exit status.'}",
        }
    except subprocess.TimeoutExpired:
        session["upload_status"] = {
            "kind": "error",
            "message": "解包超时，社务终端未能完成资源同步。",
        }
    else:
        app.jinja_env.cache.clear()
        session["upload_status"] = {
            "kind": "success",
            "message": "已成功上传至 /app/uploads/",
        }

    return redirect(url_for("admin_dashboard"))


@app.route("/admin/logout", methods=["POST"])
def admin_logout():
    session.pop(SESSION_ADMIN_KEY, None)
    return redirect(url_for("admin_login"))


@app.route("/rest/v1/<path:path>", methods=["GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"])
def proxy(path):
    return do_proxy(path)


@app.route("/rest/v1/", methods=["GET", "OPTIONS"])
@app.route("/rest/v1", methods=["GET", "OPTIONS"])
def proxy_root():
    return do_proxy("")


def do_proxy(path):
    url = f"{POSTGREST_INTERNAL_URL}/{path}"
    if request.query_string:
        url = f"{url}?{request.query_string.decode('utf-8')}"

    res = requests.request(
        method=request.method,
        url=url,
        headers={k: v for k, v in request.headers if k.lower() != "host"},
        data=request.get_data(),
        cookies=request.cookies,
        allow_redirects=False,
        timeout=POSTGREST_TIMEOUT,
    )
    excluded_headers = ["content-encoding", "content-length", "transfer-encoding", "connection"]
    headers = [
        (name, value)
        for (name, value) in res.raw.headers.items()
        if name.lower() not in excluded_headers
    ]
    return Response(res.content, res.status_code, headers)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=False)
