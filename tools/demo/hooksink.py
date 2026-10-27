"""A tiny webhook receiver for the integration run: records every POST (headers and body) and lists them on GET /received.
DEVELOPMENT ONLY. POST /fail-next?n=2 makes the next n deliveries answer 500 (to see retries)."""
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

RECEIVED = []
FAIL = {"left": 0}
LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body=b"{}"):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        with LOCK:
            self._send(200, json.dumps(RECEIVED).encode())

    def do_POST(self):
        url = urlparse(self.path)
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length).decode("utf-8", "replace")
        if url.path == "/fail-next":
            with LOCK:
                FAIL["left"] = int(parse_qs(url.query).get("n", ["1"])[0])
            return self._send(200)
        if url.path == "/reset":
            with LOCK:
                RECEIVED.clear()
            return self._send(200)
        with LOCK:
            fail = FAIL["left"] > 0
            if fail:
                FAIL["left"] -= 1
            RECEIVED.append({"path": url.path, "headers": {k.lower(): v for k, v in self.headers.items()}, "body": raw, "status": 500 if fail else 200})
        self._send(500 if fail else 200)

    def log_message(self, *args):
        pass


ThreadingHTTPServer(("0.0.0.0", 9000), Handler).serve_forever()
