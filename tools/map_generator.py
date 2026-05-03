#!/usr/bin/env python3
"""
map_generator.py — ASCII map generator for TassMUD MERC areas.

Usage:
  python tools/map_generator.py --area smurf
  python tools/map_generator.py --all
  python tools/map_generator.py --names --area smurf    # wider room-name cells
  python tools/map_generator.py --preview --area smurf  # print to stdout only
  python tools/map_generator.py --debug  --area smurf   # show phase details

Output: src/main/resources/data/MERC/<area>/map_1.txt  (and map_2.txt, etc.)
"""

from __future__ import annotations

import argparse
import sys
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

try:
    import yaml
except ImportError:
    print("PyYAML required: pip install pyyaml")
    sys.exit(1)

# ─────────────────────────────────────────────────────────────────────────────
# Constants
# ─────────────────────────────────────────────────────────────────────────────

MERC_DIR = (
    Path(__file__).parent.parent
    / "src" / "main" / "resources" / "data" / "MERC"
)

# direction → (Δx, Δy).  North = +Y so it appears at the TOP of the map.
DIR_VEC: dict[str, tuple[int, int]] = {
    "north": (0,  1),
    "south": (0, -1),
    "east":  (1,  0),
    "west":  (-1, 0),
}
OPPOSITE: dict[str, str] = {
    "north": "south", "south": "north",
    "east":  "west",  "west":  "east",
}
HORIZ = set(DIR_VEC)
VERT  = {"up", "down"}

H_CONN = 3   # chars in "---" horizontal connector between adjacent cells
STEP_Y = 2   # visual rows per grid Y unit  (room-row + 1 connector-row)

LEGEND = (
    "Legend: [NNN]=room  ---=corridor  |=corridor  +=crossing\n"
    "        ^=up exit   v=down exit   ->N=cross-area exit to room N"
)


# ─────────────────────────────────────────────────────────────────────────────
# Data classes
# ─────────────────────────────────────────────────────────────────────────────

@dataclass
class Room:
    id:      int
    name:    str
    area_id: int
    exits:   dict[str, int]   # direction → target room id


@dataclass
class AreaData:
    id:         int
    name:       str
    area_dir:   Path
    vnum_range: str = ""
    rooms:      dict[int, Room] = field(default_factory=dict)


# ─────────────────────────────────────────────────────────────────────────────
# Loading
# ─────────────────────────────────────────────────────────────────────────────

def load_area(area_dir: Path) -> Optional[AreaData]:
    af = area_dir / "areas.yaml"
    rf = area_dir / "rooms.yaml"
    if not af.exists() or not rf.exists():
        return None

    with af.open(encoding="utf-8") as f:
        adata = yaml.safe_load(f)
    with rf.open(encoding="utf-8") as f:
        rdata = yaml.safe_load(f)

    if not adata or not adata.get("areas"):
        return None
    if not rdata or not rdata.get("rooms"):
        return None

    arec = adata["areas"][0]
    area = AreaData(
        id=int(arec.get("id", 0)),
        name=str(arec.get("name", area_dir.name)),
        area_dir=area_dir,
        vnum_range=str(arec.get("vnum_range", "")),
    )

    for r in rdata["rooms"]:
        raw = r.get("exits") or {}
        exits: dict[str, int] = {}
        for d, t in raw.items():
            if t is not None:
                try:
                    exits[d] = int(t)
                except (ValueError, TypeError):
                    pass
        area.rooms[int(r["id"])] = Room(
            id=int(r["id"]),
            name=str(r.get("name", f"Room {r['id']}")),
            area_id=int(r.get("area_id", area.id)),
            exits=exits,
        )

    return area


# ─────────────────────────────────────────────────────────────────────────────
# Phase 1 — BFS coordinate assignment
# ─────────────────────────────────────────────────────────────────────────────

def _pick_entry(area: AreaData) -> Room:
    """Entry room = most cross-area horizontal exits; tie-break = smallest id."""
    ids = set(area.rooms)
    best: Optional[Room] = None
    best_n = -1
    for room in sorted(area.rooms.values(), key=lambda r: r.id):
        n = sum(1 for d, t in room.exits.items()
                if d in HORIZ and t not in ids)
        if n > best_n:
            best_n, best = n, room
    return best or min(area.rooms.values(), key=lambda r: r.id)


