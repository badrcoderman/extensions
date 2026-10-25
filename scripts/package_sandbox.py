#!/usr/bin/env python3
"""Sign the opt-in diagnostics JAR/APK with a separate laboratory certificate."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'
PACKAGE = 'eu.kanade.tachiyomi.extension.en.sandboxdiagnostics'


def run(*command):
    return subprocess.run([str(value) for value in command], check=True, capture_output=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--alias', default='sandbox')
    parser.add_argument('--password-env', default='SANDBOX_KEYSTORE_PASSWORD')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/sandbox-packages')
    args = parser.parse_args()
    if not os.environ.get(args.password_env):
        parser.error('The laboratory keystore password environment variable is unset')
    output = args.output.resolve()
    protected = [ROOT, ROOT/'jar', ROOT/'apk', ROOT/'build/repository']
    if any(output == path or (path != ROOT and path in output.parents) for path in protected):
        parser.error('Laboratory packages must stay outside the production repository package directories')
    java = Path(os.environ['JAVA_HOME']) / 'bin'
    tools = Path(os.environ['ANDROID_HOME']) / 'build-tools/34.0.0'
    os.environ['PATH'] = str(java) + os.pathsep + os.environ['PATH']
    cert = run(java/'keytool', '-exportcert', '-keystore', args.keystore, '-alias', args.alias,
               '-storepass:env', args.password_env).stdout
    fingerprint = hashlib.sha256(cert).hexdigest()
    production = json.loads((ROOT/'repo.json').read_text())['meta']['signingKeyFingerprint']
    if fingerprint == production:
        parser.error('Use a distinct laboratory certificate, not the production signing key')
    module = ROOT/'sandbox'
    manifest = next((module/'build/intermediates/merged_manifests/release').rglob('AndroidManifest.xml'))
    xml = ET.parse(manifest).getroot()
    assert xml.get('package') == PACKAGE
    app = xml.find('application')
    assert not any(app.findall(tag) for tag in ('activity', 'activity-alias', 'service', 'receiver', 'provider'))
    version = xml.get(ANDROID+'versionName')
    classes = module/f'build/outputs/jar/tachiyomi-ar.sandbox-v{version}-classes.jar'
    apk = module/'build/outputs/apk/release/sandbox-release-unsigned.apk'
    output.mkdir(parents=True, exist_ok=True)
    stem = f'sandbox-diagnostics-v{version}'
    key_args = ['--ks', args.keystore, '--ks-key-alias', args.alias, '--ks-pass', f'env:{args.password_env}']
    with tempfile.TemporaryDirectory() as directory:
        temporary = Path(directory)
        aligned = temporary/'aligned.apk'
        run(tools/'zipalign', '-f', '-p', '4', apk, aligned)
        run(tools/'apksigner', 'sign', *key_args, '--v1-signing-enabled', 'true', '--v4-signing-enabled', 'false',
            '--out', output/(stem+'.apk'), aligned)
        unsigned = temporary/'unsigned.jar'
        with zipfile.ZipFile(unsigned, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('AndroidManifest.xml', manifest.read_bytes())
            with zipfile.ZipFile(classes) as compiled:
                for name in sorted(compiled.namelist()):
                    if name.endswith('/'):
                        continue
                    if name.endswith('.class'):
                        assert name.startswith(PACKAGE.replace('.', '/')+'/'), f'Unexpected bundled class: {name}'
                        assert int.from_bytes(compiled.read(name)[6:8], 'big') <= 52
                    archive.writestr(name, compiled.read(name))
            with zipfile.ZipFile(apk) as resources:
                for name in resources.namelist():
                    if name.startswith(('res/', 'assets/')) or name == 'resources.arsc':
                        archive.writestr(name, resources.read(name))
        run(tools/'apksigner', 'sign', *key_args, '--min-sdk-version', '21', '--v1-signing-enabled', 'true',
            '--v2-signing-enabled', 'false', '--v3-signing-enabled', 'false', '--out', output/(stem+'.jar'), unsigned)
    checksums = []
    for suffix in ('apk', 'jar'):
        path = output/(stem+'.'+suffix)
        verification = run(tools/'apksigner', 'verify', '--min-sdk-version', '21', '--print-certs', path).stdout.decode()
        assert fingerprint in verification.replace(':', '').lower()
        checksums.append(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}')
    with zipfile.ZipFile(output/(stem+'.jar')) as archive:
        for name in ('SandboxDiagnostics', 'CapabilityProbes', 'ReportImages'):
            assert PACKAGE.replace('.', '/')+'/'+name+'.class' in archive.namelist()
    with zipfile.ZipFile(output/(stem+'.apk')) as archive:
        entry = ('L'+PACKAGE.replace('.', '/')+'/SandboxDiagnostics;').encode()
        assert any(entry in archive.read(name) for name in archive.namelist() if name.startswith('classes') and name.endswith('.dex'))
    (output/'SHA256SUMS').write_text('\n'.join(checksums)+'\n')
    (output/'metadata.json').write_text(json.dumps({'name': 'Sandbox Diagnostics (LOCAL TEST)', 'package': PACKAGE,
        'version': version, 'extension_api': '1.4', 'signer_sha256': fingerprint,
        'production_index_included': False, 'automatic_telemetry': False,
        'probes': ['own JVM canary', 'temporary canary file', 'private-IP HTTP canary', 'fixed printf process', 'controlled source error']}, indent=2)+'\n')
    print(f'Verified separate laboratory JAR and APK: {output}')


if __name__ == '__main__':
    main()
