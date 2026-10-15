"""Offline checks for repository hygiene and the project's Markdown conventions."""

from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote, urlsplit

sys.dont_write_bytecode = True
from check_evaluation import validate


def git_paths(*args):
    output = subprocess.check_output(["git", "ls-files", "-z", *args])
    return {Path(name) for name in output.decode().split("\0") if name}


def main():
    errors = []
    for path in sorted(git_paths("--cached", "--ignored", "--exclude-standard")):
        errors.append(f"{path}: tracked file matches ignore rules; remove it from Git")

    wrapper_mode = subprocess.check_output(
        ["git", "ls-files", "--stage", "--", "mvnw"], text=True
    )
    if not wrapper_mode.startswith("100755 "):
        errors.append("mvnw: Git must record executable mode 100755")

    # Include new, non-ignored files so local checks also cover uncommitted docs.
    files = git_paths("--cached", "--others", "--exclude-standard")
    markdown = sorted(path for path in files if path.suffix.lower() == ".md")
    for path in markdown:
        if not path.is_file():
            errors.append(f"{path}: missing file; stage its deletion or restore it")
            continue
        data = path.read_bytes()
        if b"\r" in data:
            errors.append(f"{path}: use LF line endings")
        text = data.decode("utf-8")
        if text and not text.endswith("\n"):
            errors.append(f"{path}: missing final newline")
        fence = None
        for number, line in enumerate(text.splitlines(), 1):
            location = f"{path}:{number}"
            if "\t" in line:
                errors.append(f"{location}: use spaces instead of tabs")
            trailing = line[len(line.rstrip()):]
            if trailing and not (trailing == "  " and line.strip()):
                errors.append(f"{location}: trailing whitespace (only two-space hard breaks allowed)")

            marker = re.match(r"^\s{0,3}(`{3,}|~{3,})(.*)$", line)
            if marker:
                token, rest = marker.groups()
                if fence is None:
                    fence = token
                elif token[0] == fence[0] and len(token) >= len(fence) and not rest.strip():
                    fence = None
                continue
            if fence:
                continue
            prose = re.sub(r"(`+).*?\1", "", line)
            # Project docs use inline links/images, with optional quoted titles.
            for match in re.finditer(r'!?\[[^\]]*\]\(\s*(<[^>]+>|[^\s)]+)(?:\s+"[^"]*")?\s*\)', prose):
                target = urlsplit(match.group(1).strip("<>"))
                if target.scheme or target.netloc or not target.path:
                    continue
                destination = (path.parent / unquote(target.path)).resolve()
                root = Path.cwd().resolve()
                if not destination.is_relative_to(root) or not destination.exists():
                    errors.append(f"{location}: missing relative link target: {target.path}")
                elif destination.is_file() and destination.relative_to(root) not in files:
                    errors.append(f"{location}: link target is ignored or outside the repository file set: {target.path}")
        if fence:
            errors.append(f"{path}: unclosed fenced code block")

    errors.extend(validate(Path("evaluation")))
    validation_tests = subprocess.run(
        [sys.executable, "-B", "-m", "unittest", "discover", "-s", "scripts",
         "-p", "test_check_evaluation.py"]
    )
    if validation_tests.returncode:
        errors.append("Evaluation validator regression tests failed; see diagnostics above")

    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Repository hygiene, evaluation, and documentation checks passed ({len(markdown)} Markdown files).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
