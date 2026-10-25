#!/usr/bin/env python3
"""Package only the requested sources as signed Android APKs and JVM JARs."""

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

import index_pb2

ROOT = Path(__file__).resolve().parents[1]
RAW = "https://raw.githubusercontent.com/badrcoderman/extensions/repo"
WEBSITE = "https://github.com/badrcoderman/extensions"
SOURCES = json.loads((ROOT / "sources.json").read_text())
ANDROID = "{http://schemas.android.com/apk/res/android}"


def run(*command, capture=False):
    return subprocess.run([str(c) for c in command], check=True, capture_output=capture)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/repository")
    args = parser.parse_args()
    output = args.output.resolve()
    if output == ROOT:
        parser.error("Stage packages in a separate directory before publishing")
    output.mkdir(parents=True, exist_ok=True)
    for directory in ('apk', 'jar', 'icon'):
        if (output / directory).exists():
            shutil.rmtree(output / directory)
    key = Path(os.environ["EXTENSION_KEYSTORE"]).resolve()
    alias = os.environ.get("EXTENSION_KEY_ALIAS", "extensions")
    sdk = Path(os.environ["ANDROID_HOME"])
    build_tools = sdk / "build-tools/34.0.0"
    java = Path(os.environ["JAVA_HOME"]) / "bin"
    os.environ["PATH"] = str(java) + os.pathsep + os.environ["PATH"]
    key_args = ["--ks", key, "--ks-key-alias", alias, "--ks-pass", "env:EXTENSION_KEYSTORE_PASSWORD"]
    cert = run(java / "keytool", "-exportcert", "-keystore", key, "-alias", alias, "-storepass:env", "EXTENSION_KEYSTORE_PASSWORD", capture=True).stdout
    fingerprint = hashlib.sha256(cert).hexdigest()
    index = index_pb2.Index(name="Arabic Manga Extensions", badgeLabel="AR", signingKey=fingerprint, contact=index_pb2.Contact(website=WEBSITE))
    legacy = []
    checksums = []
    for config in SOURCES:
        slug, name, home = config['slug'], config['name'], config['home']
        language, lib = config['lang'], config['lib']
        module = ROOT / "src/ar" / slug
        apk = module / f"build/outputs/apk/release/{slug}-release-unsigned.apk"
        manifest = next((module / "build/intermediates/merged_manifests/release").rglob("AndroidManifest.xml"))
        xml = ET.parse(manifest).getroot()
        version = xml.attrib[ANDROID + "versionName"]
        classes = module / f"build/outputs/jar/tachiyomi-ar.{slug}-v{version}-classes.jar"
        code = int(xml.attrib[ANDROID + "versionCode"])
        package = xml.attrib["package"]
        assert code == config['versionCode'], f"Version metadata differs for {slug}"
        metadata = {e.attrib[ANDROID + 'name']: e.attrib[ANDROID + 'value'] for e in xml.findall('application/meta-data')}
        assert metadata['tachiyomix.extensionLib'] == lib
        basename = f"tachiyomi-ar.{slug}-v{version}"
        for directory in ("apk", "jar", "icon"):
            (output / directory).mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory() as temporary:
            temporary = Path(temporary)
            aligned = temporary / "aligned.apk"
            run(build_tools / "zipalign", "-f", "-p", "4", apk, aligned)
            run(build_tools / "apksigner", "sign", *key_args, "--v1-signing-enabled", "true", "--v4-signing-enabled", "false", "--out", output / f"apk/{basename}.apk", aligned)
            unsigned = temporary / "unsigned.jar"
            with zipfile.ZipFile(unsigned, "w", compression=zipfile.ZIP_DEFLATED) as jar:
                def add(path, data):
                    entry = zipfile.ZipInfo(path, (2024, 1, 6, 0, 0, 0))
                    entry.compress_type = zipfile.ZIP_DEFLATED
                    jar.writestr(entry, data)
                add("AndroidManifest.xml", manifest.read_bytes())
                with zipfile.ZipFile(classes) as source:
                    for path in sorted(source.namelist()):
                        if not path.endswith("/"):
                            add(path, source.read(path))
                with zipfile.ZipFile(apk) as source:
                    for path in sorted(source.namelist()):
                        if path.startswith(("res/", "assets/")) or path == "resources.arsc":
                            add(path, source.read(path))
            run(build_tools / "apksigner", "sign", *key_args, "--min-sdk-version", "21", "--v1-signing-enabled", "true", "--v2-signing-enabled", "false", "--v3-signing-enabled", "false", "--out", output / f"jar/{basename}.jar", unsigned)
        shutil.copyfile(module / "res/mipmap-xxxhdpi/ic_launcher.png", output / f"icon/{package}.png")
        source_id = config.get('id') or (int.from_bytes(hashlib.md5(f"{name.lower()}/{language}/{config['versionId']}".encode()).digest()[:8], "big") & ((1 << 63) - 1))
        source = {"name": name, "lang": language, "id": str(source_id), "baseUrl": home, "versionId": config['versionId']}
        legacy.append({"name": f"Tachiyomi: {name}", "pkg": package, "apk": f"{basename}.apk", "jar": f"{basename}.jar", "lang": language, "code": code, "version": version, "nsfw": 1, "sources": [source]})
        extension = index.extensionList.extensions.add(name=name, packageName=package, extensionLib=lib, versionCode=code, versionName=version, contentWarning=index_pb2.CONTENT_WARNING_MIXED)
        extension.resources.apkUrl = f"{RAW}/apk/{basename}.apk"
        extension.resources.jarUrl = f"{RAW}/jar/{basename}.jar"
        extension.resources.iconUrl = f"{RAW}/icon/{package}.png"
        extension.sources.add(id=source_id, name=name, language=language, homeUrl=home)
        for kind in ("apk", "jar"):
            artifact = output / f"{kind}/{basename}.{kind}"
            result = run(build_tools / "apksigner", "verify", "--min-sdk-version", "21", "--print-certs", artifact, capture=True)
            if fingerprint not in result.stdout.decode().replace(":", "").lower():
                raise RuntimeError(f"Wrong signer on {artifact.name}")
            checksums.append(f"{hashlib.sha256(artifact.read_bytes()).hexdigest()}  {kind}/{artifact.name}")
        with zipfile.ZipFile(output / f"jar/{basename}.jar") as jar:
            assert "AndroidManifest.xml" in jar.namelist()
            main = config['mainClass'].replace('.', '/') + '.class' if '.' in config['mainClass'] else f"eu/kanade/tachiyomi/extension/ar/{slug}/{config['mainClass']}.class"
            assert main in jar.namelist()
            if slug == 'procomic':
                with zipfile.ZipFile(module / 'build/upstream/procomic.jar') as original:
                    expected_classes = {path for path in original.namelist() if path.endswith('.class')}
                actual_classes = {path for path in jar.namelist() if path.endswith('.class')}
                assert expected_classes <= actual_classes, f"Required ProComic classes missing: {sorted(expected_classes - actual_classes)}"
            for path in jar.namelist():
                if path.endswith(".class"):
                    data = jar.read(path)
                    assert int.from_bytes(data[6:8], "big") <= (55 if lib == '1.6' else 52), f"{path} requires a newer JVM"
                    if slug != 'procomic':
                        assert path.startswith("eu/kanade/tachiyomi/extension/"), f"Host library bundled: {path}"
        with zipfile.ZipFile(output / f"apk/{basename}.apk") as archive:
            descriptor = ('L' + main.removesuffix('.class') + ';').encode()
            assert any(descriptor in archive.read(path) for path in archive.namelist() if path.startswith('classes') and path.endswith('.dex')), f"Entry class missing from APK: {main}"
        print(f"Verified {name}: signed APK + API {lib} JAR, source ID {source_id}")
    (output / "index.pb").write_bytes(gzip.compress(index.SerializeToString(deterministic=True), mtime=0))
    (output / "index.json").write_text(json.dumps(legacy, ensure_ascii=False, indent=2) + "\n")
    (output / "index.min.json").write_text(json.dumps(legacy, ensure_ascii=False, separators=(",", ":")) + "\n")
    (output / "repo.json").write_text(json.dumps({"meta": {"name": "Arabic Manga Extensions", "website": WEBSITE, "signingKeyFingerprint": fingerprint}}, indent=2) + "\n")
    (output / "SHA256SUMS").write_text("\n".join(checksums) + "\n")
    restored = index_pb2.Index.FromString(gzip.decompress((output / "index.pb").read_bytes()))
    assert len(restored.extensionList.extensions) == len(SOURCES)
    assert {s.name for e in restored.extensionList.extensions for s in e.sources} == {s['name'] for s in SOURCES}
    print(f"Staged repository: {output}")


if __name__ == "__main__":
    main()