def bfs_assign(
    area:  AreaData,
    debug: bool = False,
    *,
    _seed_coord: Optional[dict[int, tuple[int,int]]] = None,
    _seed_z:     Optional[dict[int, int]]             = None,
) -> tuple[dict[int, tuple[int, int]], set[int], dict[int, int]]:
    """Unified BFS from entry room.  Returns (coord_map, conflict_ids, z_map).

    Handles horizontal exits (same z), U/D exits (z ± 1, same XY), AND
    reverse exits (rooms that only point *toward* a placed room get pulled in
    from the opposite direction).  All three are processed in a single BFS
    pass so that paths like  9301--(down)-->9302<--(south from)--9303  are
    handled correctly.

    When *_seed_coord* is provided the BFS starts from all rooms in that dict
    (used to continue exploration after Phase-2 conflict relaxation adds rooms
    that were never BFS-expanded).
    """
    ids = set(area.rooms)
    coord:  dict[int, tuple[int, int]] = {}           # room → (x, y)
    z_map:  dict[int, int]             = {}           # room → z level
    # occupancy is per z-level: two rooms may share (x,y) on different floors
    occ:    dict[int, dict[tuple[int,int], int]] = {} # z → {(x,y) → room_id}
    conflicts: set[int] = set()

    # Precompute reverse-exit map for ALL directions (horiz + UD)
    # "S has exit dir D → T" stored as reverse_exits[T] = [(S, D), ...]
    reverse_exits: dict[int, list[tuple[int, str]]] = {}
    for room in area.rooms.values():
        for d, t in room.exits.items():
            if d in HORIZ | VERT and t in ids:
                reverse_exits.setdefault(t, []).append((room.id, d))

    def _seed(rid: int, x: int, y: int, z: int) -> None:
        coord[rid] = (x, y)
        z_map[rid] = z
        occ.setdefault(z, {})[(x, y)] = rid

    if _seed_coord:
        # Continuation mode: seed from all already-placed rooms
        for rid, (x, y) in _seed_coord.items():
            z = (_seed_z or {}).get(rid, 0)
            _seed(rid, x, y, z)
        seed_rooms = list(_seed_coord.keys())
    else:
        entry = _pick_entry(area)
        _seed(entry.id, 0, 0, 0)
        seed_rooms = [entry.id]

    q:    deque[int] = deque(seed_rooms)
    seen: set[int]   = set(seed_rooms)

    def try_place(t: int, nx: int, ny: int, nz: int) -> None:
        """Assign (nx, ny) at z-level nz to room t and enqueue it."""
        if t in coord:
            return
        floor = occ.setdefault(nz, {})
        if (nx, ny) in floor:
            if debug:
                print(f"    CONFLICT: room {t} wants ({nx},{ny}) z={nz} "
                      f"but room {floor[(nx,ny)]} is there")
            conflicts.add(t)
        else:
            coord[t]  = (nx, ny)
            z_map[t]  = nz
            floor[(nx, ny)] = t
            if t not in seen:
                seen.add(t)
                q.append(t)

    while q:
        rid = q.popleft()
        cx, cy = coord[rid]
        cz     = z_map[rid]

        # ── Forward exits ──
        for d, t in area.rooms[rid].exits.items():
            if t not in ids or t in coord:
                continue
            if d in HORIZ:
                dx, dy = DIR_VEC[d]
                try_place(t, cx + dx, cy + dy, cz)
            elif d == "up":
                try_place(t, cx, cy, cz + 1)
            elif d == "down":
                try_place(t, cx, cy, cz - 1)

        # ── Reverse exits ──
        # "src has exit src_dir → rid"  ⟹  src lies in direction opposite(src_dir) from rid
        for src_id, src_dir in reverse_exits.get(rid, []):
            if src_id in coord:
                continue
            if src_dir in HORIZ:
                dx, dy = DIR_VEC[OPPOSITE[src_dir]]
                try_place(src_id, cx + dx, cy + dy, cz)
            elif src_dir == "up":
                # src has exit "up" → rid  ⟹  src is one floor below rid
                try_place(src_id, cx, cy, cz - 1)
            elif src_dir == "down":
                # src has exit "down" → rid  ⟹  src is one floor above rid
                try_place(src_id, cx, cy, cz + 1)

    return coord, conflicts, z_map


# ─────────────────────────────────────────────────────────────────────────────
# Phase 2 — relaxed placement (same axis, further steps)
# ─────────────────────────────────────────────────────────────────────────────

def _incoming(area: AreaData, target: int):
    """Yield (parent_id, direction_toward_target) for edges incident to target.

    Considers both:
    - Forward: parent has exit D→target (direction = D)
    - Reverse: target has exit D→parent (direction = opposite(D), from parent's POV)
    """
    ids = set(area.rooms)
    # Forward: some room points to target
    for room in area.rooms.values():
        if room.id not in ids:
            continue
        for d, t in room.exits.items():
            if t == target and d in HORIZ:
                yield room.id, d
    # Reverse: target points to some room → that room is on the other side
    if target in area.rooms:
        for d, t in area.rooms[target].exits.items():
            if d in HORIZ and t in ids:
                yield t, OPPOSITE[d]


