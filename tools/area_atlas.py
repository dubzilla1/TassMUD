#!/usr/bin/env python3
"""
area_atlas.py — Generate a 2D ASCII mega-map of TassMUD's area connectivity.

Areas are placed on a grid using a BFS spanning-tree layout, with Midgaard
at the center. Each area is rendered as a box containing its display name.
Edges (connections) are drawn between boxes.

Usage:
    python tools/area_atlas.py              # print to stdout
    python tools/area_atlas.py -o maps/atlas.txt  # write to file
"""

import sys
import os
import re
import yaml
import argparse
from pathlib import Path
from collections import defaultdict, deque
from typing import Optional

MERC_DIR = Path("src/main/resources/data/MERC")
DIRECTIONS = {"north", "south", "east", "west", "up", "down"}

# ─────────────────────────────────────────────────────────────────────────────
# Load area + connectivity data  (duplicated from area_connectivity.py)
# ─────────────────────────────────────────────────────────────────────────────

def load_areas():
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
            break
    return areas


def load_rooms(area_name):
    ry = MERC_DIR / area_name / "rooms.yaml"
    if not ry.exists():
        return []
    try:
        with open(ry, encoding="utf-8") as f:
            data = yaml.safe_load(f)
        return (data or {}).get("rooms", []) or []
    except Exception:
        return []


def vnum_to_area(vnum, areas):
    for name, info in areas.items():
        if info["low"] <= vnum <= info["high"]:
            return name
    return None


def build_connection_graph(areas):
    connections = defaultdict(set)
    for area_name, info in areas.items():
        rooms = load_rooms(area_name)
        my_low, my_high = info["low"], info["high"]
        for room in rooms:
            exits = room.get("exits") or {}
            if not isinstance(exits, dict):
                continue
            for direction, dest in exits.items():
                if direction not in DIRECTIONS:
                    continue
                if not isinstance(dest, int) or dest < 0:
                    continue
                if my_low <= dest <= my_high:
                    continue
                dst = vnum_to_area(dest, areas)
                if dst and dst != area_name:
                    connections[area_name].add(dst)
                    connections[dst].add(area_name)
    return connections


# ─────────────────────────────────────────────────────────────────────────────
# Layout: assign (col, row) grid positions via BFS spanning tree
# ─────────────────────────────────────────────────────────────────────────────

def bfs_layout(connections, root="midgaard"):
    """
    BFS from root. At each node we assign one of 8 directions to each neighbor,
    spreading them out. Returns dict: area_name → (col, row).
    """
    # 8 directions to try for neighbors, in preference order
    OFFSETS = [
        (0, -2),   # N
        (2,  0),   # E
        (0,  2),   # S
        (-2, 0),   # W
        (2, -2),   # NE
        (2,  2),   # SE
        (-2, 2),   # SW
        (-2,-2),   # NW
        (0, -4),   # far N
        (4,  0),   # far E
        (0,  4),   # far S
        (-4, 0),   # far W
        (4, -2), (4, 2), (-4, -2), (-4, 2),
        (2, -4), (2, 4), (-2, -4), (-2, 4),
        (4, -4), (4, 4), (-4, -4), (-4, 4),
        (6,  0), (-6, 0), (0, -6), (0, 6),
    ]

    pos = {root: (0, 0)}
    queue = deque([root])
    visited = {root}
    occupied = {(0, 0): root}

    while queue:
        node = queue.popleft()
        col, row = pos[node]
        # Sort neighbors by degree (highest-degree first) so hubs get better spots
        neighbors = sorted(connections.get(node, set()) - visited,
                           key=lambda n: -len(connections.get(n, set())))
        offset_idx = 0
        for nb in neighbors:
            if nb in visited:
                continue
            # Try offsets until we find a free cell
            placed = False
            all_offsets = OFFSETS[offset_idx:] + OFFSETS[:offset_idx]
            for off in all_offsets:
                nc = col + off[0]
                nr = row + off[1]
                if (nc, nr) not in occupied:
                    pos[nb] = (nc, nr)
                    occupied[(nc, nr)] = nb
                    visited.add(nb)
                    queue.append(nb)
                    placed = True
                    offset_idx = (OFFSETS.index(off) + 1) % len(OFFSETS)
                    break
            if not placed:
                # Fallback: spiral scan (step=2 grid)
                for radius in range(2, 30, 2):
                    found = False
                    for dc in range(-radius, radius + 1, 2):
                        for dr in range(-radius, radius + 1, 2):
                            nc, nr = col + dc, row + dr
                            if (nc, nr) not in occupied:
                                pos[nb] = (nc, nr)
                                occupied[(nc, nr)] = nb
                                visited.add(nb)
                                queue.append(nb)
                                found = True
                                break
                        if found:
                            break
                    if found:
                        break

    # Handle disconnected areas (isolated): place them far below
    all_areas = set(connections.keys())
    isolated_col = 0
    for area in sorted(all_areas - visited):
        pos[area] = (isolated_col, 20)
        isolated_col += 3

    return pos


