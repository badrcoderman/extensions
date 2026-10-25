#!/usr/bin/env python3
"""Check that real upstream plans fill every destination pixel without background paint."""
import json
from pathlib import Path


def check(plan):
    width, height = plan['dim']
    rectangles = plan['rects']
    for rect in rectangles:
        assert 0 <= rect['left'] < rect['left'] + rect['width'] <= width
        assert 0 <= rect['top'] < rect['top'] + rect['height'] <= height
    rows = sorted({0, height} | {edge for r in rectangles for edge in (r['top'], r['top'] + r['height'])})
    for top, bottom in zip(rows, rows[1:]):
        segments = sorted((r['left'], r['left'] + r['width']) for r in rectangles if r['top'] <= top and r['top'] + r['height'] >= bottom)
        cursor = 0
        for left, right in segments:
            assert left == cursor, f'Gap or overlap at row {top}, column {cursor}'
            cursor = right
        assert cursor == width, f'Unfilled row at {top}'


if __name__ == '__main__':
    fixtures = Path(__file__).resolve().parents[1] / 'common/fixtures/procomic/geometry.json'
    plans = json.loads(fixtures.read_text())
    for plan in plans:
        check(plan)
    print(f'ProComic: {len(plans)} real plans cover every destination pixel without drawColor')
