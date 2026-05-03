#!/usr/bin/env python3
"""
area_connectivity.py — Scan all MERC area rooms.yaml files and build an
inter-area connection graph.

Usage:
    python tools/area_connectivity.py            # print full report
    python tools/area_connectivity.py --dot       # also emit a Graphviz .dot file
"""

import os
import sys
import re
import yaml
from collections import defaultdict
from pathlib import Path
from typing import Optional

MERC_DIR = Path("src/main/resources/data/MERC")
DIRECTIONS = {"north", "south", "east", "west", "up", "down"}


# ─────────────────────────────────────────────────────────────────────────────
# Data loading
# ─────────────────────────────────────────────────────────────────────────────

def load_areas():
    """Return dict: area_name → {id, name, vnum_low, vnum_high}"""
    areas = {}
    for area_dir in sorted(MERC_DIR.iterdir()):
        if not area_dir.is_dir():
            continue
        ay = area_dir / "areas.yaml"
        if not ay.exists():
            continue
        with open(ay, encoding="utf-8") as f:
            data = yaml.safe_load(f)
        for entry in (data or {}).get("areas", []):
            vrange = entry.get("vnum_range", "0-0")
            m = re.match(r"(\d+)-(\d+)", vrange)
            lo, hi = (int(m.group(1)), int(m.group(2))) if m else (0, 0)
            areas[area_dir.name] = {
                "id":   entry.get("id", 0),
                "name": entry.get("name", area_dir.name),
                "low":  lo,
                "high": hi,
            }
            break  # one area per directory
    return areas


def load_rooms(area_name: str):
    """Return list of room dicts from rooms.yaml for the given area."""
    ry = MERC_DIR / area_name / "rooms.yaml"
    if not ry.exists():
        return []
    with open(ry, encoding="utf-8") as f:
        data = yaml.safe_load(f)
    return (data or {}).get("rooms", []) or []


# ─────────────────────────────────────────────────────────────────────────────
# Connectivity analysis
# ─────────────────────────────────────────────────────────────────────────────

def vnum_to_area(vnum: int, areas: dict) -> Optional[str]:
    """Return the area_name that owns this vnum, or None."""
    for name, info in areas.items():
        if info["low"] <= vnum <= info["high"]:
            return name
    return None


def build_connection_graph(areas: dict):
    """
    Returns:
        connections: dict[area_name, set[area_name]]   — which areas connect to which
        details:     dict[(src_area, dst_area), list[(src_room, dir, dst_room)]]
    """
    connections: dict[str, set[str]] = defaultdict(set)
    details: dict[tuple[str, str], list] = defaultdict(list)

    for area_name, info in areas.items():
        rooms = load_rooms(area_name)
        my_low, my_high = info["low"], info["high"]

        for room in rooms:
            rid = room.get("id")
            exits = room.get("exits") or {}
            if not isinstance(exits, dict):
                continue
            for direction, dest in exits.items():
                if direction not in DIRECTIONS:
                    continue
                if not isinstance(dest, int):
                    continue
                # Is the destination outside this area?
                if my_low <= dest <= my_high:
                    continue
                # Which area does it land in?
                dest_area = vnum_to_area(dest, areas)
                if dest_area is None:
                    dest_area = f"<unknown:{dest}>"
                if dest_area == area_name:
                    continue  # shouldn't happen, but guard
                connections[area_name].add(dest_area)
                details[(area_name, dest_area)].append((rid, direction, dest))

    return connections, details


# ─────────────────────────────────────────────────────────────────────────────
# Reporting
# ─────────────────────────────────────────────────────────────────────────────

