#!/usr/bin/env python3
"""Exercise the signed lab JAR in a separately started localhost Suwayomi instance."""
import argparse
import io
import json
from pathlib import Path
from urllib.parse import urlsplit

import requests
from PIL import Image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--server', default='http://127.0.0.1:4569')
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    target = urlsplit(args.server)
    if target.scheme != 'http' or target.hostname != '127.0.0.1' or target.path or target.query or target.fragment:
        parser.error('Use a separately started localhost test server')
    base = args.server + '/api/v1'

    def get(path):
        response = requests.get(base + path, timeout=60)
        response.raise_for_status()
        return response.json()

    with args.jar.open('rb') as file:
        response = requests.post(base + '/extension/install', files={'file': (args.jar.name, file, 'application/java-archive')}, timeout=90)
    response.raise_for_status()
    source = next(item for item in get('/source/list') if item['name'] == 'Sandbox Diagnostics (LOCAL TEST)')
    catalog = get(f"/source/{source['id']}/popular/1")['mangaList']
    assert len(catalog) == 5
    chapters = {}
    for manga in catalog:
        mid = manga['id']
        details = get(f'/manga/{mid}?onlineFetch=true')
        assert 'No probe has run' in details['description']
        batch = get(f'/manga/{mid}/chapters?onlineFetch=true')
        assert len(batch) == 1
        chapters[manga['url']] = (mid, batch[0]['index'])
        assert 'No probe has run' in get(f'/manga/{mid}?onlineFetch=true')['description']

    rows = []
    expected = {'jvm': 'PASS', 'storage': 'ALLOWED', 'network': 'ALLOWED', 'process': 'ALLOWED'}
    for probe, outcome in expected.items():
        mid, index = chapters[f'/probe/{probe}']
        reader = get(f'/manga/{mid}/chapter/{index}')
        assert reader['pageCount'] == 1
        response = requests.get(base + f'/manga/{mid}/chapter/{index}/page/0?updateProgress=false', timeout=60)
        response.raise_for_status()
        image = Image.open(io.BytesIO(response.content)); image.load()
        assert image.format == 'PNG' and image.size == (720, 400)
        description = get(f'/manga/{mid}?onlineFetch=true')['description']
        assert f'Last local result: {outcome}' in description
        rows.append({'probe': probe, 'outcome': outcome, 'static_result_png_decoded': True})
        print(probe, outcome, flush=True)

    mid, index = chapters['/probe/failure']
    response = requests.get(base + f'/manga/{mid}/chapter/{index}', timeout=60)
    assert response.status_code == 500
    assert any(item['id'] == source['id'] for item in get('/source/list'))
    assert len(get(f"/source/{source['id']}/popular/1")['mangaList']) == 5
    rows.append({'probe': 'failure', 'outcome': 'EXPECTED_SOURCE_ERROR', 'host_remained_responsive': True})
    evidence = {'host': 'Provided Suwayomi 2.4.2366 AppImage, isolated test data', 'extension_version': '1.4.1',
                'source_id': source['id'], 'catalog_and_chapter_lists_run_no_probes': True,
                'probes': rows, 'ios_tested': False, 'android_tested': False,
                'scope': 'Fixed capability canaries; no sandbox escape attempted or established'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(evidence, indent=2) + '\n')
    print('Signed laboratory JAR host checks passed', flush=True)


if __name__ == '__main__':
    main()