def relax_conflicts(
    area:      AreaData,
    coord:     dict[int, tuple[int, int]],
    conflicts: set[int],
    debug:     bool = False,
    z_map:     Optional[dict[int, int]] = None,
) -> set[int]:
    """Try steps 2, 3, 4, -1 … along the same axis; updates coord in-place.

    If *z_map* is provided, also assigns z-levels to newly placed rooms
    (derived from their parent room's z-level) so the continuation BFS
    can seed correctly from them.
    """
    occ: dict[tuple[int, int], int] = {v: k for k, v in coord.items()}
    still: set[int] = set()

    for rid in sorted(conflicts):
        placed = False
        for parent_id, d in _incoming(area, rid):
            if parent_id not in coord:
                continue
            px, py = coord[parent_id]
            dx, dy = DIR_VEC[d]
            for step in (2, 3, 4, -1, 5, 6, 7, 8):
                cand = (px + step * dx, py + step * dy)
                if cand not in occ:
                    coord[rid] = cand
                    occ[cand] = rid
                    if z_map is not None and rid not in z_map:
                        z_map[rid] = z_map.get(parent_id, 0)
                    if debug:
                        print(f"    RELAXED room {rid} -> {cand} "
                              f"(step={step} from room {parent_id})")
                    placed = True
                    break
            if placed:
                break
        if not placed:
            still.add(rid)
            if debug:
                print(f"    UNRESOLVED room {rid}")

    return still


# ─────────────────────────────────────────────────────────────────────────────
# Phase 3 — connected-component split
# ─────────────────────────────────────────────────────────────────────────────

def find_components(
    area:  AreaData,
    coord: dict[int, tuple[int, int]],
) -> list[set[int]]:
    """Flood-fill placed rooms on internal horizontal edges → components.

    Traverses both forward exits (rid→t) and reverse exits (t has exit→rid)
    so that one-directional exits don't split a logical area into fragments.
    """
    placed = set(coord)
    # Build reverse adjacency so we can traverse both directions
    adj: dict[int, set[int]] = {rid: set() for rid in placed}
    for rid in placed:
        for d, t in area.rooms[rid].exits.items():
            if d in HORIZ and t in placed:
                adj[rid].add(t)
                adj[t].add(rid)   # add reverse edge

    seen: set[int] = set()
    components: list[set[int]] = []

    for start in sorted(placed):
        if start in seen:
            continue
        comp: set[int] = set()
        q: deque[int] = deque([start])
        seen.add(start)
        while q:
            rid = q.popleft()
            comp.add(rid)
            for t in adj[rid]:
                if t not in seen:
                    seen.add(t)
                    q.append(t)
        components.append(comp)

    return components


# ─────────────────────────────────────────────────────────────────────────────
# Renderer
# ─────────────────────────────────────────────────────────────────────────────

def _draw(grid: list[list[str]], r: int, c: int, ch: str) -> None:
    """Place a connector char; upgrade to '+' when H and V connectors cross."""
    if not (0 <= r < len(grid) and 0 <= c < len(grid[0])):
        return
    cur = grid[r][c]
    if cur == " ":
        grid[r][c] = ch
    elif (cur == "-" and ch == "|") or (cur == "|" and ch == "-"):
        grid[r][c] = "+"
    # else: keep existing (already "+", room char, etc.)