def print_report(areas: dict, connections: dict, details: dict):
    print("=" * 70)
    print("INTER-AREA CONNECTION REPORT")
    print("=" * 70)

    # Sort areas: midgaard first, then alphabetical
    sorted_areas = ["midgaard"] + sorted(a for a in areas if a != "midgaard")

    # Summary table
    print(f"\n{'Area':<20} {'# Connections':>14}  Connected to")
    print("-" * 70)
    for area in sorted_areas:
        if area not in areas:
            continue
        conns = sorted(connections.get(area, set()))
        if not conns:
            print(f"  {area:<20} {'0':>12}  (isolated)")
        else:
            first = True
            for i, dest in enumerate(conns):
                if first:
                    print(f"  {area:<20} {len(conns):>12}  {dest}")
                    first = False
                else:
                    print(f"  {'':20} {'':12}  {dest}")

    # Detailed edge list
    print("\n" + "=" * 70)
    print("DETAILED CONNECTIONS (area → area : room --dir--> room)")
    print("=" * 70)
    for area in sorted_areas:
        if area not in areas:
            continue
        conns = sorted(connections.get(area, set()))
        if not conns:
            continue
        print(f"\n  [{areas[area]['name']}]  ({area})")
        for dest in conns:
            hops = details.get((area, dest), [])
            # Deduplicate: keep unique (src_room, dir) pairs
            seen = set()
            unique = []
            for h in hops:
                key = (h[0], h[1])
                if key not in seen:
                    seen.add(key)
                    unique.append(h)
            hop_str = ", ".join(f"{s} --{d}--> {t}" for s, d, t in unique[:4])
            suffix = f" (+{len(unique)-4} more)" if len(unique) > 4 else ""
            dest_name = areas.get(dest, {}).get("name", dest)
            print(f"    → {dest_name:<25} via: {hop_str}{suffix}")

    # Reverse index: unreferenced areas
    all_areas = set(areas.keys())
    mentioned_as_dest = set()
    for dests in connections.values():
        mentioned_as_dest |= dests
    unreachable = all_areas - set(connections.keys()) - mentioned_as_dest
    if unreachable:
        print("\n" + "=" * 70)
        print(f"ISOLATED AREAS (no cross-area exits found): {len(unreachable)}")
        for a in sorted(unreachable):
            print(f"  {a}  ({areas[a]['name']})")

    # Degree summary
    print("\n" + "=" * 70)
    print("CONNECTIVITY DEGREE SUMMARY")
    print("=" * 70)
    by_degree = sorted(areas.keys(), key=lambda a: len(connections.get(a, set())), reverse=True)
    for area in by_degree:
        deg = len(connections.get(area, set()))
        bar = "█" * deg
        print(f"  {area:<20} {deg:>3}  {bar}")


def emit_dot(areas: dict, connections: dict, output_path: str = "maps/area_graph.dot"):
    """Write a Graphviz .dot file for the connection graph."""
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    # Collect all bidirectional edges (undirected graph)
    edges: set[tuple[str, str]] = set()
    for src, dests in connections.items():
        for dst in dests:
            edge = tuple(sorted([src, dst]))
            edges.add(edge)

    with open(output_path, "w", encoding="utf-8") as f:
        f.write("graph TassMUD {\n")
        f.write('    layout=neato;\n')
        f.write('    overlap=false;\n')
        f.write('    splines=true;\n')
        f.write('    node [shape=box, style=filled, fillcolor=lightyellow, fontname="Courier"];\n')
        # Midgaard special style
        f.write('    midgaard [fillcolor=gold, style="filled,bold"];\n')
        for src, dst in sorted(edges):
            src_label = areas.get(src, {}).get("name", src)
            dst_label = areas.get(dst, {}).get("name", dst)
            f.write(f'    "{src_label}" -- "{dst_label}";\n')
        f.write("}\n")
    print(f"\nGraphviz .dot file written to {output_path}")
    print("Render with:  dot -Tpng maps/area_graph.dot -o maps/area_graph.png")


# ─────────────────────────────────────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    emit_graphviz = "--dot" in sys.argv

    areas = load_areas()
    print(f"Loaded {len(areas)} areas.")

    connections, details = build_connection_graph(areas)
    print_report(areas, connections, details)

    if emit_graphviz:
        emit_dot(areas, connections)
