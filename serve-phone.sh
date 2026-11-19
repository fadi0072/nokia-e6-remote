#!/bin/bash
# Serves the phone app over Wi-Fi so you can install it from the E6's web browser.
cd "$(dirname "$0")/dist" || exit 1
[ -f E6Remote.jar ] || { echo "Run ./build-phone.sh first"; exit 1; }
IP=$(ipconfig getifaddr en0 || ipconfig getifaddr en1)
echo "On the Nokia E6, open the browser and go to:  http://$IP:8000/E6Remote.jad"
echo "(or http://$IP:8000/E6Remote.jar if the .jad doesn't install). Ctrl+C to stop."
exec python3 -c '
import http.server, socketserver
h = http.server.SimpleHTTPRequestHandler
h.extensions_map.update({".jad": "text/vnd.sun.j2me.app-descriptor", ".jar": "application/java-archive"})
socketserver.TCPServer.allow_reuse_address = True
socketserver.TCPServer(("", 8000), h).serve_forever()'
