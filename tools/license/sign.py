#!/usr/bin/env python3
"""Sign a card key.

    python sign.py --user alice --tier pro --exp 2027-01-01

Prints the card key, which is  base64url(payload) "." base64url(signature).
The app checks the signature offline with the matching public key, so issuing a key needs no
server and no network: run this on your own machine.
"""
import argparse
import base64
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
KEY_DIR = os.environ.get("PAPERSU_KEY_DIR", os.path.join(HERE, "keys"))


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def openssl_path():
    for candidate in (
        r"D:\Program Files\Git\usr\bin\openssl.exe",
        r"D:\Program Files\Git\mingw64\bin\openssl.exe",
        "openssl",
    ):
        try:
            subprocess.run([candidate, "version"], capture_output=True, check=True)
            return candidate
        except Exception:
            continue
    sys.exit("openssl not found; install Git for Windows or OpenSSL")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--user", default="", help="who the key is for, stored in the payload")
    ap.add_argument("--tier", default="pro", help="pro / vip / premium")
    ap.add_argument("--exp", default="", help="expiry as yyyy-MM-dd; empty means never")
    ap.add_argument("--out", default="", help="write the key to this file as well")
    args = ap.parse_args()

    priv = os.path.join(KEY_DIR, "private_key.pem")
    if not os.path.exists(priv):
        sys.exit("no private key at %s - run genkey.py first" % priv)

    payload = json.dumps(
        {"u": args.user, "t": args.tier, "exp": args.exp},
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")

    ossl = openssl_path()
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        pfile = os.path.join(tmp, "payload.bin")
        sfile = os.path.join(tmp, "sig.bin")
        with open(pfile, "wb") as fh:
            fh.write(payload)
        subprocess.run([ossl, "dgst", "-sha256", "-sign", priv, "-out", sfile, pfile], check=True)
        with open(sfile, "rb") as fh:
            signature = fh.read()

    card = b64url(payload) + "." + b64url(signature)
    print(card)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(card + "\n")
        print("\nwritten to", args.out, file=sys.stderr)


if __name__ == "__main__":
    main()