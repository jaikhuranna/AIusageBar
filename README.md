# AIusageBar

GNOME top-bar indicator for [Claude Code](https://claude.com/claude-code) usage. Written in Go.

- Tray icon = colored dot (green/orange/red) by how close you are to your
  worst plan limit. Tray text = `5h NN% · 7d NN%`.
- Click menu shows:
  - **Session (5h)** and **Week (7d)** limit utilization and when each resets
    - read straight from Anthropic's own cached usage data in `~/.claude.json`
      (the same numbers Claude Code itself shows you), so the percentages and
      reset times are exact, not estimated.
  - **API-cost equivalent** for today / this session / this week - what the
    same token usage would have cost on pay-as-you-go API pricing, computed
    from the token counts logged in `~/.claude/projects/**/*.jsonl` using
    current per-model Anthropic pricing (updated 2026-06).
- Refreshes every 30s, or on demand via the "Refresh" menu item.
- Can also serve the same numbers over your LAN (`-serve`) as JSON, for other
  devices on the network.

Subscription plans (Pro/Max) don't expose a dollar limit or a metered
balance - only a percentage of your session/weekly allowance. The cost
figure here is a separate, independent estimate of value, not what you're
being billed.

## Build

```sh
go build -o aiusagebar .
```

Needs Go 1.22+, a C compiler (`gcc`), and GTK/AppIndicator dev headers for
the tray (same class of dependency the old GTK version needed):

```sh
sudo apt install build-essential libgtk-3-dev libayatana-appindicator3-dev
```

## Run

```sh
./aiusagebar
```

## Serve usage over the LAN

The same snapshot the tray shows can be served as JSON, so anything else on the
network can read it - that's how the Wear OS companion
([AIusageWear](../AIusageWear)) gets its numbers:

```sh
./aiusagebar -serve :8765              # tray + JSON bridge
./aiusagebar -headless -serve :8765    # bridge only, no tray
./aiusagebar -serve :8765 -token s3cr3t  # require a shared secret
```

On startup the process logs every LAN address it is reachable on, and advertises
itself over mDNS as `_aiusage._tcp` (via `avahi-publish-service`) so a client can
find it without anyone typing an IP address. `-mdns=false` turns that off.

### Endpoints

| Endpoint | What it does |
| --- | --- |
| `GET /usage` | current snapshot. Sends an `ETag`; send it back as `If-None-Match` to get `304` when nothing has changed |
| `GET /events?since=<id>` | alert events (threshold crossings, window resets) newer than `<id>`, plus the new `high_water` mark |
| `POST /pair` `{"code":"123456"}` | redeems a pairing code for a per-device token (plus `public_url`, if set) |
| `GET /healthz` | liveness, no auth, no usage data. Says whether a token is needed |

```json
{
  "schema": 1,
  "five_hour": {"utilization": 53, "remaining": 47, "resets_at": "2026-09-21T18:20:00Z", "resets_in_sec": 16631},
  "seven_day": {"utilization": 41, "remaining": 59, "resets_at": "2026-09-27T00:00:00Z", "resets_in_sec": 469031},
  "cost_today": 251.02, "cost_session": 25.06, "cost_week": 351.44,
  "generated_at": "2026-09-21T13:43:29Z", "host": "pop-os"
}
```

The `ETag` deliberately ignores `generated_at` and `resets_in_sec`: they change
on every poll but carry no news, and a `304` over a Bluetooth proxy is nearly
free next to a full body. A `304` means "your copy is still current *now*", so
treat the fetch time, not `generated_at`, as the freshness clock.

### Alerts

The bridge is the only thing that decides an alert; clients just render what
they're told. Crossing 80%, 95% or 100% (exhausted) on either window raises an
event, and so does a window resetting. One crossing produces exactly one event: it re-arms only
when the window rolls over or utilization falls `-hysteresis` points back below
the line, and the arming state is persisted, so restarting the bridge doesn't
replay history.

```sh
./aiusagebar -serve :8765 -thresholds 80,95,100 -hysteresis 5
```

Cost is deliberately not alertable - it's an independent estimate of value, not
a bill, and a "you've spent $X" notification would imply a charge that isn't
happening.

### Pairing a device

Typing a 32-character token on a watch is not a plan. Instead:

```sh
./aiusagebar -pair          # asks the running bridge for a code, prints it
```

(or click **Pair a device…** in the tray menu). The bridge prints a 6-digit
code, good for one use and two minutes; the device posts it to `/pair` and gets
back its own token. Ten wrong codes in a row burn every pending code, so the
6-digit space can't be walked.

Codes are minted only over a Unix socket (`$XDG_RUNTIME_DIR/aiusagebar.sock`,
mode 0600), never over HTTP. A tunnel or reverse proxy on this machine delivers
internet traffic from `127.0.0.1`, so a "loopback only" HTTP endpoint would let
anyone who can reach the tunnel mint a code. The socket is reachable only by
your own user. **Pairing one device closes the bridge**: once any device is
paired, unauthenticated requests get a `401`, and each device's token can be
revoked on its own by deleting it from the state file.

State - device tokens, the event ring, alert arming - lives in
`$XDG_STATE_HOME/aiusagebar/state.json` (mode 0600), usually
`~/.local/state/aiusagebar/state.json`.

The bridge itself is plain HTTP with no transport security. On the LAN, keep it
to a trusted network. To reach it from anywhere, put TLS in front with a tunnel:

## Reach it from anywhere (Cloudflare Tunnel)

A [Cloudflare named tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/)
gives the bridge a stable `https://` hostname without opening a port on your
router. Cloudflare terminates TLS; `cloudflared` on this machine forwards to the
bridge on loopback. It needs a Cloudflare account and a domain on it. The free
"quick tunnel" (`trycloudflare.com`) won't do: its hostname changes every
restart, and the watch needs one that stays put.

The quick way, which does everything below (login, tunnel, DNS, a config that
only forwards the bridge's routes, a user-level systemd service, and autostart
flags):

```sh
scripts/setup-tunnel.sh usage.example.com
```

Or by hand (`usage.example.com` is your hostname):

```sh
cloudflared tunnel login                      # opens a browser, picks the domain
cloudflared tunnel create aiusage             # prints the tunnel UUID
cloudflared tunnel route dns aiusage usage.example.com
```

`~/.cloudflared/config.yml`:

```yaml
tunnel: <UUID>
credentials-file: /home/<you>/.cloudflared/<UUID>.json
ingress:
  # Only the bridge's public routes cross the tunnel; everything else 404s.
  - hostname: usage.example.com
    path: ^/(usage|events|pair|healthz)$
    service: http://127.0.0.1:8765
  - service: http_status:404
```

Then run both:

```sh
cloudflared tunnel run aiusage
./aiusagebar -serve :8765 -public-url https://usage.example.com
```

`-public-url` does two things:

- **Every request needs a credential from the start.** Without it, an unpaired
  bridge answers anyone; with it, the internet can reach the port, so `/usage`
  and `/events` return `401` until a device has paired.
- **It's handed to devices when they pair.** A watch pairs once on home Wi-Fi,
  where it finds the bridge over mDNS, and the `/pair` response tells it the
  public URL to use from then on. Nobody types a hostname on a watch.

Send the token as `Authorization: Bearer …`, not `?token=`: query strings end up
in proxy logs. Keep `-serve :8765` (all interfaces) if you want LAN pairing; bind
`127.0.0.1:8765` instead to make the tunnel the only way in, at the cost of
pairing through the public URL.

`cloudflared service install` runs the tunnel at boot (see Cloudflare's docs; it
reads `/etc/cloudflared/config.yml`).

## Start on login

```sh
cp aiusagebar.desktop ~/.config/autostart/
```

(Edit the `Exec=` path in `aiusagebar.desktop` first if you build/install the
binary somewhere other than this directory.)
