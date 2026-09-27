"""Local protocol sink: checks authenticated requests without logging their contents."""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
counts = {}
class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(json.dumps(counts).encode())
    def do_POST(self):
        body=self.rfile.read(int(self.headers.get('Content-Length',0)))
        if self.headers.get('Authorization') != 'Basic dGVzdDp0ZXN0':
            self.send_response(401); self.end_headers(); return
        if body: counts[self.path] = counts.get(self.path,0)+1
        self.send_response(200); self.end_headers()
HTTPServer(('0.0.0.0',8080),Handler).serve_forever()
