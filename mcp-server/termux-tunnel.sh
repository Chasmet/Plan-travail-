#!/data/data/com.termux/files/usr/bin/bash
set -e

if ! command -v cloudflared >/dev/null 2>&1; then
  pkg update -y
  pkg install -y cloudflared
fi

PORT=${PORT:-3000}
echo "Ouverture d'un tunnel HTTPS vers le MCP local sur le port $PORT"
echo "Copie ensuite l'URL https://...trycloudflare.com/sse dans ChatGPT"
cloudflared tunnel --url http://127.0.0.1:$PORT
