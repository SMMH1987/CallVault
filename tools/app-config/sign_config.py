#!/usr/bin/env python3
"""Sign the app config so phones accept it.

Writes <config>.sig next to the file: base64 of an ECDSA P-256 / SHA-256 signature over the file's
exact bytes. Upload both files together; a phone ignores a config whose signature does not match.
Re-sign after every edit, even a one-character one.

    python3 tools/app-config/sign_config.py out/config.json --key config-signing-key.pem
    python3 tools/app-config/sign_config.py --new-key config-signing-key.pem   # once, prints the public key

The private key never goes into the app or the repository. The public key printed by --new-key is
the build's -PconfigPublicKey (the default in app/build.gradle.kts is the company key).
Needs: pip install cryptography
"""
import argparse
import base64
import os
import sys

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec


def public_b64(key):
    der = key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return base64.b64encode(der).decode()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("config", nargs="?")
    p.add_argument("--key", default=os.environ.get("CONFIG_SIGNING_KEY"), help="private key PEM")
    p.add_argument("--new-key", metavar="PEM", help="create a new key pair and print the public key")
    a = p.parse_args()

    if a.new_key:
        if os.path.exists(a.new_key):
            sys.exit(f"{a.new_key} exists; refusing to overwrite a signing key")
        key = ec.generate_private_key(ec.SECP256R1())
        with open(a.new_key, "wb") as f:
            f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                      serialization.NoEncryption()))
        print(public_b64(key))
        return

    if not a.config or not a.key:
        sys.exit("usage: sign_config.py <config.json> --key <private.pem>")
    with open(a.key, "rb") as f:
        key = serialization.load_pem_private_key(f.read(), None)
    with open(a.config, "rb") as f:
        data = f.read()
    signature = base64.b64encode(key.sign(data, ec.ECDSA(hashes.SHA256()))).decode()
    with open(a.config + ".sig", "w") as f:
        f.write(signature + "\n")
    print(f"{a.config}.sig written (public key {public_b64(key)[:24]}...)")


if __name__ == "__main__":
    main()
