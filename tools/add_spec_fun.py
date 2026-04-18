#!/usr/bin/env python3
"""
Phase 4: Add spec_fun fields to per-area mobiles.yaml files.
Based on MERC #SPECIALS section from all .are files.

Inserts `  spec_fun: "spec_xxx"` as the second field (right after `- id: XXXX`)
for every mob that has a spec assignment.
"""

import re
import os

BASE_DIR = r"c:\Users\jason\dev\TassMUD\src\main\resources\data\MERC"

# area_name -> {mob_id (int) -> spec_fun_name}
SPECIALS = {
    "air": {
        1000: "spec_cast_mage",
    },
    "arachnos": {
        6302: "spec_breath_gas",
        6314: "spec_breath_gas",
        6316: "spec_breath_gas",
        6317: "spec_breath_gas",
        6318: "spec_poison",
        6319: "spec_poison",
    },
    "canyon": {
        9202: "spec_cast_cleric",
        9204: "spec_breath_any",
        9207: "spec_breath_any",
        9208: "spec_cast_cleric",
        9209: "spec_breath_fire",
        9210: "spec_breath_gas",
        9211: "spec_breath_lightning",
        9212: "spec_breath_acid",
        9219: "spec_breath_acid",
        9223: "spec_breath_lightning",
        9224: "spec_thief",
        9225: "spec_thief",
        9226: "spec_breath_fire",
        9227: "spec_breath_lightning",
        9228: "spec_breath_frost",
        9230: "spec_breath_gas",
        9231: "spec_cast_mage",
        9232: "spec_cast_cleric",
        9233: "spec_cast_mage",
        9234: "spec_thief",
        9235: "spec_breath_any",
        9237: "spec_breath_any",
        9238: "spec_cast_mage",
    },
    "catacomb": {
        2007: "spec_cast_undead",
        2009: "spec_cast_undead",
        2011: "spec_cast_mage",
        2013: "spec_cast_undead",
        2014: "spec_cast_undead",
        2016: "spec_breath_any",
        2017: "spec_poison",
    },
    "chapel": {
        3400: "spec_cast_undead",
        3401: "spec_cast_undead",
        3402: "spec_cast_undead",
        3403: "spec_cast_undead",
        3404: "spec_cast_undead",
        3405: "spec_cast_cleric",
        3407: "spec_cast_undead",
        3408: "spec_cast_undead",
        3410: "spec_cast_undead",
        3411: "spec_cast_undead",
        3412: "spec_cast_mage",
        3414: "spec_cast_mage",
        3415: "spec_cast_undead",
        3416: "spec_cast_undead",
    },
    "daycare": {
        6600: "spec_thief",
        6601: "spec_thief",
        6602: "spec_thief",
        6603: "spec_thief",
        6604: "spec_thief",
        6605: "spec_thief",
        6606: "spec_cast_cleric",
        6608: "spec_thief",
    },
    "draconia": {
        2200: "spec_breath_any",
        2203: "spec_cast_mage",
        2204: "spec_cast_cleric",
        2205: "spec_breath_any",
        2220: "spec_breath_any",
        2221: "spec_breath_fire",
        2222: "spec_breath_acid",
        2223: "spec_breath_frost",
        2225: "spec_breath_gas",
        2226: "spec_poison",
        2227: "spec_thief",
        2240: "spec_cast_undead",
        2241: "spec_breath_gas",
        2242: "spec_thief",
        2243: "spec_breath_any",
    },
    "drow": {
        5103: "spec_cast_mage",
        5104: "spec_cast_cleric",
        5108: "spec_cast_cleric",
        5109: "spec_breath_any",
    },
    "dylan": {
        9107: "spec_cast_undead",
    },
    "eastern": {
        5005: "spec_breath_any",
        5010: "spec_breath_fire",
        5014: "spec_cast_cleric",
    },
    "firenewt": {
        2913: "spec_cast_cleric",
        2914: "spec_breath_fire",
    },
    "galaxy": {
        9301: "spec_thief",
        9313: "spec_breath_fire",
        9314: "spec_breath_fire",
        9315: "spec_cast_cleric",
        9316: "spec_cast_cleric",
        9317: "spec_cast_cleric",
        9318: "spec_cast_cleric",
        9319: "spec_cast_cleric",
        9320: "spec_cast_cleric",
        9321: "spec_cast_cleric",
        9322: "spec_cast_cleric",
        9323: "spec_cast_cleric",
        9324: "spec_cast_cleric",
        9325: "spec_cast_cleric",
        9326: "spec_cast_cleric",
        9327: "spec_cast_cleric",
        9328: "spec_cast_cleric",
        9329: "spec_cast_cleric",
        9330: "spec_cast_mage",
        9331: "spec_cast_mage",
        9332: "spec_breath_acid",
    },
    "gnome": {
        1503: "spec_cast_mage",
        1505: "spec_cast_cleric",
        1509: "spec_poison",
        1510: "spec_poison",
        1511: "spec_poison",
        1513: "spec_breath_acid",
        1514: "spec_cast_undead",
        1515: "spec_breath_fire",
        1519: "spec_cast_mage",
    },
    "grave": {
        3601: "spec_cast_undead",
        3602: "spec_cast_undead",
        3603: "spec_cast_undead",
        3604: "spec_cast_undead",
        3605: "spec_cast_undead",
    },
    "grove": {
        8900: "spec_cast_cleric",
        8901: "spec_cast_cleric",
        8902: "spec_cast_cleric",
        8903: "spec_cast_cleric",
        8908: "spec_poison",
        8909: "spec_cast_cleric",
        8910: "spec_cast_cleric",
    },
    "haon": {
        6112: "spec_breath_gas",
        6116: "spec_cast_cleric",
    },
    "hitower": {
        1300: "spec_cast_undead",
        1301: "spec_cast_cleric",
        1304: "spec_cast_mage",
        1306: "spec_cast_mage",
        1307: "spec_cast_mage",
        1308: "spec_cast_mage",
        1309: "spec_cast_mage",
        1310: "spec_cast_mage",
        1311: "spec_thief",
        1312: "spec_cast_undead",
        1313: "spec_cast_undead",
        1314: "spec_cast_mage",
        1315: "spec_cast_mage",
        1316: "spec_cast_mage",
        1317: "spec_cast_mage",
        1318: "spec_cast_mage",
        1320: "spec_cast_mage",
        1321: "spec_cast_mage",
        1322: "spec_cast_mage",
        1323: "spec_cast_mage",
        1324: "spec_cast_mage",
        1325: "spec_cast_mage",
        1326: "spec_cast_mage",
        1327: "spec_cast_mage",
        1328: "spec_cast_mage",
        1329: "spec_cast_mage",
        1330: "spec_cast_mage",
        1331: "spec_cast_mage",
        1332: "spec_cast_mage",
        1333: "spec_cast_mage",
        1334: "spec_cast_mage",
        1336: "spec_cast_mage",
        1337: "spec_cast_mage",
        1338: "spec_cast_mage",
        1340: "spec_cast_mage",
        1341: "spec_cast_mage",
        1348: "spec_cast_mage",
        1349: "spec_cast_mage",
        1350: "spec_cast_undead",
        1351: "spec_cast_undead",
        1352: "spec_cast_mage",
        1353: "spec_cast_mage",
        1354: "spec_cast_mage",
        1355: "spec_cast_mage",
        1356: "spec_cast_mage",
        1357: "spec_cast_mage",
        1358: "spec_cast_mage",
        1359: "spec_cast_mage",
        1360: "spec_cast_mage",
        1361: "spec_cast_mage",
        1362: "spec_cast_mage",
        1363: "spec_cast_mage",
        1364: "spec_cast_mage",
        1365: "spec_cast_mage",
    },
    "mahntor": {
        2300: "spec_cast_undead",
        2302: "spec_breath_acid",
        2306: "spec_poison",
        2310: "spec_cast_cleric",
        2313: "spec_breath_frost",
        2314: "spec_breath_acid",
        2324: "spec_cast_mage",
        2326: "spec_cast_undead",
        2328: "spec_cast_mage",
        2329: "spec_cast_cleric",
        2330: "spec_cast_cleric",
        2333: "spec_cast_mage",
    },
    "marsh": {
        8309: "spec_poison",
        8310: "spec_poison",
        8312: "spec_thief",
        8313: "spec_cast_undead",
        8319: "spec_cast_cleric",
    },
    "mega1": {
        8003: "spec_cast_judge",
        8004: "spec_thief",
        8006: "spec_cast_judge",
        8010: "spec_cast_judge",
        8011: "spec_cast_judge",
    },
    "midennir": {
        3500: "spec_poison",
    },
    "midgaard": {
        3000: "spec_cast_mage",
        3005: "spec_thief",
        3011: "spec_executioner",
        3012: "spec_cast_adept",
        3020: "spec_cast_mage",
        3021: "spec_cast_cleric",
        3022: "spec_thief",
        3024: "spec_cast_mage",
        3042: "spec_cast_mage",
        3043: "spec_cast_cleric",
        3044: "spec_thief",
        3050: "spec_cast_undead",
        3060: "spec_guard",
        3061: "spec_janitor",
        3062: "spec_fido",
        3066: "spec_fido",
        3067: "spec_guard",
        3068: "spec_guard",
        3069: "spec_guard",
        3100: "spec_cast_cleric",
        3120: "spec_cast_cleric",
        3143: "spec_mayor",
    },
    "mirror": {
        5300: "spec_cast_mage",
        5303: "spec_guard",
        5305: "spec_guard",
        5306: "spec_cast_cleric",
        5307: "spec_cast_cleric",
        5317: "spec_cast_mage",
        5320: "spec_guard",
        5321: "spec_guard",
        5327: "spec_cast_cleric",
        5328: "spec_cast_cleric",
        5331: "spec_guard",
        5332: "spec_janitor",
        5333: "spec_fido",
        5334: "spec_guard",
    },
    "moria": {
        4000: "spec_poison",
        4001: "spec_poison",
        4053: "spec_poison",
        4100: "spec_cast_mage",
        4102: "spec_poison",
        4150: "spec_breath_lightning",
        4151: "spec_breath_fire",
        4152: "spec_breath_gas",
        4153: "spec_breath_frost",
        4154: "spec_breath_acid",
        4155: "spec_thief",
        4157: "spec_cast_mage",
        4158: "spec_poison",
    },
    "ofcol2": {
        600: "spec_guard",
        601: "spec_cast_cleric",
        602: "spec_cast_cleric",
        603: "spec_cast_mage",
        621: "spec_janitor",
        623: "spec_guard",
        628: "spec_cast_adept",
        629: "spec_guard",
        630: "spec_breath_any",
        631: "spec_breath_any",
        632: "spec_cast_mage",
        633: "spec_breath_any",
        634: "spec_guard",
    },
    "olympus": {
        901: "spec_cast_cleric",
        902: "spec_breath_fire",
        903: "spec_thief",
        904: "spec_cast_mage",
        905: "spec_cast_adept",
        907: "spec_cast_adept",
        908: "spec_breath_lightning",
        909: "spec_guard",
        914: "spec_breath_any",
        919: "spec_breath_any",
        920: "spec_breath_fire",
        921: "spec_cast_cleric",
        922: "spec_poison",
        923: "spec_thief",
        926: "spec_cast_mage",
    },
    "plains": {
        350: "spec_cast_mage",
    },
    "redferne": {
        7900: "spec_cast_cleric",
    },
    "school": {
        3707: "spec_cast_adept",
        3714: "spec_fido",
    },
    "sewer": {
        7006: "spec_poison",
        7040: "spec_breath_fire",
        7041: "spec_cast_undead",
        7042: "spec_cast_cleric",
        7043: "spec_breath_any",
        7200: "spec_cast_mage",
        7201: "spec_cast_mage",
        7202: "spec_cast_mage",
        7203: "spec_poison",
        7204: "spec_poison",
    },
    "shire": {
        1100: "spec_cast_mage",
        1101: "spec_poison",
        1110: "spec_guard",
        1111: "spec_guard",
        1112: "spec_guard",
        1116: "spec_cast_mage",
        1117: "spec_cast_mage",
        1121: "spec_guard",
        1122: "spec_thief",
        1132: "spec_guard",
    },
    "smurf": {
        101: "spec_thief",
        102: "spec_thief",
        103: "spec_thief",
        104: "spec_thief",
        105: "spec_thief",
        106: "spec_thief",
        107: "spec_thief",
        108: "spec_thief",
        109: "spec_thief",
        110: "spec_cast_mage",
        111: "spec_cast_cleric",
        112: "spec_poison",
    },
    "thalos": {
        5200: "spec_cast_mage",
    },
    "valley": {
        7807: "spec_cast_cleric",
        7808: "spec_cast_mage",
        7809: "spec_breath_fire",
        7811: "spec_cast_mage",
    },
    "wyvern": {
        1605: "spec_cast_cleric",
        1609: "spec_cast_undead",
        1614: "spec_breath_acid",
        1616: "spec_breath_any",
        1617: "spec_cast_undead",
        1716: "spec_cast_undead",
    },
}