def render_submap(
    area:      AreaData,
    comp:      set[int],
    coord:     dict[int, tuple[int, int]],
    z_map:     dict[int, int],
    use_names: bool = False,
) -> list[str]:
    """Render one sub-map component. Returns a list of text lines."""
    ids   = set(area.rooms)
    local = {rid: coord[rid] for rid in comp if rid in coord}
    if not local:
        return []

    # ── grid metrics ──────────────────────────────────────────────────────────
    id_len = max(max(len(str(r)) for r in comp), 3)

    any_ud = any(
        any(d in VERT for d in area.rooms[r].exits)
        for r in comp
    )

    if use_names:
        cell_w = 14                  # "[Name        ]" — 12-char name + brackets
    elif any_ud:
        cell_w = id_len + 4          # "[NNN^ ]" or "[NNN^v]"
    else:
        cell_w = id_len + 2          # "[NNN]"

    step_x = cell_w + H_CONN

    xs = [x for x, y in local.values()]
    ys = [y for x, y in local.values()]
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)
    width  = max_x - min_x + 1
    height = max_y - min_y + 1

    n_cols = width  * step_x - H_CONN + 40   # +40 margin for cross-area labels
    n_rows = max(1, height * STEP_Y - (STEP_Y - 1))

    grid: list[list[str]] = [[" "] * n_cols for _ in range(n_rows)]

    def gx(x: int) -> int:
        return (x - min_x) * step_x

    def gy(y: int) -> int:
        return (max_y - y) * STEP_Y   # flip Y: north is up

    # ── cell string ───────────────────────────────────────────────────────────
    def cell_str(rid: int) -> str:
        room = area.rooms[rid]
        if use_names:
            return f"[{room.name[:12].ljust(12)}]"
        u  = any(d == "up"   for d in room.exits)
        dv = any(d == "down" for d in room.exits)
        rid_s = str(rid).rjust(id_len)
        if any_ud:
            if u and dv:
                marker = "^v"
            elif u:
                marker = "^ "
            elif dv:
                marker = "v "
            else:
                marker = "  "
            return f"[{rid_s}{marker}]"
        return f"[{rid_s}]"

    # ── place cells ───────────────────────────────────────────────────────────
    # Also record which (row, col) positions belong to a room cell so that
    # connector drawing never overwrites them.
    room_cells: set[tuple[int, int]] = set()
    for rid, (x, y) in local.items():
        s   = cell_str(rid)
        row = gy(y)
        col = gx(x)
        for i, ch in enumerate(s):
            if 0 <= col + i < n_cols:
                grid[row][col + i] = ch
                room_cells.add((row, col + i))

    def draw_safe(r: int, c: int, ch: str) -> None:
        """Like _draw but will not overwrite a room-cell position."""
        if (r, c) in room_cells:
            return
        _draw(grid, r, c, ch)

    # ── connectors (draw each room-pair once) ─────────────────────────────────
    drawn: set[frozenset] = set()

    for rid, (x, y) in local.items():
        for d, t in area.rooms[rid].exits.items():
            if d not in HORIZ or t not in local:
                continue
            pair = frozenset([rid, t])
            if pair in drawn:
                continue
            drawn.add(pair)

            tx, ty = local[t]

            if ty == y:          # horizontal connector
                lx = min(x, tx)
                rx = max(x, tx)
                row = gy(y)
                for c in range(gx(lx) + cell_w, gx(rx)):
                    draw_safe(row, c, "-")

            elif tx == x:        # vertical connector
                top_y = max(y, ty)
                bot_y = min(y, ty)
                col = gx(x) + cell_w // 2
                for r in range(gy(top_y) + 1, gy(bot_y)):
                    draw_safe(r, col, "|")

            # else: diagonal (shouldn't occur with axis-only Phase 2) — skip

    # ── cross-area exit labels ────────────────────────────────────────────────
    # Collect every cross-area exit for the footer, and also render inline
    # labels for east/west exits where there is space.
    cross_exits: list[tuple[int, str, int]] = []   # (room_id, direction, target)

    for rid, (x, y) in local.items():
        room = area.rooms[rid]
        for d, t in room.exits.items():
            if d not in HORIZ or t in ids:
                continue
            cross_exits.append((rid, d, t))
            row = gy(y)
            if d == "east":
                label = f"  ->[{t}]"
                col   = gx(x) + cell_w
                # advance past any existing connector chars
                while col < n_cols and grid[row][col] in ("-", "+"):
                    col += 1
                for i, ch in enumerate(label):
                    if col + i < n_cols:
                        grid[row][col + i] = ch
            elif d == "west":
                label = f"[{t}]<-  "
                # place label just left of the cell
                col = max(0, gx(x) - len(label))
                for i, ch in enumerate(label):
                    if col + i < n_cols:
                        grid[row][col + i] = ch

    # ── trim trailing whitespace and blank lines ──────────────────────────────
    lines = ["".join(row).rstrip() for row in grid]
    while lines and not lines[-1].strip():
        lines.pop()

    return lines, cross_exits


# ─────────────────────────────────────────────────────────────────────────────
# Main pipeline
# ─────────────────────────────────────────────────────────────────────────────

