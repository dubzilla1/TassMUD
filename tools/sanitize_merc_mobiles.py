#!/usr/bin/env python3
"""
Sanitize MERC mobiles.yaml files by converting Python-style flow mappings
used for `template_json: { ... }` into YAML block scalars so SnakeYAML
can parse them reliably.

Backups are written to the same path with a `.bak` suffix.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]  # project root
MERC_DIR = ROOT / 'src' / 'main' / 'resources' / 'data' / 'MERC'


def find_matching_brace(s, start):
    depth = 0
    for i in range(start, len(s)):
        ch = s[i]
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                return i
    return -1


def sanitize_content(text):
    out = []
    idx = 0
    while True:
        m = re.search(r"(^[ \t]*template_json\s*:\s*)\{", text[idx:], re.MULTILINE)
        if not m:
            out.append(text[idx:])
            break
        # append before match
        start = idx + m.start()
        brace_start = idx + m.end() - 1  # position of '{'
        out.append(text[idx:start])
        # find matching '}'
        brace_end = find_matching_brace(text, brace_start)
        if brace_end == -1:
            raise RuntimeError('Unmatched { starting at ' + str(brace_start))
        prefix = m.group(1)
        block = text[brace_start:brace_end+1]
        # determine indentation (spaces at beginning of line of prefix)
        line_start = text.rfind('\n', 0, start) + 1
        indent = text[line_start:start]
        # build block scalar: maintain original chars but indent each line
        block_lines = block.splitlines()
        indented = '\n'.join(indent + '  ' + line for line in block_lines)
        replacement = prefix + "|\n" + indented + "\n"
        out.append(replacement)
        idx = brace_end + 1
    return ''.join(out)


def drop_template_json_blocks(text):
    # Remove both flow-map and block-scalar forms of `template_json:`
    out = []
    i = 0
    while True:
        m = re.search(r"(^[ \t]*template_json\s*:\s*)(.*)$", text[i:], re.MULTILINE)
        if not m:
            out.append(text[i:])
            break
        start = i + m.start()
        line_end = i + m.end()
        prefix = m.group(1)
        rest = m.group(2).strip()
        out.append(text[i:start])
        # If rest starts with '{', remove up to matching brace
        if rest.startswith('{'):
            brace_pos = text.find('{', start)
            if brace_pos == -1:
                # malformed; just skip the line
                i = line_end
                continue
            brace_end = find_matching_brace(text, brace_pos)
            if brace_end == -1:
                # fallback: skip the single line
                i = line_end
                continue
            # advance past the brace_end and any following newline
            j = brace_end + 1
            if j < len(text) and text[j] == '\n':
                j += 1
            i = j
            continue
        # If rest starts with '|' or '>' or is empty, it's a block scalar: consume following indented lines
        if rest.startswith('|') or rest.startswith('>') or rest == '':
            # determine indentation level of this line
            line_start = text.rfind('\n', 0, start) + 1
            indent = text[line_start:start]
            # consume this line
            j = line_end
            # then consume subsequent lines that start with greater indentation
            while j < len(text):
                nl = text.find('\n', j)
                if nl == -1:
                    nl = len(text)
                # get the line start and examine indentation
                line_s = text[j:nl]
                if not line_s:
                    j = nl + 1
                    continue
                if line_s.startswith(indent + ' ') or line_s.startswith(indent + '\t'):
                    j = nl + 1
                    continue
                break
            i = j
            continue
        # otherwise it's an inline scalar (e.g., template_json: someval) - skip that line
        i = line_end
    return ''.join(out)


def convert_problematic_double_quoted_lines(text):
    """Convert single-line double-quoted scalars that contain unescaped
    inner double-quotes into YAML block scalars. Handles lines like:
    long_desc: "A templar shouts "AKK!""
    """
    def repl(m):
        prefix = m.group(1)
        inner = m.group(2)
        # determine indentation
        line_start = text.rfind('\n', 0, m.start()) + 1
        indent = text[line_start:m.start()]
        # build block scalar with one extra indent
        block = prefix + "|\n"
        # indent inner lines by two spaces beyond current indent
        indented = '\n'.join(indent + '  ' + l for l in inner.splitlines())
        return block + indented

    # Match a key: "..." on a single line where the inner content may contain quotes
    pattern = re.compile(r'^([ \t]*[A-Za-z0-9_\-]+\s*:\s*)"(.+)"\s*$', re.MULTILINE)
    return pattern.sub(repl, text)


def process_file(p: Path, drop=False):
    txt = p.read_text(encoding='utf-8')
    # We always attempt to normalize problematic quoted scalars. Only
    # run the template_json-specific sanitizers when `template_json` is present.
    try:
        if 'template_json' in txt:
            if drop:
                new = drop_template_json_blocks(txt)
            else:
                new = sanitize_content(txt)
        else:
            new = txt
        # Run a broader pass to convert problematic double-quoted scalars
        new = convert_problematic_double_quoted_lines(new)
    except Exception as e:
        print(f"Failed to sanitize {p}: {e}")
        return False
    if new != txt:
        bak = p.with_suffix(p.suffix + '.bak')
        # If a backup already exists from a previous run, overwrite it.
        try:
            if bak.exists():
                bak.unlink()
        except Exception:
            pass
        p.rename(bak)
        p.write_text(new, encoding='utf-8')
        action = 'Dropped template_json' if drop else 'Sanitized'
        print(f"{action} {p} -> backup {bak}")
        return True
    return False


def main():
    if not MERC_DIR.exists():
        print('MERC dir not found:', MERC_DIR)
        return
    import sys
    drop = False
    if len(sys.argv) > 1 and sys.argv[1] in ('--drop-template-json', '--drop'):
        drop = True
    changed = 0
    for d in MERC_DIR.iterdir():
        if not d.is_dir():
            continue
        f = d / 'mobiles.yaml'
        if f.exists():
            try:
                if process_file(f, drop=drop):
                    changed += 1
            except Exception as e:
                print('Error processing', f, e)
    verb = 'Dropped template_json in' if drop else 'Sanitized'
    print(f"Done. {verb} {changed} files.")


if __name__ == '__main__':
    main()
