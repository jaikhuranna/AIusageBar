#!/usr/bin/env bash
# One-time Cloudflare Tunnel setup for the AIusageBar bridge.
#
#   scripts/setup-tunnel.sh usage.example.com
#
# The hostname must be on a domain in your Cloudflare account. Safe to re-run.
# Uses its own config file and a user-level systemd service, so it doesn't touch
# any other cloudflared setup on this machine.
set -euo pipefail

HOST="${1:?usage: $0 <hostname on your Cloudflare domain, e.g. usage.example.com>}"
NAME="${TUNNEL_NAME:-aiusage}"
PORT="${PORT:-8765}"
CF="$HOME/.cloudflared"
CONF="$CF/aiusage.yml"
SERVER="$(cd "$(dirname "$0")/.." && pwd)/aiusage-server"

if ! command -v cloudflared >/dev/null; then
    echo "cloudflared isn't installed. Install it, then re-run this script:"
    echo "  curl -fsSL -o /tmp/cloudflared.deb https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb"
    echo "  sudo dpkg -i /tmp/cloudflared.deb"
    exit 1
fi

# 1. Log in (opens a browser; pick the domain the hostname lives on).
[ -f "$CF/cert.pem" ] || cloudflared tunnel login

# 2. Create the tunnel once.
tunnel_id() {
    cloudflared tunnel list --name "$NAME" --output json |
        python3 -c 'import json,sys; t=json.load(sys.stdin); print(t[0]["id"] if t else "")'
}
UUID="$(tunnel_id)"
if [ -z "$UUID" ]; then
    cloudflared tunnel create "$NAME"
    UUID="$(tunnel_id)"
fi
echo "tunnel $NAME = $UUID"

# 3. Point the hostname at it.
cloudflared tunnel route dns "$NAME" "$HOST" ||
    echo "(could not add the DNS record; if $HOST already points at this tunnel, that's fine)"

# 4. Only the bridge's public routes cross the tunnel; everything else 404s.
mkdir -p "$CF"
cat > "$CONF" <<EOF
tunnel: $UUID
credentials-file: $CF/$UUID.json
ingress:
  - hostname: $HOST
    path: ^/(usage|events|pair|share|healthz)\$
    service: http://127.0.0.1:$PORT
  - service: http_status:404
EOF
cloudflared tunnel --config "$CONF" ingress validate

# 5. Keep the tunnel running while you're logged in.
UNIT="$HOME/.config/systemd/user/aiusage-tunnel.service"
mkdir -p "$(dirname "$UNIT")"
cat > "$UNIT" <<EOF
[Unit]
Description=Cloudflare Tunnel for the AIusageBar bridge
After=network-online.target

[Service]
ExecStart=$(command -v cloudflared) tunnel --no-autoupdate --config $CONF run $NAME
Restart=on-failure
RestartSec=10

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable --now aiusage-tunnel.service
systemctl --user restart aiusage-tunnel.service

# 6. Run the server independently of the tray/client.
SERVER_UNIT="$HOME/.config/systemd/user/aiusage-server.service"
if [ -f "$SERVER_UNIT" ]; then cp "$SERVER_UNIT" "$SERVER_UNIT.bak"; fi
cat > "$SERVER_UNIT" <<EOF
[Unit]
Description=AIusage standalone Go server
After=network-online.target

[Service]
ExecStart=$SERVER -serve :$PORT -public-url https://$HOST
Environment=PATH=$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
EOF
systemctl --user daemon-reload
systemctl --user enable aiusage-server.service

cat <<EOF

Tunnel configured. Stop any old combined AIusageBar process, then start:
  systemctl --user restart aiusage-server.service

Pair and start the tray client separately:
  $SERVER -serve :$PORT -pair
  ./aiusagebar -server http://127.0.0.1:$PORT -pair <printed-code>
  ./aiusagebar

Check from your phone on mobile data:
  https://$HOST/healthz   should show "auth": "token"
EOF
