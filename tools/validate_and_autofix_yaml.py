#!/usr/bin/env python3
"""Validate and attempt safe autofixes for MERC YAML files.

Runs a lightweight set of repairs for common problems observed in the repo
and writes backups before changing files.

Usage: python tools/validate_and_autofix_yaml.py [--dry-run]
"""
import re
import sys
from pathlib import Path
import yaml


def try_load(text):
    try:
        yaml.safe_load(text)
        return True, None
    except Exception as e:
        return False, str(e)


def fix_bare_keys(text):
    # If a bare token line (e.g. "the_baby_beholder" or "fairy dragon")
    # occurs followed by an indented field (e.g. "  name:"), insert a colon
    # on the bare key. If the bare key contains spaces, slugify it (replace
    # spaces with underscores) so it's a valid YAML map key.
    def slug(s: str) -> str:
        s2 = s.strip()
        s2 = re.sub(r"\s+", "_", s2)
        s2 = re.sub(r"[^A-Za-z0-9_\-]", "", s2)
        return s2

    lines = text.splitlines()
    out = []
    i = 0
    while i < len(lines):
        line = lines[i]
        # any non-empty line that does not contain ':' and is not indented
        m = re.match(r"^(\s*)([^:\n]+)\s*$", line)
        if m and i + 1 < len(lines):
            indent = m.group(1)
            token = m.group(2)
            next_line = lines[i+1]
            if re.match(r"^\s+[A-Za-z0-9_\-]+\s*:.*$", next_line):
                newkey = token
                if ' ' in token or not re.match(r"^[A-Za-z0-9_\-]+$", token):
                    newkey = slug(token)
                out.append(indent + newkey + ':')
                i += 1
                continue
        out.append(line)
        i += 1
    return "\n".join(out) + "\n"


def wrap_lines_with_unescaped_inner_quotes(text):
    # Convert single-line double-quoted scalars that contain unescaped inner
    # quotes to block scalars for safety. Look for pattern: key: "..." with
    # an inner unescaped " inside.
    def repl(m):
        key = m.group(1)
        content = m.group(2)
        # replace with block scalar
        lines = content.split('\\n')
        block = key + '|\n'
        for ln in lines:
            block += '  ' + ln + '\n'
        return block

    pattern = re.compile(r"^(\s*[A-Za-z0-9_\-]+:)\s*\"((?:[^\\\"]|\\\\|\\\")*)\"\s*$", re.M)
    return pattern.sub(repl, text)


def run_fix(path: Path, dry_run=False):
    text = path.read_text(encoding='utf-8')
    ok, err = try_load(text)
    if ok:
        return False, 'valid'

    # Preliminary: remove entire template_json blocks (they frequently contain
    # raw embedded data that breaks SnakeYAML). We'll remove from a line that
    # starts with 'template_json:' up to the next top-level list item '- id:'.
    def drop_template_json_blocks(t: str) -> str:
        lines = t.splitlines()
        out = []
        i = 0
        while i < len(lines):
            line = lines[i]
            if re.match(r"^\s*template_json\s*:\s*$", line):
                # skip until next line that looks like an item start
                i += 1
                while i < len(lines) and not re.match(r"^\s*-\s*id\s*:\s*", lines[i]):
                    i += 1
                continue
            out.append(line)
            i += 1
        return "\n".join(out) + "\n"

    text = drop_template_json_blocks(text)

    original = text
    changed = False

    # 1) fix bare keys
    fixed = fix_bare_keys(text)
    if fixed != text:
        text = fixed
        changed = True

    # 2) wrap problematic quoted strings
    fixed = wrap_lines_with_unescaped_inner_quotes(text)
    if fixed != text:
        text = fixed
        changed = True

    # 2b) ensure common "key:|" forms have a space before the pipe
    fixed = re.sub(r"^(\s*(name|short_desc|long_desc|raw_block|extra_desc):)\|\s*$", r"\1 |", text, flags=re.M)
    if fixed != text:
        text = fixed
        changed = True

    # 2c) fix list dash spacing (ensure '- ' not '-') to help parsers
    fixed = re.sub(r"^(\s*)-(\S)", r"\1- \2", text, flags=re.M)
    if fixed != text:
        text = fixed
        changed = True

    # 2d) convert extra_desc or other freeform lines containing inner quotes
    # into block scalars to avoid mapping-value errors
    def block_for_key(m):
        key = m.group(1)
        content = m.group(2).rstrip()
        return f"{key}|\n  {content}\n"

    fixed = re.sub(r"^(\s*(extra_desc|long_desc|short_desc):)\s*(.*\".*)$", block_for_key, text, flags=re.M)
    if fixed != text:
        text = fixed
        changed = True

    # 2e) normalize block scalars so their content lines are indented
    def normalize_block_pipes_all(t: str) -> str:
        lines = t.splitlines()
        out = []
        i = 0
        while i < len(lines):
            line = lines[i]
            m = re.match(r"^(\s*[A-Za-z0-9_\-]+:\s*\|)\s*$", line)
            if m:
                out.append(line)
                i += 1
                # collect content lines until next top-level key or end
                while i < len(lines):
                    nxt = lines[i]
                    if re.match(r"^\s*[A-Za-z0-9_\-]+\s*:.*$", nxt):
                        break
                    # ensure at least two spaces indent
                    if len(nxt) > 0 and not re.match(r"^\s{2,}.*$", nxt):
                        out.append('  ' + nxt.lstrip())
                    else:
                        out.append(nxt)
                    i += 1
                continue
            out.append(line)
            i += 1
        return "\n".join(out) + "\n"

    fixed = normalize_block_pipes_all(text)
    if fixed != text:
        text = fixed
        changed = True

    # 3) quote percent-sign name lines conservatively
    fixed = re.sub(r"^(\s*name:)\s*(%.*)$", r"\1 \"\2\"", text, flags=re.M)
    if fixed != text:
        text = fixed
        changed = True

    ok2, err2 = try_load(text)
    if not ok2:
        return changed, f'failed-after-fixes: {err2}'

    if not dry_run and changed:
        bak = path.with_suffix(path.suffix + '.bak')
        bak.write_text(original, encoding='utf-8')
        path.write_text(text, encoding='utf-8')

    return changed, 'fixed'


def main():
    dry = '--dry-run' in sys.argv
    root = Path('src/main/resources/data/MERC')
    if not root.exists():
        print('MERC data folder not found:', root)
        return 2

    files = list(root.rglob('*.yaml'))
    total = 0
    fixed_count = 0
    failed = []
    for f in files:
        total += 1
        changed, status = run_fix(f, dry_run=dry)
        print(f'{f}: {status}{" (changed)" if changed else ""}')
        if changed and status == 'fixed':
            fixed_count += 1
        if status.startswith('failed'):
            failed.append((f, status))

    print(f'Done. Scanned {total} files. Fixed: {fixed_count}. Failures: {len(failed)}')
    for f, s in failed[:20]:
        print('FAIL:', f, s)
    return 0


if __name__ == '__main__':
    sys.exit(main())
