import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('report_failures', Path(__file__).with_name('report_failures.py'))
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class ReportTests(unittest.TestCase):
    def test_private_failure_contents_are_not_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'sources.json').write_text(json.dumps([{'slug': 'sample', 'name': 'Sample'}]))
            path = root / 'src/ar/sample/build/test-results/testReleaseUnitTest'
            path.mkdir(parents=True)
            (path / 'TEST-example.xml').write_text('''<testsuite><testcase name="readerChecks"><failure message="token=PRIVATE_TOKEN">PRIVATE_CHAPTER https://example/reader?secret=PRIVATE_SECRET</failure></testcase></testsuite>''')
            pending = list(report.reports(report.collect(root), 'https://github.com/owner/repo/actions/runs/1'))
            text = json.dumps(pending)
            self.assertIn('readerChecks', text)
            for secret in ('PRIVATE_TOKEN', 'PRIVATE_CHAPTER', 'PRIVATE_SECRET', 'example/reader'):
                self.assertNotIn(secret, text)

    def test_repeat_failures_do_not_create_duplicate_issues_or_comments(self):
        class Client:
            def __init__(self): self.issues = []
            def open_issues(self): return self.issues
            def create(self, issue): self.issues.append(dict(issue))
        client = Client()
        pending = list(report.reports({'sample': ('Sample', ['readerChecks'])}, 'https://github.com/owner/repo/actions/runs/1'))
        self.assertEqual(1, report.publish(client, pending))
        self.assertEqual(0, report.publish(client, pending))
        self.assertEqual(1, len(client.issues))


if __name__ == '__main__': unittest.main()
