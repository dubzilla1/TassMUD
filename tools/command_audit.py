import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'src' / 'main' / 'java'

def extract_registrations(path):
    regs = {}
    aliases = {}
    with open(path, 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line.startswith('register(') or line.startswith('registerCombat(') or line.startswith('registerGm(') or ' register(' in line:
                # find first quoted string
                m = re.search(r'\("([^"]+)"', line)
                if m:
                    cmd = m.group(1).lower()
                    regs[cmd] = line
                    # find aliases via List.of(...)
                    mo = re.search(r'List\.of\(([^)]*)\)', line)
                    if mo:
                        content = mo.group(1)
                        found = re.findall(r'"([^"]+)"', content)
                        if found:
                            aliases[cmd] = [a.lower() for a in found]
    return regs, aliases


def extract_item_handler_supported(path):
    supported = set()
    with open(path, 'r', encoding='utf-8') as f:
        text = f.read()
        m = re.search(r'SUPPORTED_COMMANDS\s*=\s*Set\.of\(([^)]*)\)', text)
        if m:
            content = m.group(1)
            found = re.findall(r'"([^"]+)"', content)
            for a in found:
                supported.add(a.lower())
    return supported


def extract_clienthandler_cases(path):
    cases = set()
    with open(path, 'r', encoding='utf-8') as f:
        text = f.read()
        found = re.findall(r'case\s+"([^"]+)"', text)
        for a in found:
            cases.add(a.lower())
    return cases


if __name__ == '__main__':
    cr = SRC / 'com' / 'example' / 'tassmud' / 'net' / 'CommandRegistry.java'
    ih = SRC / 'com' / 'example' / 'tassmud' / 'net' / 'commands' / 'ItemCommandHandler.java'
    ch = SRC / 'com' / 'example' / 'tassmud' / 'net' / 'ClientHandler.java'

    regs, aliases = extract_registrations(cr)
    item_supported = extract_item_handler_supported(ih)
    ch_cases = extract_clienthandler_cases(ch)

    # Build canonical -> aliases map and alias->canonical
    alias_to_canonical = {}
    for can, al in aliases.items():
        for a in al:
            alias_to_canonical[a] = can

    # Report
    print('Registered commands (canonical):')
    for cmd in sorted(regs.keys()):
        a = aliases.get(cmd, [])
        print(f'  {cmd}' + (f'  aliases={a}' if a else ''))
    print('\nItemCommandHandler supports:')
    for s in sorted(item_supported):
        print('  ' + s)
    print('\nClientHandler cases found: (sample)')
    print('  total cases found:', len(ch_cases))

    # For each registered command, check handled
    print('\nCommand handling summary:')
    missing = []
    for cmd in sorted(regs.keys()):
        handled_by = []
        # If dispatcher handler supports it (item handler)
        if cmd in item_supported:
            handled_by.append('ItemHandler')
        # aliases mapping: also check aliases
        if cmd in ch_cases:
            handled_by.append('ClientHandler')
        # check aliases presence
        for a, can in alias_to_canonical.items():
            if can == cmd:
                if a in ch_cases:
                    handled_by.append('ClientHandler(alias)')
                if a in item_supported:
                    handled_by.append('ItemHandler(alias)')
        if not handled_by:
            missing.append(cmd)
        print(f'  {cmd}: ' + (', '.join(sorted(set(handled_by))) if handled_by else 'NOT HANDLED'))

    if missing:
        print('\nCommands registered but not found in any handler:')
        for m in missing:
            print('  ' + m)
    else:
        print('\nAll registered commands were found in handlers (or as aliases)')

    # Also list aliases that are registered but not present in handlers
    print('\nAliases mapping (alias -> canonical):')
    for a, can in sorted(alias_to_canonical.items()):
        print(f'  {a} -> {can}')

    # Quick suggestions
    print('\nSuggestions:')
    print('  - Ensure CommandDispatcher.registerHandler is called for categories you want to move out of ClientHandler.')
    print('  - Expand ItemCommandHandler.SUPPORTED_COMMANDS to include more item commands or implement new handlers.')
    print('  - For any "NOT HANDLED" commands, add a case in ClientHandler or a handler in CommandDispatcher.')
