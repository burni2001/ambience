#!/usr/bin/env python3
"""Koppelt mit der Hue-Bridge und legt den Schlüssel in ../hue.env ab.

Aufruf: ./huesync/pair.py <Bridge-IP> [Wartezeit in Sekunden]
Fragt alle 3 s bei der Bridge an, bis die Link-Taste gedrückt wurde.
Die Schlüssel werden nie ausgegeben, nur in hue.env geschrieben (Rechte 600).
"""
import json, os, ssl, sys, time, urllib.request

if len(sys.argv) < 2:
    sys.exit("Aufruf: ./pair.py <Bridge-IP> [Wartezeit in Sekunden]")
ip = sys.argv[1]
wait = int(sys.argv[2]) if len(sys.argv) > 2 else 900
env = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "hue.env")
ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE  # Bridge hat ein Zertifikat der Signify-eigenen CA
body = json.dumps({"devicetype": "ambilight-sync#tv", "generateclientkey": True}).encode()
end = time.time() + wait
while time.time() < end:
    try:
        req = urllib.request.Request("https://%s/api" % ip, data=body, method="POST")
        res = json.load(urllib.request.urlopen(req, timeout=5, context=ctx))
        if res and "success" in res[0]:
            s = res[0]["success"]
            fd = os.open(env, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w") as f:
                f.write("HUE_IP=%s\nHUE_KEY=%s\nHUE_CLIENTKEY=%s\n" % (ip, s["username"], s["clientkey"]))
            print("Gekoppelt. Schlüssel liegen in hue.env.")
            sys.exit(0)
        err = res[0].get("error", {}) if res else {}
        if err.get("type") != 101:
            print("Unerwartete Antwort: Typ %s, %s" % (err.get("type"), err.get("description")))
    except Exception as e:
        print("Fehler:", type(e).__name__, e)
    time.sleep(3)
print("Zeit abgelaufen, Link-Taste wurde nicht gedrückt.")
sys.exit(1)