def generate_maps(
    area_dir:  Path,
    use_names: bool = False,
    debug:     bool = False,
    write:     bool = True,
) -> int:
    """Process one area directory.  Returns number of sub-maps produced."""
    area = load_area(area_dir)
    if area is None:
        return 0
    if not area.rooms:
        return 0

    n = len(area.rooms)
    if debug:
        print(f"\n=== {area.name} ({n} rooms) ===")

    # Phase 1+4 — Unified BFS (horizontal + U/D + reverse exits)
    coord, conflicts, z_map = bfs_assign(area, debug)
    if debug:
        print(f"  P1: placed={len(coord)}/{n}  conflicts={len(conflicts)}")

    # Iterative Phase 2 + continuation BFS until convergence.
    # Each round: relax remaining conflicts, then re-expand BFS from all
    # placed rooms (picking up exits from Phase-2-placed rooms that were
    # never queued in the previous pass).
    round_n = 0
    while conflicts:
        round_n += 1
        prev_placed = len(coord)
        conflicts = relax_conflicts(area, coord, conflicts, debug, z_map=z_map)
        if debug and round_n == 1:
            print(f"  P2: unresolved={len(conflicts)}")

        # Continuation BFS from all currently placed rooms
        coord, new_conflicts, z_map = bfs_assign(
            area, debug=False, _seed_coord=coord, _seed_z=z_map
        )
        conflicts |= new_conflicts
        newly_placed = len(coord) - prev_placed
        if debug:
            label = "P1b" if round_n == 1 else f"P1b-r{round_n}"
            print(f"  {label}: placed={len(coord)}/{n}  "
                  f"new_conflicts={len(new_conflicts)}")
        if newly_placed == 0:
            break   # nothing new placed; further iterations won't help

    # Phase 3 — connected components (sees all placed rooms including U/D ones)
    components = find_components(area, coord)
    if debug:
        print(f"  P3: {len(components)} component(s)")

    # Build sub-map list: one entry per (component × z-level)
    submaps: list[tuple[set[int], int]] = []
    for comp in components:
        z_levels = sorted({z_map.get(r, 0) for r in comp})
        for z in z_levels:
            z_comp = {r for r in comp if z_map.get(r, 0) == z}
            if z_comp:
                submaps.append((z_comp, z))

    total = len(submaps)
    if debug:
        print(f"  Total sub-maps: {total}")

    # Remove stale map files before writing new ones
    if write:
        for old in sorted(area_dir.glob("map_*.txt")):
            old.unlink()

    for idx, (comp, z) in enumerate(submaps, 1):
        z_tag  = f" (Level {z:+d})" if z != 0 else ""
        header = f"=== {area.name} (Area {area.id}){z_tag}  Map {idx} of {total}"
        lines, cross_exits = render_submap(area, comp, coord, z_map, use_names)
        body   = "\n".join(lines) if lines else "(no rooms placed)"

        # Build cross-area exits footer
        cross_section = ""
        if cross_exits:
            cross_section = "\nCross-area exits:\n"
            for rid, d, t in sorted(cross_exits):
                cross_section += f"  Room {rid} -> {d} -> Room {t}\n"

        content = f"{header}\n\n{body}\n\n{LEGEND}\n{cross_section}"

        if write:
            out = area_dir / f"map_{idx}.txt"
            out.write_text(content, encoding="utf-8")
            if debug:
                print(f"  Wrote {out.name} ({len(comp)} rooms, z={z}, "
                      f"{len(cross_exits)} cross-area exits)")
        else:
            print(content)

    return total


# ─────────────────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────────────────

def main() -> None:
    ap = argparse.ArgumentParser(
        description="Generate ASCII maps for TassMUD MERC areas.")
    ap.add_argument("--area",    metavar="NAME",
                    help="Area folder name (e.g. smurf)")
    ap.add_argument("--all",     action="store_true",
                    help="Process every area under MERC_DIR")
    ap.add_argument("--names",   action="store_true",
                    help="Use room-name cells instead of room IDs")
    ap.add_argument("--debug",   action="store_true",
                    help="Print per-phase debug output")
    ap.add_argument("--preview", action="store_true",
                    help="Print maps to stdout; do not write files")
    args = ap.parse_args()

    if not MERC_DIR.exists():
        print(f"ERROR: MERC directory not found:\n  {MERC_DIR}")
        sys.exit(1)

    if args.all:
        dirs  = sorted(d for d in MERC_DIR.iterdir() if d.is_dir())
        total = 0
        for d in dirs:
            n = generate_maps(d, args.names, args.debug, not args.preview)
            if n:
                print(f"  {d.name}: {n} map(s)")
            total += n
        print(f"\nDone — {total} total map(s).")

    elif args.area:
        d = MERC_DIR / args.area
        if not d.exists():
            print(f"ERROR: area directory not found:\n  {d}")
            sys.exit(1)
        n = generate_maps(d, args.names, args.debug, not args.preview)
        if not args.preview:
            print(f"Done — {n} map(s).")

    else:
        ap.print_help()
        sys.exit(1)


if __name__ == "__main__":
    main()
