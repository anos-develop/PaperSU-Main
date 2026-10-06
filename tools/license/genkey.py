#!/usr/bin/env python3
"""Generate the ECDSA P-256 key pair used to sign card keys.

The private key never leaves this machine and must never be committed. The public key is printed
as base64 DER, which is what LicenseManager.PUBLIC_KEY_BASE64 wants.
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.environ.get("PAPERSU_KEY_DIR", os.path.join(HERE, "keys"))


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
    ossl = openssl_path()
    os.makedirs(OUT, exist_ok=True)
    priv = os.path.join(OUT, "private_key.pem")
    if os.path.exists(priv):
        print("private key already present at", priv)
    else:
        subprocess.run([ossl, "ecparam", "-name", "prime256v1", "-genkey", "-noout",
                        "-out", priv], check=True)
        print("wrote", priv)
    der = os.path.join(OUT, "public_key.der")
    subprocess.run([ossl, "ec", "-in", priv, "-pubout", "-outform", "DER", "-out", der],
                   check=True)
    import base64
    with open(der, "rb") as fh:
        b64 = base64.b64encode(fh.read()).decode()
    os.remove(der)
    print()
    print("Paste this into LicenseManager.PUBLIC_KEY_BASE64:")
    print()
    print(b64)
    print()


if __name__ == "__main__":
    main()