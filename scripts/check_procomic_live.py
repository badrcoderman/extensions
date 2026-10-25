"""Check ProComic's public API and reader protocol; emit privacy-safe JUnit results."""
from html.parser import HTMLParser
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from urllib.parse import urlencode
import xml.etree.ElementTree as ET

HOME = 'https://procomic.pro'
class Scripts(HTMLParser):
    def __init__(self):
        super().__init__(); self.active = False; self.values = []
    def handle_starttag(self, tag, attrs):
        self.active = tag == 'script'
    def handle_endtag(self, tag):
        if tag == 'script': self.active = False
    def handle_data(self, data):
        if self.active and data.startswith('self.__next_f.push('):
            args = json.loads(data[len('self.__next_f.push('):].rstrip(';')[:-1])
            if args[0] == 1: self.values.append(args[1])


def load(path, body=None, cookies=None):
    command = ['curl', '-4', '-fsSL', '--retry', '2', '--max-time', '45',
               '-A', 'Mozilla/5.0', '--referer', HOME + '/', '-H', 'Cookie: safe_browsing=off']
    if cookies is not None:
        command += ['--cookie-jar', str(cookies), '--cookie', str(cookies)]
    # The config goes through stdin so public reader session tokens are not in process arguments.
    config = 'url = ' + json.dumps(HOME + path) + '\n'
    if body is not None:
        config += 'request = "POST"\nheader = "Content-Type: application/json"\n'
        config += 'data = ' + json.dumps(json.dumps(body)) + '\n'
    result = subprocess.run(command + ['--config', '-'], input=config.encode(), capture_output=True)
    if result.returncode: raise RuntimeError('public_request_failed')
    return result.stdout.decode()


def check():
    with tempfile.TemporaryDirectory() as directory:
        cookies = Path(directory) / 'guest-cookies.txt'
        def fetch(path, body=None): return load(path, body, cookies)
        catalog = json.loads(fetch('/api/public/content?sort=popular&limit=2&page=1'))
        assert catalog['data'] and all(item.get('id') and item.get('title') for item in catalog['data'])
        parser = Scripts()
        parser.feed(fetch('/ar/chapter/after-improperly-licking-a-dog-i-became-a-billionaire-319-213362'))
        stream = ''.join(parser.values)
        decoder = json.JSONDecoder()
        def value(key):
            marker = json.dumps(key) + ':'
            offset = stream.index(marker) + len(marker)
            return decoder.raw_decode(stream[offset:].lstrip())[0]
        chapter_id = value('chapterId')
        assert chapter_id == 213362 and len(value('appImages')) > 0
        session = value('deferredMedia')
        assert session.get('token') and session.get('requireTurnstile') is not True
        query = urlencode({'token': session['token']})
        deferred = json.loads(fetch(f'/chapter-deferred-media/{chapter_id}?{query}'))['data']
        first = deferred['maps'][0]
        plan = {'token': first['token'], 'method': first['method'],
                'cdnPath': value('cdnPath'), 'pageIndex': deferred['splitIndex']}
        mapped = json.loads(fetch(f'/chapter-map-proxy-plan/{chapter_id}', plan))['data']['map']
        assert len(mapped['dim']) == 2 and all(n > 0 for n in mapped['dim'])
        assert mapped['pieces'] and len(mapped['order']) == len(mapped['rects'])
        assert all(0 <= n < len(mapped['pieces']) for n in mapped['order'])
        # Cookies and tokens are discarded. Reports contain no reader payloads or raw exceptions.


def main():
    suite = ET.Element('testsuite', name='ProComicLive', tests='1')
    case = ET.SubElement(suite, 'testcase', classname='ProComicLive', name='livePublicCatalogAndReaderProtocol')
    failed = False
    try: check()
    except Exception:
        failed = True
        ET.SubElement(case, 'failure', message='Public source health check failed').text = 'Inspect the current public API and reader protocol.'
    suite.set('failures', str(int(failed)))
    target = Path(__file__).resolve().parents[1] / 'src/ar/procomic/build/test-results/testReleaseUnitTest/TEST-ProComicLive.xml'
    target.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(target, encoding='utf-8', xml_declaration=True)
    print('ProComic public catalog and reader protocol:', 'FAILED' if failed else 'PASSED')
    return int(failed)

if __name__ == '__main__': sys.exit(main())
