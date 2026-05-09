import json
import subprocess
from flask import Flask, request, jsonify, render_template, Request

class CustomRequest(Request):
    @property
    def charset(self):
        try:
            return self.mimetype_params.get('charset', 'utf-8')
        except:
            return 'utf-8'

app = Flask(__name__)

app.request_class = CustomRequest

@app.before_request
def waf_middleware():
    raw_data = request.get_data()
    blacklist = [
        b'flag', b'cat', b'ls', b'bash', b'sh', b'nc',
        b';', b'|', b'&', b'$', b'>', b'<', b'`', b'\n', b'\\'
    ]
    for word in blacklist:
        if word in raw_data:
            return jsonify({'error': 'No hacker!'}), 403

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/api/ping', methods=['POST'])
def ping_tool():
    try:
        raw_text = request.get_data(as_text=True) 
        data = json.loads(raw_text)
    except Exception:
        return jsonify({'error': 'Invalid request format'}), 400

    target = data.get('target', '')
    if not target:
        return jsonify({'error': 'Missing target parameter'}), 400

    if len(target) > 50:
        return jsonify({'error': 'Target too long'}), 400

    command = f"ping -c 1 {target}"
    
    try:
        process = subprocess.run(
            command,
            shell=True,
            capture_output=True,
            text=True,
            timeout=5
        )
        if process.returncode != 0:
            return jsonify({'success': False, 'output': process.stderr})
        return jsonify({'success': True, 'output': process.stdout})
    except Exception:
        return jsonify({'success': False, 'output': 'Command execution failed'}), 500

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000)