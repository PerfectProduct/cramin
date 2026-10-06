#!/usr/bin/env python3
"""Package exact committed Cramin source and checksum-pinned upstream sources; no publication."""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]

def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args]).decode().strip()

def sha(path):
    return hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--output', required=True)
    p.add_argument('--cache', type=pathlib.Path)
    args = p.parse_args()
    if git('status', '--porcelain'):
        raise SystemExit('Source kit requires a clean committed working tree')
    subprocess.run(['python3', str(ROOT / 'scripts/audit-release-inputs.py')], check=True)
    head = git('rev-parse', 'HEAD')
    code = int(git('rev-list', '--count', 'HEAD'))
    target = pathlib.Path(args.output).resolve()
    stage = target / 'source-kit'
    stage.mkdir(parents=True, exist_ok=False)
    records = json.loads((ROOT / 'config/source-kit/dependency-sources.json').read_text())
    upstream = json.loads((ROOT / 'config/source-kit/upstream.json').read_text())
    upstream.append(json.loads((ROOT / 'config/source-kit/r8.json').read_text()))
    desugar = json.loads((ROOT / 'config/source-kit/desugar-build.json').read_text())
    inventory = json.loads((ROOT / 'app/src/main/assets/legal/inventory.json').read_text())
    for component in desugar['components']:
        item = next(r for r in inventory if ':'.join([r['group'], r['module'], r['version']]) == component['coordinate'])
        if item['artifactSha256'] != component['sha256']:
            raise SystemExit('Desugar review does not match resolved inventory')
    if not any(r.get('commit') == desugar['r8Commit'] for r in upstream):
        raise SystemExit('Desugar R8 source pin mismatch')
    if not any(desugar['libraryCommit'] in r['file'] for r in upstream):
        raise SystemExit('Desugar library source pin mismatch')
    downloads = [d for r in records for d in r['downloads'] if 'sha256' in d]
    downloads += [dict(r, file='upstream/' + r['file']) for r in upstream]
    for d in downloads:
        dest = stage / d['file']
        dest.parent.mkdir(parents=True, exist_ok=True)
        cached = args.cache / d['file'] if args.cache else None
        # Some upstream archive endpoints regenerate tar mtimes for the same commit.
        # Frozen preferred sources retain the reviewed byte hash; verification stays strict.
        frozen = ROOT / 'release-sources' / d['file']
        if cached and cached.is_file():
            shutil.copyfile(cached, dest)
        elif frozen.is_file():
            shutil.copyfile(frozen, dest)
        else:
            urllib.request.urlretrieve(d['url'], dest)
        if sha(dest) != d['sha256']:
            raise SystemExit('Source checksum mismatch: ' + d['file'])
    shutil.copytree(ROOT / 'config/source-kit', stage / 'manifests')
    shutil.copytree(ROOT / 'app/src/main/assets/legal', stage / 'legal')
    support = stage / 'build-support/scripts'
    support.mkdir(parents=True)
    shutil.copyfile(ROOT / 'scripts/rebuild-desugar.py', support / 'rebuild-desugar.py')
    shutil.copytree(ROOT / 'scripts/desugar', support / 'desugar')
    shutil.copytree(ROOT / 'docs/release/desugar-evidence', stage / 'desugar-evidence')
    for f in ['LICENSE', 'DISTRIBUTION-LICENSE', 'docs/SOURCE-BUILD.md', 'docs/DESUGAR-REVIEW.md', 'docs/DISTRIBUTION.md', 'docs/THIRD_PARTY_NOTICES.md', 'docs/COMPONENT-LICENSES.md', 'NOTICE']:
        shutil.copyfile(ROOT / f, stage / pathlib.Path(f).name)
    # Only HEAD's reachable history; no remotes, reflog, local settings or private working files.
    bundle = stage / f'cramin-{head}.bundle'
    subprocess.run(['git', '-C', str(ROOT), 'bundle', 'create', str(bundle), 'HEAD'], check=True)
    subprocess.run(['git', '-C', str(ROOT), 'bundle', 'verify', str(bundle)], check=True)
    subprocess.run(['git', '-C', str(ROOT), 'archive', '--format=tar.gz', '-o', str(stage / f'cramin-{head}.tar.gz'), 'HEAD'], check=True)
    metadata = dict(commit=head, versionCode=code, versionName=f'0.1.{code}',
                    distribution='GPL-3.0-or-later combined APK; Cramin own sources retain MIT; dependency terms retained',
                    sourceLimitations='See SOURCE-BUILD.md: entire dependency graph and signed APK byte reproducibility are not claimed',
                    desugarReview=dict(reportSha256=sha(ROOT / 'docs/DESUGAR-REVIEW.md'),
                                       buildManifestSha256=sha(ROOT / 'config/source-kit/desugar-build.json')))
    (stage / 'CRAMIN-SOURCE.json').write_text(json.dumps(metadata, indent=2) + '\n')
    checksums = {str(f.relative_to(stage)): sha(f) for f in sorted(stage.rglob('*')) if f.is_file()}
    (stage / 'SHA256.json').write_text(json.dumps(checksums, indent=2) + '\n')
    output = target / f'cramin-v0.1.{code}-source-kit.zip'
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as z:
        for f in sorted(stage.rglob('*')):
            if f.is_file():
                z.write(f, 'source-kit/' + str(f.relative_to(stage)))
    print(output)
    print('SHA256 ' + sha(output))

if __name__ == '__main__':
    main()