def patch_yaml_file(yaml_path: str, specs: dict) -> tuple:
    """
    Add spec_fun field to matching mob entries in a YAML file.
    Returns (added_count, missing_ids, already_had_count).
    """
    with open(yaml_path, 'r', encoding='utf-8') as f:
        content = f.read()

    added = 0
    missing = []
    already_had = []

    for mob_id, spec_fun in specs.items():
        # Check if this mob already has spec_fun
        # Pattern: - id: MOB_ID (with optional surrounding context)
        block_pattern = rf'^- id: {mob_id}\n((?:  .*\n)*)'

        # First check if the mob exists at all
        id_pattern = rf'^- id: {mob_id}$'
        if not re.search(id_pattern, content, re.MULTILINE):
            missing.append(mob_id)
            continue

        # Check if spec_fun already present anywhere near this entry
        # Find the block for this mob
        match = re.search(rf'^(- id: {mob_id}\n(?:  .*\n)*)', content, re.MULTILINE)
        if match:
            block = match.group(1)
            if 'spec_fun:' in block:
                already_had.append(mob_id)
                continue

        # Insert spec_fun right after "- id: MOB_ID\n"
        new_line = f'  spec_fun: "{spec_fun}"\n'
        new_content = re.sub(
            rf'^(- id: {mob_id}\n)',
            rf'\1' + new_line,
            content,
            flags=re.MULTILINE
        )
        if new_content != content:
            content = new_content
            added += 1
        else:
            missing.append(mob_id)

    if added > 0:
        with open(yaml_path, 'w', encoding='utf-8') as f:
            f.write(content)

    return added, missing, already_had


def main():
    total_added = 0
    total_missing = 0
    total_already = 0

    for area, specs in sorted(SPECIALS.items()):
        yaml_path = os.path.join(BASE_DIR, area, "mobiles.yaml")
        if not os.path.exists(yaml_path):
            print(f"SKIP  [{area}]: file not found at {yaml_path}")
            continue

        added, missing, already_had = patch_yaml_file(yaml_path, specs)
        total_added += added
        total_missing += len(missing)
        total_already += len(already_had)

        status = f"[{area}]: +{added} added"
        if missing:
            status += f", {len(missing)} not found: {missing}"
        if already_had:
            status += f", {len(already_had)} already had spec_fun: {already_had}"
        print(status)

    print()
    print(f"Total: {total_added} spec_fun fields added, "
          f"{total_missing} mob IDs not found in YAML, "
          f"{total_already} already had spec_fun.")


if __name__ == "__main__":
    main()
