#!/usr/bin/env python3
"""Offline secret scan of HEAD history and optional APK, prints no secret values."""
import argparse
import pathlib
import re
import subprocess
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]

def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args])

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--apk', type=pathlib.Path)
    args = p.parse_args()
    needles = []
    env = ROOT / '.env'
    if env.is_file():
        for line in env.read_text().splitlines():
            key, sep, value = line.removeprefix('export ').partition('=')
            if sep and key.strip() == 'OPENROUTER_API_KEY':
                value = value.strip().strip('"\'')
                if len(value) >= 12:
                    needles.append(value[:12].encode())
    patterns = [re.compile(rb'sk-or-v1-[a-zA-Z0-9_-]{24,}'),
                re.compile(rb'-----BEGIN (?:RSA |EC |ENCRYPTED )?PRIVATE KEY-----')]
    def check(data, name):
        pattern_data = data
        if name.endswith('/data/SecretStoreInstrumentedTest.kt'):
            # Public deterministic encryption fixture, not a credential. Exact exception only.
            fixture = b'sk-or-v1-' + b'0123456789abcdef' * 2
            pattern_data = data.replace(fixture, b'<synthetic-encryption-fixture>')
        if name.endswith('/testing/FakeOpenRouterServer.kt'):
            fixture = b'sk-or-v1-' + b'FAKEFAKEFAKE' + b'0' * 52
            pattern_data = data.replace(fixture, b'<synthetic-http-fixture>')
        if any(n in data for n in needles) or any(p.search(pattern_data) for p in patterns):
            raise SystemExit('Secret-like content detected in ' + name)
    files = git('ls-tree', '-r', '--name-only', 'HEAD').decode().splitlines()
    prohibited = [f for f in files if pathlib.PurePosixPath(f).name in {'.env', 'local.properties', 'keystore.properties'}
                  or f.endswith(('.p12', '.jks', '.keystore', '.apk', '.db'))]
    if prohibited:
        raise SystemExit('Private/build files tracked: ' + ', '.join(prohibited))
    blobs = 0
    for line in git('rev-list', '--objects', 'HEAD').decode().splitlines():
        parts = line.split(' ', 1)
        oid = parts[0]
        name = parts[1] if len(parts) > 1 else oid
        if git('cat-file', '-t', oid).strip() == b'blob':
            check(git('cat-file', 'blob', oid), name)
            blobs += 1
    count = 0
    if args.apk:
        with zipfile.ZipFile(args.apk) as z:
            for info in z.infolist():
                check(z.read(info), info.filename)
                count += 1
            dex = b''.join(z.read(i) for i in z.namelist() if re.fullmatch(r'classes\d*\.dex', i))
            for forbidden in [b'Lpro/perfectproduct/cramin/testing/', b'Lpro/perfectproduct/cramin/probe/', b'Lpro/perfectproduct/cramin/live/']:
                if forbidden in dex:
                    raise SystemExit('Test-only app code detected in release DEX')
    print(f'PASS: {len(files)} tracked files; {blobs} reachable blobs; {count} APK entries; no checked secret/test-code matches')

if __name__ == '__main__':
    main()
