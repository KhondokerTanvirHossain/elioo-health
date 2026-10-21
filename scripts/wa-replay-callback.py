#!/usr/bin/env python3
"""Replay one signed WhatsApp text callback twice, to try F9a (the same message twice is answered once) from the server.

A phone cannot make Meta re-deliver a webhook. This builds the callback Meta would send for the text "বিস্তারিত" from
the number given as --number, with a fixed message id of Meta's shape (base64 of the number and an opaque tail), signs
it with BAYMAX_WA_APP_SECRET exactly as Meta does (X-Hub-Signature-256), and POSTs the identical bytes twice.

It impersonates that number to the app, so it refuses unless the number is all of:
  - given explicitly with --number (never taken from the allowlist: once a pilot family's number is first, that would
    forge a message from the family);
  - on BAYMAX_WA_ALLOWLIST (the app would not answer anyone else);
  - equal to BAYMAX_WA_TEST_NUMBER, the dedicated test number, from the environment or the env file.
It prints the masked target (****NNNN) and sends nothing without --yes.

What a run does to that number, as a real message would (RUNBOOK, F9a): ONE WhatsApp reply is sent to it; its 24-hour
WhatsApp window is recorded as open (wa_last_inbound, DR-39: a web sign-in code may then be sent to it as free-form
text); an inbound_message row is stored with the text; the detail word may compose a detail message about its latest
paper.

What proves it: two 200s, ONE WhatsApp reply on the test phone, and one "wa inbound duplicate ref=... ignored" line in
`docker logs medscribe-ai`.

Standard library only (runs on the box's python3, 3.9). Never prints the secret, the number, the body or the signature,
except under --dry-run, which prints the request it would send (the signature only with --show-signature: with it,
anyone holding the output can replay the request to the public webhook) and sends nothing.

Usage (on the box):
    BAYMAX_WA_TEST_NUMBER=+880... python3 -I ~/wa-replay-callback.py --number +880... --yes
    python3 -I ~/wa-replay-callback.py --number +880... --dry-run      # prints {path, body}; sends nothing
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
TEST_NUMBER = "BAYMAX_WA_TEST_NUMBER"


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
            if key in wanted or key == TEST_NUMBER:
                found[key] = value
    missing = sorted(k for k in wanted if not found.get(k, "").strip())
    if missing:
        sys.exit("missing or blank in the env file: " + ", ".join(missing))
    return found


def masked(number):
    return "****" + number[-4:] if len(number) >= 4 else "****"


def e164(value, what):
    """+ and 8 to 15 digits, as the allowlist holds numbers."""
    value = value.strip()
    if not value.startswith("+") or not value[1:].isdigit() or not 8 <= len(value) - 1 <= 15:
        sys.exit(what + " is not an E.164 number (+ and 8 to 15 digits)")
    return value


def target(number, env):
    """The number to impersonate, or exit: explicit, allowlisted, and the dedicated test number. Never allowlist[0]."""
    number = e164(number, "--number")
    allowlist = [entry.strip() for entry in env["BAYMAX_WA_ALLOWLIST"].split(",") if entry.strip()]
    if number not in allowlist:
        sys.exit("refusing: --number " + masked(number) + " is not on BAYMAX_WA_ALLOWLIST")
    test_number = (os.environ.get(TEST_NUMBER) or env.get(TEST_NUMBER) or "").strip()
    if not test_number:
        sys.exit("refusing: " + TEST_NUMBER + " is not set (environment or env file); it names the one number this "
                 "script may impersonate")
    if number != e164(test_number, TEST_NUMBER):
        sys.exit("refusing: --number " + masked(number) + " is not " + TEST_NUMBER + " (" + masked(test_number)
                 + "); this script never forges a message from any other number")
    return number[1:]   # as Meta reports a sender: digits only, no plus


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
    parser.add_argument("--number", required=True,
                        help="E.164 number to send as; must be on BAYMAX_WA_ALLOWLIST and equal " + TEST_NUMBER)
    parser.add_argument("--yes", action="store_true", help="really send (without it: print the masked target, send nothing)")
    parser.add_argument("--env", default="~/medscribe.env", help="env file holding BAYMAX_WA_APP_SECRET and BAYMAX_WA_ALLOWLIST")
    parser.add_argument("--url", default="http://localhost:8086", help="the app's base URL")
    parser.add_argument("--dry-run", action="store_true", help="print the request as JSON and send nothing")
    parser.add_argument("--show-signature", action="store_true",
                        help="with --dry-run, include the signature (anyone holding it can replay the request)")
    args = parser.parse_args(argv)

    env = read_env(args.env)
    digits = target(args.number, env)
    now = datetime.datetime.fromtimestamp(time.time(), tz=datetime.timezone.utc)
    body = callback(digits, wamid(digits, now), now)
    body_bytes = body.encode("utf-8")
    signature = sign(body_bytes, env["BAYMAX_WA_APP_SECRET"])

    if args.dry_run:
        request = {"path": WEBHOOK_PATH, "body": body}
        if args.show_signature:
            request["signature"] = signature
        print(json.dumps(request))
        return 0
    print("target " + masked(args.number.strip()))
    if not args.yes:
        print("not sent: re-run with --yes to send this as that number (one reply, an open 24 h window, a stored row)",
              file=sys.stderr)
        return 2
    url = args.url.rstrip("/") + WEBHOOK_PATH
    # the identical signed bytes, twice: what Meta does when it decides our first 200 did not arrive
    print(post(url, body_bytes, signature))
    print(post(url, body_bytes, signature))
    return 0


if __name__ == "__main__":
    sys.exit(main())