# ─────────────────────────────────────────────────────────────────────────────
# Rendering
# ─────────────────────────────────────────────────────────────────────────────

BOX_PAD = 1   # spaces inside box on each side

def area_label(name, areas):
    """Display name for an area — uses the human-readable name from areas.yaml."""
    info = areas.get(name, {})
    label = info.get("name", name)
    # Cap length to keep boxes manageable
    if len(label) > 18:
        label = label[:17] + "…"
    return label


def make_box(label):
    """Return list of strings forming the box, and (width, height)."""
    w = len(label) + BOX_PAD * 2
    top    = "+" + "-" * w + "+"
    middle = "|" + " " * BOX_PAD + label + " " * BOX_PAD + "|"
    bottom = "+" + "-" * w + "+"
    return [top, middle, bottom]


def render_atlas(pos, connections, areas):
    """
    Render the atlas to a 2D character grid and return as a string.
    Grid cells are (col, row). Each cell is BOX_W wide and BOX_H tall.
    """
    if not pos:
        return ""

    # Compute box sizes per area
    labels = {a: area_label(a, areas) for a in pos}
    boxes  = {a: make_box(labels[a]) for a in pos}
    box_w  = {a: len(boxes[a][0]) for a in pos}
    box_h  = 3  # always 3 lines

    # Normalize grid: shift so min col/row = 0
    min_col = min(c for c, r in pos.values())
    min_row = min(r for c, r in pos.values())
    pos = {a: (c - min_col, r - min_row) for a, (c, r) in pos.items()}

    # Grid step size in pixels — wide enough for the widest box + connector gap
    max_bw = max(box_w.values())
    CELL_W = max_bw + 2   # 2 chars gap between boxes horizontally
    CELL_H = box_h + 2    # 2 lines gap between boxes vertically

    max_col = max(c for c, r in pos.values())
    max_row = max(r for c, r in pos.values())

    canvas_w = (max_col + 1) * CELL_W + max_bw + 4
    canvas_h = (max_row + 1) * CELL_H + box_h + 2

    # Build mutable 2D character canvas
    canvas = [[" "] * canvas_w for _ in range(canvas_h)]

    def put(x, y, ch):
        if 0 <= y < canvas_h and 0 <= x < canvas_w:
            canvas[y][x] = ch

    def put_str(x, y, s):
        for i, ch in enumerate(s):
            put(x + i, y, ch)

    def hline(x1, y, x2):
        for x in range(min(x1, x2), max(x1, x2) + 1):
            if (x, y) in protected:
                continue
            ch = canvas[y][x]
            if ch in (" ", "|"):
                put(x, y, "-")

    def vline(x, y1, y2):
        for y in range(min(y1, y2), max(y1, y2) + 1):
            if (x, y) in protected:
                continue
            ch = canvas[y][x]
            if ch in (" ", "-"):
                put(x, y, "|")

    # Map area → pixel center of box
    def cell_px(area):
        gc, gr = pos[area]
        bw = box_w[area]
        px = gc * CELL_W + bw // 2
        py = gr * CELL_H + 1  # row 1 of the 3-line box = middle line
        return px, py

    # Track which cells belong to a box border (protected from connector overwrites)
    protected = set()

    # Place boxes
    for area, (gc, gr) in pos.items():
        bw = box_w[area]
        px = gc * CELL_W
        py = gr * CELL_H
        for line_i, line in enumerate(boxes[area]):
            put_str(px, py + line_i, line)
            for xi in range(len(line)):
                protected.add((px + xi, py + line_i))

    # Draw edges (simple Manhattan routing: go horizontal then vertical)
    drawn = set()
    for area_a, neighbors in connections.items():
        if area_a not in pos:
            continue
        for area_b in neighbors:
            if area_b not in pos:
                continue
            edge = tuple(sorted([area_a, area_b]))
            if edge in drawn:
                continue
            drawn.add(edge)

            ax, ay = cell_px(area_a)
            bx, by = cell_px(area_b)

            # Adjust to box edge instead of center
            # horizontal connector from right/left edge of box
            ga_col, ga_row = pos[area_a]
            gb_col, gb_row = pos[area_b]
            bwa = box_w[area_a]
            bwb = box_w[area_b]

            # left edge of box A, right edge, mid row
            ax_left  = ga_col * CELL_W
            ax_right = ga_col * CELL_W + bwa - 1
            ay_mid   = ga_row * CELL_H + 1

            bx_left  = gb_col * CELL_W
            bx_right = gb_col * CELL_W + bwb - 1
            by_mid   = gb_row * CELL_H + 1

            # Pick start/end points on box edges
            if ax_right < bx_left:
                sx, sy = ax_right, ay_mid
                ex, ey = bx_left,  by_mid
            elif bx_right < ax_left:
                sx, sy = ax_left,  ay_mid
                ex, ey = bx_right, by_mid
            else:
                # vertically separated — use bottom/top of box
                ax_mid2 = ga_col * CELL_W + bwa // 2
                bx_mid2 = gb_col * CELL_W + bwb // 2
                if ay_mid < by_mid:
                    sy = ga_row * CELL_H + 2   # bottom of box A
                    ey = gb_row * CELL_H        # top of box B
                else:
                    sy = ga_row * CELL_H        # top of box A
                    ey = gb_row * CELL_H + 2    # bottom of box B
                sx, ex = ax_mid2, bx_mid2
                hline(sx, sy, ex)
                if sy != ey:
                    mid_x = (sx + ex) // 2
                    vline(mid_x, sy, ey)
                hline(mid_x, ey, ex)
                continue

            # Route: horizontal from sx → mid_x, then vertical, then horizontal to ex
            mid_x = (sx + ex) // 2
            hline(sx, sy, mid_x)
            if sy != ey:
                vline(mid_x, sy, ey)
            hline(mid_x, ey, ex)

    # Render canvas to string, stripping trailing whitespace
    lines = ["".join(row).rstrip() for row in canvas]
    # Strip leading blank lines
    while lines and not lines[0].strip():
        lines.pop(0)
    # Strip trailing blank lines
    while lines and not lines[-1].strip():
        lines.pop()
    return "\n".join(lines)


