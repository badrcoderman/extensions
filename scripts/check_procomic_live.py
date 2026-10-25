"""Check ProComic's public API and reader protocol; emit privacy-safe JUnit results."""
from html.parser import HTMLParser
import json
from pathlib import Path
import subprocess
import sys
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


def load(path):
    result = subprocess.run(['curl', '-4', '-fsSL', '--retry', '2', '--max-time', '45', HOME + path], capture_output=True)
    if result.returncode: raise RuntimeError('public_request_failed')
    return result.stdout.decode()


def check():
    catalog = json.loads(load('/api/public/content?sort=popular&limit=2&page=1'))
    assert catalog['data'] and all(item.get('id') and item.get('title') for item in catalog['data'])
    parser = Scripts()
    parser.feed(load('/ar/chapter/after-improperly-licking-a-dog-i-became-a-billionaire-319-213362'))
    stream = ''.join(parser.values)
    decoder = json.JSONDecoder()
    def value(key):
        marker = json.dumps(key) + ':'
        offset = stream.index(marker) + len(marker)
        return decoder.raw_decode(stream[offset:].lstrip())[0]
    assert value('chapterId') == 213362
    assert len(value('appImages')) > 0
    session = value('deferredMedia')
    assert session.get('token') and session.get('requireTurnstile') is not True
    # Tokens remain in process memory. No reader payload is written to reports or logs.


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
