#!/usr/bin/env python3
"""Report automated check failures without reader URLs, exception text or chapter data."""
import json
import os
from pathlib import Path
import re
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def collect(root=ROOT):
    sources = {item['slug']: item['name'] for item in json.loads((root / 'sources.json').read_text())}
    failures = {}
    for path in (root / 'src').glob('*/*/build/test-results/testReleaseUnitTest/TEST-*.xml'):
        slug = path.relative_to(root / 'src').parts[1]
        if slug not in sources:
            continue
        try:
            suite = ET.parse(path).getroot()
        except ET.ParseError:
            failures.setdefault(slug, set()).add('test_report_unreadable')
            continue
        for case in suite.findall('testcase'):
            if case.find('failure') is None and case.find('error') is None:
                continue
            # Test names are code identifiers. Deliberately omit messages, logs and stack traces.
            name = case.get('name', '')
            safe = name if re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_.$]{0,150}', name) else 'automated_test_failed'
            failures.setdefault(slug, set()).add(safe)
    return {slug: (sources[slug], sorted(names)) for slug, names in failures.items()}


def reports(failures, run_url):
    if not failures:
        failures = {'build': ('Build / lint / package verification', ['automated_check_failed'])}
    for slug, (name, tests) in sorted(failures.items()):
        marker = f'<!-- extension-check:{slug} -->'
        yield {
            'marker': marker,
            'title': f'[Automated bug report] {name}',
            'body': f'{marker}\n\nAutomated verification failed for **{name}**.\n\n'
                    + '\n'.join(f'- `{test}`' for test in tests)
                    + f'\n\n[Open the check run]({run_url})\n\n'
                    + 'This report omits reader URLs, chapter titles, tokens, and exception contents. '
                      'A failed check can also mean a temporary website outage or a blocked CI connection.',
        }


class GitHub:
    def __init__(self, repository, token):
        self.base = f'https://api.github.com/repos/{repository}'
        self.token = token

    def request(self, path, data=None):
        request = urllib.request.Request(self.base + path,
            data=json.dumps(data).encode() if data is not None else None,
            headers={'Authorization': f'Bearer {self.token}', 'Accept': 'application/vnd.github+json',
                     'X-GitHub-Api-Version': '2022-11-28', 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)

    def open_issues(self):
        page = 1
        while True:
            batch = self.request(f'/issues?state=open&per_page=100&page={page}')
            yield from (issue for issue in batch if 'pull_request' not in issue)
            if len(batch) < 100:
                return
            page += 1

    def create(self, report):
        return self.request('/issues', {key: report[key] for key in ('title', 'body')})


def publish(client, pending):
    bodies = [issue.get('body') or '' for issue in client.open_issues()]
    created = 0
    for report in pending:
        if any(report['marker'] in body for body in bodies):
            print('Existing report retained:', report['title'])
            continue
        client.create(report)
        bodies.append(report['body'])
        created += 1
        print('Created report:', report['title'])
    return created


def main():
    repository = os.environ['GITHUB_REPOSITORY']
    run_id = os.environ['GITHUB_RUN_ID']
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository) or not run_id.isdecimal():
        raise ValueError('Invalid workflow coordinates')
    url = f'https://github.com/{repository}/actions/runs/{run_id}'
    publish(GitHub(repository, os.environ['GITHUB_TOKEN']), reports(collect(), url))


if __name__ == '__main__':
    main()
