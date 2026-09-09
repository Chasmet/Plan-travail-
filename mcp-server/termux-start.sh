#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"

if ! command -v node >/dev/null 2>&1; then
  pkg update -y
  pkg install -y nodejs-lts
fi

if [ ! -d node_modules ]; then
  npm install
fi

export PORT=${PORT:-3000}
echo "Plan Travail Orsay MCP sur http://127.0.0.1:$PORT"
echo "Endpoint SSE local : http://127.0.0.1:$PORT/sse"
node server.js
