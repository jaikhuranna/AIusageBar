#!/usr/bin/env python3
"""Exercise separate server/client binaries with isolated state and fake sources."""
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

server, client = (str(Path(p).resolve()) for p in sys.argv[1:])
with tempfile.TemporaryDirectory(prefix="aiusage-split-") as tmp:
    root = Path(tmp)
    env = dict(os.environ, HOME=tmp, XDG_STATE_HOME=tmp + "/state",
               XDG_RUNTIME_DIR=tmp + "/runtime", XDG_CONFIG_HOME=tmp + "/config")
    env.pop("DISPLAY", None)
    env.pop("WAYLAND_DISPLAY", None)
    (root / "runtime").mkdir(mode=0o700)
    reset = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 3600))
    (root / ".claude.json").write_text(json.dumps({"cachedUsageUtilization": {
        "fetchedAtMs": int(time.time() * 1000), "utilization": {
            "five_hour": {"utilization": 21, "resets_at": reset},
            "seven_day": {"utilization": 42, "resets_at": reset}}}}))
    fake = root / "fake-codex"
    fake.write_text('''#!/usr/bin/env python3
import json, sys, time
for line in sys.stdin:
    msg = json.loads(line)
    if "id" not in msg:
        continue
    result = {}
    if msg["method"] == "account/rateLimits/read":
        result = {"rateLimits": {"limitId": "codex", "primary": {
            "usedPercent": 63, "windowDurationMins": 60,
            "resetsAt": int(time.time()) + 3600}, "secondary": {
            "usedPercent": 84, "windowDurationMins": 1440,
            "resetsAt": int(time.time()) + 86400}}}
    print(json.dumps({"id": msg["id"], "result": result}), flush=True)
''')
    fake.chmod(0o700)
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    url = f"http://127.0.0.1:{port}"
    args = [server, "-serve", f"127.0.0.1:{port}", "-mdns=false",
            "-refresh-min-age", "0", "-codex", str(fake),
            "-public-url", "https://usage.example.com"]

    def run(*args):
        return subprocess.check_output(args, env=env, text=True, timeout=10)

    def ready(proc):
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            assert proc.poll() is None, "server exited before ready"
            try:
                with urllib.request.urlopen(url + "/healthz", timeout=1) as r:
                    assert json.load(r)["auth"] == "token"
                    return
            except urllib.error.URLError:
                time.sleep(0.05)
        raise AssertionError("server did not become ready")

    def check(provider, used):
        deadline = time.monotonic() + 10
        while True:
            data = json.loads(run(client, "-provider", provider, "-check"))
            if data.get("five_hour") is not None or time.monotonic() >= deadline:
                break
            time.sleep(0.05)
        assert data["schema"] == 1 and data["provider"] == provider
        assert data["five_hour"]["utilization"] == used, data
        if provider == "codex":
            assert data["five_hour"]["window_minutes"] == 60
            assert data["seven_day"]["window_minutes"] == 1440
            assert not data["cost_available"]

    # Never call a real provider CLI; subprocess logs remain in this isolated dir.
    with (root / "server.log").open("w") as log:
        proc = subprocess.Popen(args, env=env, stdout=log, stderr=log)
        try:
            ready(proc)
            try:
                urllib.request.urlopen(url + "/usage?provider=codex")
                raise AssertionError("anonymous usage accepted")
            except urllib.error.HTTPError as err:
                assert err.code == 401
            code = re.search(r"Pairing code: (\d{6})", run(server, "-pair")).group(1)
            run(client, "-server", url, "-pair", code)
            config = root / "config/aiusagebar/client.json"
            assert config.stat().st_mode & 0o777 == 0o600
            original = json.loads(config.read_text())
            check("claude", 21)
            check("codex", 63)
            # Both clients exited; the independent server is still healthy.
            ready(proc)
            proc.terminate()
            assert proc.wait(timeout=10) == 0
            assert not (root / "runtime/aiusagebar.sock").exists()
            # Restart loads existing device tokens, without a new pairing.
            proc = subprocess.Popen(args, env=env, stdout=log, stderr=log)
            ready(proc)
            check("codex", 63)
            check("claude", 21)
            assert json.loads(config.read_text())["token"] == original["token"]
        finally:
            if proc.poll() is None:
                proc.terminate()
                proc.wait(timeout=10)
    print("PASS: independent server, authenticated pairing, both providers, headless client checks, restart persistence")