# ─────────────────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="Generate TassMUD area atlas map.")
    parser.add_argument("-o", "--output", default=None, help="Write atlas to this file (default: stdout)")
    parser.add_argument("--skip-isolated", action="store_true", default=True,
                        help="Omit isolated areas from the map (default: True)")
    args = parser.parse_args()

    areas = load_areas()
    connections = build_connection_graph(areas)

    # Remove isolated areas from layout if requested
    if args.skip_isolated:
        isolated = {a for a in areas if not connections.get(a)}
        for a in isolated:
            connections.pop(a, None)
            areas_for_layout = {k: v for k, v in areas.items() if k not in isolated}
    else:
        areas_for_layout = areas

    # Also filter out <unknown:-1> pseudo-area (shouldn't be in areas dict, but just in case)
    connections = {k: {x for x in v if x in areas} for k, v in connections.items()}

    pos = bfs_layout(connections, root="midgaard")

    # Filter pos to only areas that exist
    pos = {k: v for k, v in pos.items() if k in areas_for_layout}

    atlas = render_atlas(pos, connections, areas)

    if args.output:
        Path(args.output).parent.mkdir(parents=True, exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(atlas)
            f.write("\n")
        print(f"Atlas written to {args.output}  ({atlas.count(chr(10))+1} lines)")
    else:
        print(atlas)


if __name__ == "__main__":
    main()
