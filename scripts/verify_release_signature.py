#!/usr/bin/env python3
"""Сверяет SHA-256 отпечаток сертификата release-APK с release-signing/cert-sha256.txt (SPEC §12.3).

Использование: scripts/verify_release_signature.py <apk> [--expected release-signing/cert-sha256.txt]
Печатает обе стороны и завершается с кодом 1 при несовпадении. Заглушка REPLACE_WITH_REAL_FINGERPRINT
в файле отпечатка валит проверку осознанно: значит, keystore ещё не создан scripts/setup-secrets.sh.
"""
import argparse
import glob
import os
import re
import subprocess
import sys


def find_apksigner() -> str:
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(env)
        if root:
            candidates = sorted(glob.glob(os.path.join(root, "build-tools", "*", "apksigner")), reverse=True)
            if candidates:
                return candidates[0]
    from shutil import which
    found = which("apksigner")
    if found:
        return found
    sys.exit("apksigner не найден: задайте ANDROID_HOME с build-tools")


def normalize(fp: str) -> str:
    return re.sub(r"[^0-9a-f]", "", fp.strip().lower())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--expected", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "release-signing", "cert-sha256.txt"))
    args = parser.parse_args()

    with open(args.expected, encoding="utf-8") as f:
        expected_raw = f.read().strip()
    if "REPLACE_WITH_REAL_FINGERPRINT" in expected_raw:
        print(f"expected: {expected_raw}")
        print("Отпечаток не задан: запустите scripts/setup-secrets.sh и создайте keystore (release-signing/cert-sha256.txt).")
        return 1
    expected = normalize(expected_raw)
    if len(expected) != 64:
        print(f"expected: {expected_raw}")
        print("Файл отпечатка не содержит SHA-256 (64 hex-символа).")
        return 1

    out = subprocess.run([find_apksigner(), "verify", "--print-certs", "-v", args.apk], capture_output=True, text=True)
    if out.returncode != 0:
        print(out.stdout)
        print(out.stderr)
        print("apksigner verify завершился с ошибкой — APK не подписан или повреждён.")
        return 1
    match = re.search(r"SHA-256 digest:\s*([0-9a-fA-F]{64})", out.stdout)
    if not match:
        print(out.stdout)
        print("В выводе apksigner нет SHA-256 digest сертификата.")
        return 1
    actual = match.group(1).lower()
    print(f"expected: {expected}")
    print(f"actual:   {actual}")
    if actual != expected:
        print("НЕСОВПАДЕНИЕ: APK подписан другим ключом.")
        return 1
    print("OK: отпечаток совпадает.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
