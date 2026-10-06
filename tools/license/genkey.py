#!/usr/bin/env python3
"""生成签发卡密用的 ECDSA P-256 密钥对。

私钥绝不外传、绝不提交到仓库。公钥会以 base64 DER 打印出来，填进
LicenseManager.PUBLIC_KEY_BASE64。

    python genkey.py

不依赖 openssl —— 用 cryptography 库直接生成。
"""
import base64
import os
import sys

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec
except ImportError:
    sys.exit("需要 cryptography：pip install cryptography")

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.environ.get("PAPERSU_KEY_DIR", os.path.join(HERE, "keys"))


def main():
    os.makedirs(OUT, exist_ok=True)
    priv_path = os.path.join(OUT, "private_key.pem")
    pub_path = os.path.join(OUT, "public_key.pem")

    if os.path.exists(priv_path):
        print("私钥已存在，沿用：", priv_path)
        with open(priv_path, "rb") as fh:
            key = serialization.load_pem_private_key(fh.read(), password=None)
    else:
        key = ec.generate_private_key(ec.SECP256R1())
        with open(priv_path, "wb") as fh:
            fh.write(key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.TraditionalOpenSSL,
                serialization.NoEncryption(),
            ))
        print("已写入私钥：", priv_path)

    public = key.public_key()
    with open(pub_path, "wb") as fh:
        fh.write(public.public_bytes(
            serialization.Encoding.PEM,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        ))
    print("已写入公钥：", pub_path)

    der = public.public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    b64 = base64.b64encode(der).decode()

    with open(os.path.join(OUT, "public_key_base64.txt"), "w", encoding="ascii") as fh:
        fh.write(b64 + "\n")

    print()
    print("把下面这一行填进 manager/.../license/LicenseManager.kt 的 PUBLIC_KEY_BASE64：")
    print()
    print(b64)
    print()
    print("（同时存了一份在 %s）" % os.path.join(OUT, "public_key_base64.txt"))


if __name__ == "__main__":
    main()