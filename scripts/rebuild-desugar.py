#!/usr/bin/env python3
"""Rebuild the two pinned desugar inputs in a separate directory; never uploads."""
import argparse
import hashlib
import http.server
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import threading
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def compare(a, b):
    with zipfile.ZipFile(a) as x, zipfile.ZipFile(b) as y:
        left = {n for n in x.namelist() if not n.endswith('/')}
        right = {n for n in y.namelist() if not n.endswith('/')}
        differences = [n for n in sorted(left & right) if x.read(n) != y.read(n)]
        return dict(left=a.name, right=b.name, sha256Left=sha(a), sha256Right=sha(b),
                    classes=sum(n.endswith('.class') for n in left),
                    onlyLeft=sorted(left-right), onlyRight=sorted(right-left),
                    differentEntries=differences)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source-kit', required=True, type=Path)
    p.add_argument('--work', required=True, type=Path)
    p.add_argument('--cache', type=Path, help='Optional directory of verified build-input downloads')
    args = p.parse_args()
    kit = args.source_kit.resolve()
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=False)
    downloads = work / 'downloads'; downloads.mkdir()
    logs = work / 'logs'; logs.mkdir()
    manifest = json.loads((kit/'manifests/desugar-build.json').read_text())
    commands = []

    def fetch(record, target):
        target.parent.mkdir(parents=True, exist_ok=True)
        cached = args.cache / record['file'] if args.cache else None
        if cached and cached.is_file():
            shutil.copyfile(cached, target)
        else:
            urllib.request.urlretrieve(record['url'], target)
        if sha(target) != record['sha256']:
            raise RuntimeError('Checksum mismatch: '+str(target))

    def run(cmd, cwd, name, env=None):
        commands.append(dict(cwd=str(cwd.relative_to(work)), command=list(map(str, cmd))))
        (work/'commands.json').write_text(json.dumps(commands, indent=2)+'\n')
        print(name, flush=True)
        with (logs/(name+'.log')).open('w') as f:
            subprocess.run(list(map(str, cmd)), cwd=cwd, env=env, stdout=f,
                           stderr=subprocess.STDOUT, check=True)

    upstream = json.loads((kit/'manifests/upstream.json').read_text())
    lib_record = next(x for x in upstream if manifest['libraryCommit'] in x['file'])
    r8_record = json.loads((kit/'manifests/r8.json').read_text())
    if r8_record['commit'] != manifest['r8Commit']:
        raise RuntimeError('R8 commit mismatch')
    for record in [lib_record, r8_record]:
        source = kit/'upstream'/record['file']
        if sha(source) != record['sha256']:
            raise RuntimeError('Source archive checksum mismatch')
    r8 = work/'r8'; r8.mkdir()
    with tarfile.open(kit/'upstream'/r8_record['file']) as t:
        t.extractall(r8, filter='tar')
    with tarfile.open(kit/'upstream'/lib_record['file']) as t:
        t.extractall(work, filter='tar')
    lib = work/('desugar_jdk_libs-'+manifest['libraryCommit'])
    for record in manifest['inputs']:
        archive = downloads/record['file']; fetch(record, archive)
        sha1file = r8/'third_party'/(record['path']+'.tar.gz.sha1')
        if sha1file.read_text().strip() != record['sha1']:
            raise RuntimeError('Upstream dependency pin mismatch')
        with archive.open('rb') as f:
            if hashlib.file_digest(f, 'sha1').hexdigest() != record['sha1']:
                raise RuntimeError('Upstream SHA1 mismatch')
        dest = r8/'third_party'/record['destination']; dest.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive) as t:
            t.extractall(dest, filter='tar')
        shutil.copyfile(archive, r8/'third_party'/(record['path']+'.tar.gz'))
    references = []
    for record in manifest['components']:
        target = downloads/record['file']; fetch(record, target); references.append(target)
    j11 = r8/'third_party/openjdk/jdk-11/linux'
    j17 = r8/'third_party/openjdk/jdk-17/linux'
    j8 = r8/'third_party/openjdk/jdk8/linux-x86'
    sdk = work/'sdk'
    for path in ['build-tools/32.0.0', 'platforms/android-30', 'platforms/android-32']:
        (sdk/path).mkdir(parents=True, exist_ok=True)
    shutil.copyfile(r8/'third_party/android_jar/lib-v32/android.jar', sdk/'platforms/android-32/android.jar')
    # Exact workaround in upstream archive_desugar_jdk_libs.BuildDesugaredLibrary.
    (lib/'jdk11/src/java.base/share/classes/java/time/format/DesugarDateTimeFormatterBuilder.java').unlink()
    env = dict(os.environ, JAVA_HOME=str(j11), ANDROID_HOME=str(sdk),
               PATH=str(j11/'bin')+os.pathsep+os.environ['PATH'])
    if not shutil.which('zip') or not shutil.which('unzip'):
        raise RuntimeError('Install zip and unzip (upstream genrule tools)')
    # Local HTTP transport avoids TLS incompatibility in the historical coursier/JDK.
    # Every response is downloaded from official Maven Central and retained for audit.
    maven = work/'maven'; records = {}
    class Mirror(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            path = self.path.split('?')[0].lstrip('/')
            if '..' in Path(path).parts:
                self.send_error(400); return
            target = maven/path; url = 'https://repo.maven.apache.org/maven2/'+path
            try:
                if not target.is_file():
                    target.parent.mkdir(parents=True, exist_ok=True)
                    urllib.request.urlretrieve(url, target)
                data = target.read_bytes()
                records[url] = dict(url=url, sha256=sha(target), size=len(data))
                self.send_response(200); self.send_header('Content-Length', str(len(data)))
                self.end_headers(); self.wfile.write(data)
            except urllib.error.HTTPError as e:
                self.send_error(e.code)
        def log_message(self, *unused):
            pass
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Mirror)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    setup = lib/'setup.bzl'
    setup.write_text(setup.read_text().replace('https://repo1.maven.org/maven2',
                                              'http://127.0.0.1:'+str(server.server_port)))
    bazel = r8/'third_party/bazel/lib/bazel/bin/bazel'
    try:
        run([bazel, '--bazelrc=/dev/null', '--output_user_root='+str(work/'bazel-cache'),
             'build', '--spawn_strategy=local', '--verbose_failures', 'maven_release_jdk11_nio'],
            lib, 'bazel-library', env)
    finally:
        server.shutdown()
        (work/'maven-downloads.json').write_text(json.dumps(list(records.values()), indent=2)+'\n')
        run([bazel, '--output_user_root='+str(work/'bazel-cache'), 'shutdown'], lib, 'bazel-shutdown', env)
    raw_library = lib/'bazel-bin/jdk11/src/d8_java_base_selected_with_addon.jar'
    conversions = work/'conversions'; conversions.mkdir()
    cpclasses = conversions/'classpath'; cpclasses.mkdir()
    classes = conversions/'classes'; classes.mkdir()
    sources = r8/'src/library_desugar/java'
    run([j8/'bin/javac', '-d', cpclasses]+sorted(p for p in sources.rglob('*.java')
        if p.relative_to(sources).parts[0] != 'java'), work, 'conversion-stubs')
    run([j8/'bin/javac', '-cp', cpclasses, '-d', classes]+sorted((sources/'java').rglob('*.java')),
        work, 'conversion-classes')
    raw_conversions = conversions/'raw.jar'
    with zipfile.ZipFile(raw_conversions, 'w') as z:
        for f in sorted(classes.rglob('*.class')): z.write(f, str(f.relative_to(classes)))
    deps = r8/'third_party/dependencies'
    classpath = os.pathsep.join(map(str, [deps/'org/ow2/asm/asm/9.7.1/asm-9.7.1.jar',
        deps/'com/google/guava/guava/32.1.2-jre/guava-32.1.2-jre.jar']))
    helper = conversions/'helper'
    run([j11/'bin/javac', '-cp', classpath, '-d', helper,
         r8/'src/test/java/com/android/tools/r8/desugar/desugaredlibrary/customconversion/CustomConversionAsmRewriteDescription.java',
         ROOT/'scripts/desugar/BytecodeTransforms.java'], work, 'transformer-compile')
    java = [j11/'bin/java', '-cp', classpath+os.pathsep+str(helper),
            'com.android.tools.r8.desugar.desugaredlibrary.customconversion.BytecodeTransforms']
    generated_conversions = conversions/'generated.jar'
    run(java+['conversion', raw_conversions, generated_conversions], work, 'conversion-transform')
    rebuilt_library = work/'library-rebuilt.jar'
    run(java+['undesugar', raw_library, rebuilt_library,
         r8/'src/test/testbase/java/com/android/tools/r8/desugar/desugaredlibrary/jdk11/DesugaredLibraryJDK11Undesugarer.java'],
        work, 'library-transform')
    init = r8/'skip-unrelated-downloads.gradle'
    init.write_text("allprojects { tasks.matching { it.name == 'downloadDeps' }.configureEach { enabled = false } }\n")
    gradle_env = dict(os.environ, JAVA_HOME=str(j17), GRADLE_USER_HOME=str(work/'gradle-cache'))
    run([r8/'third_party/gradle/bin/gradle', '--offline', '-c=d8_r8/settings.gradle.kts',
         ':main:r8WithRelocatedDeps', '-Pno_internal',
         '-Porg.gradle.java.installations.paths='+str(j11)+','+str(j17),
         '--init-script', init, '--max-workers=4', '--no-daemon'], r8, 'r8-build', gradle_env)
    if not (r8/'build/libs/r8.jar').is_file():
        raise RuntimeError('R8 build did not produce r8.jar')
    # Use newly compiled implementation and conversion classes, not reference binaries.
    shutil.copyfile(raw_library, r8/'third_party/openjdk/desugar_jdk_libs_11/desugar_jdk_libs.jar')
    reference_conversion = r8/'third_party/openjdk/custom_conversion/library_desugar_conversions.jar'
    conversion_comparison = compare(generated_conversions, reference_conversion)
    shutil.copyfile(generated_conversions, reference_conversion)
    mavenzip = work/'configuration-rebuilt.zip'
    run(['python3', r8/'tools/create_maven_release.py', '--desugar-configuration-jdk11-nio',
         '--out', mavenzip], r8, 'configuration-build')
    rebuilt_config = work/'configuration-rebuilt.jar'
    with zipfile.ZipFile(mavenzip) as z:
        jar = next(n for n in z.namelist() if n.endswith('.jar'))
        rebuilt_config.write_bytes(z.read(jar))
    results = dict(conversions=conversion_comparison, library=compare(rebuilt_library, references[0]),
                   configuration=compare(rebuilt_config, references[1]))
    with zipfile.ZipFile(rebuilt_config) as x, zipfile.ZipFile(references[1]) as y:
        results['machineJsonSemanticEqual'] = json.loads(x.read('META-INF/desugar/d8/desugar.json')) == json.loads(y.read('META-INF/desugar/d8/desugar.json'))
    (work/'comparison.json').write_text(json.dumps(results, indent=2)+'\n')
    print(json.dumps(results, indent=2))
    for name in ['conversions', 'library', 'configuration']:
        result = results[name]
        if result['onlyLeft'] or result['onlyRight'] or result['differentEntries']:
            raise SystemExit('Content comparison failed: '+name)


if __name__ == '__main__':
    main()
