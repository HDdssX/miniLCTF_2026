import io
from flask import Flask, Response, render_template, request, send_file

from codec import DEFAULT_WIDTH, text_to_bmp_bytes


app = Flask(__name__)


@app.get("/")
def index():
    return render_template(
        "index.html",
        bmp_width=DEFAULT_WIDTH,
        preview_scale=16,
    )


@app.post("/api/render")
def render_bmp():
    text = request.form.get("text", "")
    if not text:
        return Response("text is required\n", status=400, mimetype="text/plain")

    bmp_bytes = text_to_bmp_bytes(text, width=DEFAULT_WIDTH)
    return send_file(
        io.BytesIO(bmp_bytes),
        mimetype="image/bmp",
        as_attachment=False,
        download_name="render.bmp",
    )


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8000, debug=False)
