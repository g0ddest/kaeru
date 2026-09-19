"""Run a compiled simulator executable against a loopback OAuth fixture. No real credentials."""
import http.server
import subprocess
import sys
import threading
import urllib.parse

seen = []


class Fixture(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = self.headers.get("Content-Length")
        body = self.rfile.read(int(length or 0))
        seen.append((self.path, length, body))
        self.send_response(401)
        self.end_headers()
        self.wfile.write(b"private-response-body")

    def log_message(self, *_):
        pass


server = http.server.HTTPServer(("127.0.0.1", 0), Fixture)
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
try:
    result = subprocess.run([
        "xcrun", "simctl", "spawn", sys.argv[1], sys.argv[2],
        f"http://127.0.0.1:{server.server_port}/base/",
    ], timeout=45, check=True)
    assert len(seen) == 1, seen
    path, length, body = seen[0]
    assert path == "/base/oauth/token", path
    assert int(length) == len(body) and len(body) > 0, "Darwin must send Content-Length"
    form = urllib.parse.parse_qs(body.decode())
    assert form == {
        "grant_type": ["authorization_code"], "client_id": ["test"],
        "code": ["test+&日本"], "redirect_uri": ["kaeru://oauth"],
    }, form
    print("Darwin OAuth Content-Length and form encoding PASS")
finally:
    server.shutdown()
    server.server_close()
