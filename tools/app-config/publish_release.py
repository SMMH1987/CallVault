#!/usr/bin/env python3
"""Put a new APK into the app config.

Copies the APK into an output folder (what the server serves at /app/), and writes config.json there
with the version, size and SHA-256 filled in. Every other field of the existing config is kept.

    python3 tools/app-config/publish_release.py app-debug.apk \
        --config out/config.json --out out \
        --base-url https://avanegar.co-ir.com:8440/app \
        --changelog "رفع مشکل ..." [--critical] [--min-supported 20470] [--block 20465]

The version code and name are read with aapt2 (Android build-tools) when it is on PATH, otherwise
pass --version-code and --version-name.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys


def badging(apk):
    for tool in ("aapt2", "aapt"):
        exe = shutil.which(tool)
        if not exe:
            continue
        out = subprocess.run([exe, "dump", "badging", apk], capture_output=True, text=True).stdout
        m = re.search(r"versionCode='(\d+)'.*?versionName='([^']*)'", out)
        if m:
            return int(m.group(1)), m.group(2)
    return None, None


def main():
    p = argparse.ArgumentParser()
    p.add_argument("apk")
    p.add_argument("--config", help="existing config.json to update (default: the example)")
    p.add_argument("--out", required=True, help="folder the server serves as /app/")
    p.add_argument("--base-url", required=True, help="public https address of that folder")
    p.add_argument("--version-code", type=int)
    p.add_argument("--version-name")
    p.add_argument("--changelog", default="")
    p.add_argument("--critical", action="store_true", help="mark this version as essential")
    p.add_argument("--min-supported", type=int, help="block every version below this code")
    p.add_argument("--block", type=int, action="append", default=[], help="block one version code")
    a = p.parse_args()

    code, name = badging(a.apk)
    code = a.version_code or code
    name = a.version_name or name
    if not code or not name:
        sys.exit("Could not read the version; pass --version-code and --version-name")
    if not a.base_url.startswith("https://"):
        sys.exit("--base-url must be https")

    src = a.config or os.path.join(os.path.dirname(__file__), "app-config.example.json")
    with open(src, encoding="utf-8") as f:
        config = json.load(f)

    os.makedirs(a.out, exist_ok=True)
    file_name = f"CallVault-{name}.apk"
    dest = os.path.join(a.out, file_name)
    shutil.copyfile(a.apk, dest)
    digest = hashlib.sha256(open(dest, "rb").read()).hexdigest()

    base = a.base_url.rstrip("/")
    update = config.setdefault("update", {})
    update.update({
        "version_code": code,
        "version_name": name,
        "apk_url": f"{base}/{file_name}",
        "apk_size": os.path.getsize(dest),
        "apk_sha256": digest,
        "critical": a.critical,
    })
    if a.changelog:
        update["changelog"] = a.changelog
    versions = config.setdefault("versions", {})
    if a.min_supported is not None:
        versions["min_supported_code"] = a.min_supported
    if a.block:
        versions["blocked_codes"] = sorted(set(versions.get("blocked_codes", [])) | set(a.block))

    out_config = os.path.join(a.out, "config.json")
    with open(out_config, "w", encoding="utf-8") as f:
        json.dump(config, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"{file_name}  code={code}  sha256={digest}\n{out_config} written")


if __name__ == "__main__":
    main()
