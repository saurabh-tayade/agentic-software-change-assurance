"""Mutation tests ensure invalid ground truth fails with useful diagnostics."""

import json
from pathlib import Path
import shutil
import tempfile
import unittest

from check_evaluation import validate


class EvaluationValidationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / 'evaluation'
        shutil.copytree(Path(__file__).resolve().parents[1] / 'evaluation', self.root)
        self.suite = self.root / 'scenarios/parcel-quotes-v1.json'
        self.data = json.loads(self.suite.read_text())

    def check_mutation(self, mutate, expected):
        mutate(self.data['scenarios'])
        self.suite.write_text(json.dumps(self.data))
        self.assertTrue(any(expected in error for error in validate(self.root)), expected)

    def test_valid_corpus(self):
        self.assertEqual([], validate(self.root))

    def test_invalid_scenarios(self):
        mutations = [
            (lambda s: s[0].pop('question'), 'question must'),
            (lambda s: s.append(s[0]), 'duplicate scenario id'),
            (lambda s: s[0].update(expected_answerability='yes'), 'expected_answerability must'),
            (lambda s: s[0].update(corpus_version='v9'), 'unknown corpus'),
            (lambda s: s[0].update(expected_findings=[]), 'expected_findings must'),
            (lambda s: s[0].pop('expected_evidence'), 'expected_evidence must'),
            (lambda s: s[0].update(expected_evidence=[]), 'require evidence'),
            (lambda s: s[0].update(corpus_id=[]), 'unknown corpus'),
        ]
        original = self.suite.read_text()
        for mutate, expected in mutations:
            with self.subTest(expected=expected):
                self.data = json.loads(original)
                self.check_mutation(mutate, expected)

    def test_invalid_references(self):
        original = self.suite.read_text()
        for changes, expected in [
            ({'path': 'missing.java'}, 'referenced source missing'),
            ({'path': '../README.md'}, 'normalized corpus-relative'),
            ({'corpus_version': 'v2'}, 'differs from scenario'),
            ({'start_line': 0}, 'invalid inclusive line range'),
            ({'start_line': True}, 'invalid inclusive line range'),
            ({'end_line': 999}, 'invalid inclusive line range'),
            ({'start_line': 5, 'end_line': 3}, 'invalid inclusive line range'),
            ({'contains': 'invented evidence'}, 'contains must match'),
        ]:
            with self.subTest(changes=changes):
                self.data = json.loads(original)
                self.check_mutation(lambda s: s[0]['expected_evidence'][0].update(changes), expected)

    def test_changed_source_bytes(self):
        source = self.root / 'corpora/parcel-quotes/v1/README.md'
        source.write_text(source.read_text() + '\nChanged fixture.\n')
        self.assertTrue(any('SHA256 mismatch' in error for error in validate(self.root)))

    def test_malformed_json(self):
        self.suite.write_text('{')
        self.assertTrue(any('cannot read JSON' in error for error in validate(self.root)))

    def test_missing_source(self):
        (self.root / 'corpora/parcel-quotes/v1/README.md').unlink()
        self.assertTrue(any('missing source file' in error for error in validate(self.root)))


if __name__ == '__main__':
    unittest.main()
