#!/usr/bin/env python3
"""Package one clean committed release APK with matching sources/notices/metadata; no upload."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args]).decode().strip()

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--apk', required=True, type=Path)
    p.add_argument('--output', required=True, type=Path)
    p.add_argument('--cache', type=Path)
    a = p.parse_args()
    if git('status', '--porcelain'):
        raise SystemExit('Release kit requires a clean committed working tree')
    head = git('rev-parse', 'HEAD'); code = int(git('rev-list', '--count', 'HEAD'))
    version = f'0.1.{code}'; tag = 'v'+version
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    tools = sorted((sdk/'build-tools').iterdir(), reverse=True)[0]
    badging = subprocess.check_output([str(tools/'aapt2'), 'dump', 'badging', str(a.apk)]).decode().splitlines()[0]
    if not all(s in badging for s in ["name='pro.perfectproduct.cramin'", f"versionCode='{code}'", f"versionName='{version}'"]):
        raise SystemExit('APK identity/version differs from committed source')
    subprocess.run(['python3', str(ROOT/'scripts/verify_release_signature.py'), str(a.apk)], check=True)
    signature = subprocess.check_output([str(tools/'apksigner'), 'verify', '--verbose', str(a.apk)]).decode()
    for scheme in [2, 3]:
        if not re.search(r'v'+str(scheme)+r' scheme.*: true', signature):
            raise SystemExit('APK lacks required signing scheme v'+str(scheme))
    subprocess.run(['python3', str(ROOT/'scripts/audit-release-inputs.py'), '--apk', str(a.apk)], check=True)
    # These tests exercise the actual packaged offline licence files.
    with zipfile.ZipFile(a.apk) as z:
        provenance = z.read('META-INF/version-control-info.textproto').decode()
        if re.findall(r'revision:\s*"([a-f0-9]{40})"', provenance) != [head]:
            raise SystemExit('APK embedded git revision differs from committed source')
        for f in (ROOT/'app/src/main/assets/legal').iterdir():
            if f.is_file() and z.read('assets/legal/'+f.name) != f.read_bytes():
                raise SystemExit('APK legal asset differs from source: '+f.name)
    if (ROOT/'DISTRIBUTION-LICENSE').read_bytes() != (ROOT/'app/src/main/assets/legal/DISTRIBUTION-LICENSE.txt').read_bytes():
        raise SystemExit('Distribution scope differs between source and APK')
    target = a.output.resolve(); target.mkdir(parents=True, exist_ok=True)
    apk = target/f'cramin-{tag}.apk'; shutil.copyfile(a.apk, apk)
    (target/(apk.name+'.sha256')).write_text(f'{sha(apk)}  {apk.name}\n')
    cmd = ['python3', str(ROOT/'scripts/package-source-kit.py'), '--output', str(target)]
    if a.cache: cmd += ['--cache', str(a.cache.resolve())]
    subprocess.run(cmd, check=True)
    source = target/'source-kit'
    metadata = json.loads((source/'CRAMIN-SOURCE.json').read_text())
    if (metadata['commit'], metadata['versionCode'], metadata['versionName']) != (head, code, version):
        raise SystemExit('Source metadata mismatch')
    for name,h in json.loads((source/'SHA256.json').read_text()).items():
        if sha(source/name) != h: raise SystemExit('Source kit checksum mismatch: '+name)
    docs = ['README.md', 'LICENSE', 'DISTRIBUTION-LICENSE', 'NOTICE', 'docs/DISTRIBUTION.md',
            'docs/THIRD_PARTY_NOTICES.md', 'docs/COMPONENT-LICENSES.md', 'docs/SOURCE-BUILD.md',
            'docs/DESUGAR-REVIEW.md', 'docs/PRIVACY.md', 'docs/PUBLISH-PLAN.md', 'docs/PR-FIRST-RELEASE.md']
    for name in docs:
        dest = target/Path(name).name
        if name in ['docs/PUBLISH-PLAN.md', 'docs/PR-FIRST-RELEASE.md']:
            dest.write_text((ROOT/name).read_text().replace('{{RELEASE_HEAD}}',head).replace('{{RELEASE_VERSION}}',version))
        else: shutil.copyfile(ROOT/name, dest)
    (target/'RELEASE-NOTES.md').write_text((ROOT/'docs/RELEASE-NOTES.md').read_text().replace('0.1.N',version))
    notices = target/f'cramin-{tag}-notices.zip'
    with zipfile.ZipFile(notices,'w',zipfile.ZIP_DEFLATED) as z:
        for name in ['LICENSE','DISTRIBUTION-LICENSE','NOTICE','DISTRIBUTION.md','THIRD_PARTY_NOTICES.md','COMPONENT-LICENSES.md']:
            z.write(target/name,name)
        for f in sorted((source/'legal').iterdir()): z.write(f,'legal/'+f.name)
        z.writestr('CRAMIN-NOTICES.json',json.dumps(dict(commit=head,versionCode=code,versionName=version,distribution=metadata['distribution']),indent=2)+'\n')
    assets=[apk,target/(apk.name+'.sha256'),target/f'cramin-{tag}-source-kit.zip',notices,target/'RELEASE-NOTES.md']
    artifact_data=dict(commit=head,branch=git('branch','--show-current'),versionCode=code,versionName=version,tag=tag,
        apkGitRevision=head,
        applicationId='pro.perfectproduct.cramin',distribution=metadata['distribution'],
        certificateSha256=(ROOT/'release-signing/cert-sha256.txt').read_text().strip(),
        signatureSchemes=['v2','v3'],published=False,
        desugar='Verified; see DESUGAR-REVIEW.md and source-kit/desugar-evidence',
        assets={f.name:dict(sha256=sha(f),size=f.stat().st_size) for f in assets})
    (target/'ARTIFACTS.json').write_text(json.dumps(artifact_data,indent=2)+'\n')
    checks=assets+[target/'ARTIFACTS.json']
    (target/'SHA256SUMS').write_text(''.join(f'{sha(f)}  {f.name}\n' for f in checks))
    if git('rev-parse','HEAD') != head or git('status','--porcelain'):
        raise SystemExit('Source changed while packaging')
    print('PASS exact release/source/notices: '+head+' '+version)
    print('APK SHA256 '+sha(apk))

if __name__ == '__main__': main()
