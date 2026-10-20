#!/usr/bin/env python3
"""Replay one signed WhatsApp text callback twice, to try F9a (the same message twice is answered once) from the server.

A phone cannot make Meta re-deliver a webhook. This builds the callback Meta would send for the text "বিস্তারিত" from the
FIRST number on BAYMAX_WA_ALLOWLIST, with a fixed message id of Meta's shape (base64 of the number and an opaque tail),
signs it with BAYMAX_WA_APP_SECRET exactly as Meta does (X-Hub-Signature-256), and POSTs the identical bytes twice.

What proves it: two 200s here, ONE WhatsApp reply on the allowlisted phone, and one
"wa inbound duplicate ref=... ignored" line in `docker logs medscribe-ai`.

A test aid for the allowlisted number only: the app refuses to message any other number. Standard library only
(runs on the box's python3, 3.9). Never prints the secret, the number or the body, except under --dry-run, which prints
the request it would send and sends nothing.

Usage (on the box):
    python3 -I ~/wa-replay-callback.py                 # reads ~/medscribe.env, posts to http://localhost:8086
    python3 -I ~/wa-replay-callback.py --dry-run       # prints {path, body, signature}; sends nothing
"""
import argparse
import base64
import datetime
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request

WEBHOOK_PATH = "/api/v1/baymax/wa/webhook"
TEXT = "বিস্তারিত"


def read_env(path):
    """Exact-key KEY=value lines, as docker --env-file reads them: no quotes stripped, no inline comments."""
    wanted = {"BAYMAX_WA_APP_SECRET", "BAYMAX_WA_ALLOWLIST"}
    found = {}
    with open(os.path.expanduser(path), encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.lstrip().startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            if key in wanted:
                found[key] = value
    missing = sorted(k for k in wanted if not found.get(k, "").strip())
    if missing:
        sys.exit("missing or blank in the env file: " + ", ".join(missing))
    return found


def first_number(allowlist):
    """The first E.164 entry, as Meta reports a sender: digits only, no plus."""
    entry = allowlist.split(",")[0].strip()
    digits = entry[1:] if entry.startswith("+") else entry
    if not digits.isdigit() or not 8 <= len(digits) <= 15:
        sys.exit("the first BAYMAX_WA_ALLOWLIST entry is not an E.164 number")
    return digits


def wamid(digits, now):
    """Meta's shape: base64 of 0x1c 0x18 len, the digits, 0x15 0x02 0x00 0x12 0x18 0x14, then a 20-byte opaque tail."""
    tail = ("REPLAY" + now.strftime("%Y%m%d%H%M") + "00").encode("ascii")
    raw = bytes([0x1C, 0x18, len(digits)]) + digits.encode("ascii") + bytes([0x15, 0x02, 0x00, 0x12, 0x18, len(tail)]) + tail
    return "wamid." + base64.b64encode(raw).decode("ascii")


def callback(digits, message_id, now):
    return json.dumps({
        "object": "whatsapp_business_account",
        "entry": [{"changes": [{"field": "messages", "value": {
            "messaging_product": "whatsapp",
            "messages": [{"from": digits, "id": message_id, "timestamp": str(int(now.timestamp())),
                          "type": "text", "text": {"body": TEXT}}]}}]}],
    }, ensure_ascii=False, separators=(",", ":"))


def sign(body_bytes, secret):
    return "sha256=" + hmac.new(secret.encode("utf-8"), body_bytes, hashlib.sha256).hexdigest()


def post(url, body_bytes, signature):
    request = urllib.request.Request(url, data=body_bytes, method="POST", headers={
        "Content-Type": "application/json", "X-Hub-Signature-256": signature})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status
    except urllib.error.HTTPError as e:
        return e.code


def main(argv=None):
    parser = argparse.ArgumentParser(description="Replay one signed WhatsApp callback twice (F9a).")
    parser.add_argument("--env", default="~/medscribe.env", help="env file holding BAYMAX_WA_APP_SECRET and BAYMAX_WA_ALLOWLIST")
    parser.add_argument("--url", default="http://localhost:8086", help="the app's base URL")
    parser.add_argument("--dry-run", action="store_true", help="print the request as JSON and send nothing")
    args = parser.parse_args(argv)

    env = read_env(args.env)
    digits = first_number(env["BAYMAX_WA_ALLOWLIST"])
    now = datetime.datetime.fromtimestamp(time.time(), tz=datetime.timezone.utc)
    body = callback(digits, wamid(digits, now), now)
    body_bytes = body.encode("utf-8")
    signature = sign(body_bytes, env["BAYMAX_WA_APP_SECRET"])

    if args.dry_run:
        print(json.dumps({"path": WEBHOOK_PATH, "body": body, "signature": signature}))
        return 0
    url = args.url.rstrip("/") + WEBHOOK_PATH
    # the identical signed bytes, twice: what Meta does when it decides our first 200 did not arrive
    print(post(url, body_bytes, signature))
    print(post(url, body_bytes, signature))
    return 0


if __name__ == "__main__":
    sys.exit(main())
