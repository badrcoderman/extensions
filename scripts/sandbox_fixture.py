#!/usr/bin/env python3
"""Serve a fixed canary to a locally installed sandbox diagnostics extension."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MARKER = b'extension-sandbox-canary-v1'


class CanaryHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        # No client addresses, request URLs or headers are recorded.
        pass

    def do_GET(self):
        body = MARKER if self.path == '/canary' else b'Not found'
        self.send_response(200 if self.path == '/canary' else 404)
        self.send_header('Content-Type', 'text/plain')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bind', default='127.0.0.1', help='Use a private interface or 0.0.0.0 for an iPhone on your LAN')
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.bind, 4570), CanaryHandler)
    print('Fixed canary server listening on port 4570. Press Ctrl+C to stop.', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
