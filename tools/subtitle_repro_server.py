"""Server HTTP di prova per la riproduzione del bug OpenSubtitles sul player StreamNova.

Uso (riproduzione deterministica sull'emulatore Android TV):

    python tools/subtitle_repro_server.py 8099
    adb reverse tcp:8099 tcp:8099
    .\\gradlew.bat :app:connectedDebugAndroidTest ^
        "-Pandroid.testInstrumentationRunnerArguments.class=com.example.ui.screens.player.ExternalSubtitleFailureInstrumentedTest"

L'emulatore raggiunge cosi' il server sull'host via http://127.0.0.1:8099. La variante
DEBUG consente il traffico in chiaro verso il loopback (app/src/debug/res/xml/
network_security_config.xml); la variante RELEASE lo vieta.

Espone:
  /video.mp4    -> file video valido
  /video_404.mp4-> 404 (sorgente video irraggiungibile)
  /sub_ok.srt   -> sottotitolo SRT valido
  /sub_html.srt -> risposta 200 ma contenuto HTML (payload non decodificabile)
  /sub_404.srt  -> 404
  /sub_403.srt  -> 403
  /sub_500.srt  -> 500
  /manifest.json, /stream/... -> addon Stremio minimale (non usato dai test)
"""
import http.server
import json
import os
import socketserver
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8099

VIDEO = os.path.join(ROOT, "app", "src", "androidTest", "assets", "testvideo.mp4")

VALID_SRT = b"1\n00:00:01,000 --> 00:00:03,000\nciao\n\n"
HTML_SRT = b"<!DOCTYPE html><html><body><p>Not a subtitle</p></body></html>"

# Addon Stremio minimale: serve il video locale come unica sorgente, cosi'
# l'app puo' riprodurre un film sull'emulatore senza dipendere da un torrent.
ADDON_MANIFEST = {
    "id": "org.streamnova.localtest",
    "name": "StreamNova Local Test",
    "version": "1.0.0",
    "description": "Addon di prova locale per la riproduzione del bug sottotitoli.",
    "resources": ["stream"],
    "types": ["movie", "series"],
    "catalogs": [],
}

ADDON_STREAMS = {
    "streams": [
        {
            "name": "Local MP4",
            "title": "Local MP4 320x180",
            "url": "http://127.0.0.1:8099/video.mp4",
            "behaviorHints": {"bingeGroup": "streamnova-local"},
        }
    ]
}


class Handler(http.server.BaseHTTPRequestHandler):
    def _send(self, code, body, content_type):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/manifest.json":
            self._send(200, json.dumps(ADDON_MANIFEST).encode("utf-8"), "application/json")
        elif path.startswith("/stream/"):
            self._send(200, json.dumps(ADDON_STREAMS).encode("utf-8"), "application/json")
        elif path == "/video_404.mp4":
            self._send(404, b"missing video", "text/plain")
        elif path == "/video.mp4":
            if not os.path.exists(VIDEO):
                self._send(404, b"no video", "text/plain")
                return
            with open(VIDEO, "rb") as handle:
                body = handle.read()
            self._send(200, body, "video/mp4")
        elif path == "/sub_ok.srt":
            self._send(200, VALID_SRT, "application/x-subrip")
        elif path == "/sub_html.srt":
            self._send(200, HTML_SRT, "application/x-subrip")
        elif path == "/sub_404.srt":
            self._send(404, b"missing", "text/plain")
        elif path == "/sub_403.srt":
            self._send(403, b"forbidden", "text/plain")
        elif path == "/sub_500.srt":
            self._send(500, b"server error", "text/plain")
        else:
            self._send(404, b"unknown", "text/plain")

    def log_message(self, fmt, *args):
        sys.stderr.write("[srv] %s - %s\n" % (self.address_string(), fmt % args))


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    with Server(("127.0.0.1", PORT), Handler) as httpd:
        sys.stderr.write("[srv] listening on %d\n" % PORT)
        httpd.serve_forever()
