"""Validate controlled evidence fixtures without parsing source or calling services."""

import hashlib
import json
from pathlib import Path, PurePosixPath


def validate(root):
    errors = []
    seen = set()

    def fail(location, message):
        errors.append(f"{location}: {message}")

    def read(path):
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            fail(path, f"cannot read JSON: {exc}")
            return None

    def nonempty(value):
        return isinstance(value, str) and bool(value.strip())

    def safe_path(value):
        return (nonempty(value) and '\\' not in value
                and not PurePosixPath(value).is_absolute()
                and all(part not in ('', '.', '..') for part in value.split('/')))

    manifests = {}
    for path in sorted((root / 'corpora').glob('*/*/manifest.json')):
        data = read(path)
        if not isinstance(data, dict):
            fail(path, 'manifest must be an object')
            continue
        key = (path.parent.parent.name, path.parent.name)
        if (type(data.get('schema_version')) is not int or data['schema_version'] != 1
                or (data.get('corpus_id'), data.get('corpus_version')) != key):
            fail(path, 'schema_version must be 1 and corpus identity must match directory')
        files = data.get('files')
        if not isinstance(files, dict) or not files:
            fail(path, 'files must be a nonempty path-to-SHA256 object')
            continue
        manifests[key] = (path.parent, files)
        actual = {p.relative_to(path.parent).as_posix() for p in path.parent.rglob('*')
                  if p.is_file() and p != path}
        if actual != set(files):
            fail(path, 'manifest file inventory differs from corpus; review version and hashes')
        for name, digest in files.items():
            source = path.parent / name
            if not safe_path(name) or not source.resolve().is_relative_to(path.parent.resolve()):
                fail(path, f'unsafe corpus path: {name}')
            elif not source.is_file():
                fail(path, f'missing source file: {name}')
            elif not isinstance(digest, str) or hashlib.sha256(source.read_bytes()).hexdigest() != digest:
                fail(path, f'SHA256 mismatch: {name}; restore bytes or create a new corpus version')

    suites = sorted((root / 'scenarios').glob('*.json'))
    if not suites:
        fail(root, 'no scenario suites found')
    for path in suites:
        data = read(path)
        if (not isinstance(data, dict) or type(data.get('schema_version')) is not int
                or data['schema_version'] != 1 or not isinstance(data.get('scenarios'), list)
                or not data['scenarios']):
            fail(path, 'expected schema_version 1 and a nonempty scenarios array')
            continue
        for index, scenario in enumerate(data['scenarios']):
            location = f'{path}:scenario[{index}]'
            if not isinstance(scenario, dict):
                fail(location, 'scenario must be an object')
                continue
            for field in ('id', 'question', 'corpus_id', 'corpus_version'):
                if not nonempty(scenario.get(field)):
                    fail(location, f'{field} must be a nonempty string')
            identifier = scenario.get('id')
            if isinstance(identifier, str):
                location += f' ({identifier})'
                if identifier in seen:
                    fail(location, 'duplicate scenario id')
                seen.add(identifier)
            answerability = scenario.get('expected_answerability')
            if answerability not in ('answerable', 'partial', 'insufficient'):
                fail(location, 'expected_answerability must be answerable, partial, or insufficient')
            findings = scenario.get('expected_findings')
            if not isinstance(findings, list) or not findings or not all(nonempty(f) for f in findings):
                fail(location, 'expected_findings must be a nonempty array of strings')
            identity = (scenario.get('corpus_id'), scenario.get('corpus_version'))
            corpus = manifests.get(identity) if all(isinstance(v, str) for v in identity) else None
            if corpus is None:
                fail(location, 'unknown corpus identifier/version')
            references = scenario.get('expected_evidence')
            if not isinstance(references, list):
                fail(location, 'expected_evidence must be an array')
                continue
            if answerability in ('answerable', 'partial') and not references:
                fail(location, 'answerable or partial scenarios require evidence')
            for number, ref in enumerate(references):
                where = f'{location}:expected_evidence[{number}]'
                if not isinstance(ref, dict):
                    fail(where, 'reference must be an object')
                    continue
                if (ref.get('corpus_id'), ref.get('corpus_version')) != identity:
                    fail(where, 'reference corpus identifier/version differs from scenario')
                name = ref.get('path')
                if not safe_path(name):
                    fail(where, 'path must be a normalized corpus-relative file path')
                    continue
                if corpus is None:
                    continue
                directory, files = corpus
                source = directory / name
                if (name not in files or not source.is_file()
                        or not source.resolve().is_relative_to(directory.resolve())):
                    fail(where, f'referenced source missing or outside corpus manifest: {name}')
                    continue
                try:
                    lines = source.read_text(encoding='utf-8').splitlines()
                except (OSError, UnicodeError) as exc:
                    fail(where, f'cannot read source: {exc}')
                    continue
                start, end = ref.get('start_line'), ref.get('end_line')
                if type(start) is not int or type(end) is not int or not 1 <= start <= end <= len(lines):
                    fail(where, f'invalid inclusive line range {start}..{end} for {name} ({len(lines)} lines)')
                    continue
                contains = ref.get('contains')
                if not nonempty(contains) or contains not in '\n'.join(lines[start - 1:end]):
                    fail(where, f'contains must match expected source material at {name}:{start}-{end}')
    return errors
