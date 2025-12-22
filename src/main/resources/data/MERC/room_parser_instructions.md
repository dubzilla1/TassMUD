Purpose
-------
Small instruction reference for converting MERC `#ROOMS` entries into TASSMUD `rooms.yaml` + `doors` blocks. Intended for LLMs or small tools performing area conversions.

Key MERC inputs
---------------
- Room header: `#<vnum>` — numeric id of the room.
- Name (string terminated by `~`).
- Long description (tilde-terminated string).
- `<area:number> <room-flags:number> <sector-type:number>` — area is ignored by MERC loader; `room-flags` is a numeric bitvector; `sector-type` is a numeric code.
- Optional `D <door:number>` blocks (0..5): each contains `description`, `keywords`, `locks` (bit/number), `key` (object vnum), `to_room` (vnum).
- Optional `E` extended descriptions: `keywords` + `description`.

Essential mapping rules
-----------------------
1. Room basics → `rooms.yaml` fields
   - `id`: MERC vnum
   - `name`: MERC room name
   - `long_desc`: MERC room description
   - `area_id`: mapped area id in TASSMUD (e.g., Midgaard -> 30)
   - `exits`: set from `D` blocks' `to_room` values (direction -> numeric id)
   - `extras`: map `E` sections to named extras (use primary keyword as key)

2. Sector mapping
   - Use `merc_mappings.yaml` sector_types table to map numeric `sector-type` → TASSMUD `sector_type` (or use `moveCost` override if needed per-room).

3. Room flags
   - Convert numeric bitvector using `merc_mappings.yaml.room_flags`. Preserve important bits (e.g., `ROOM_NO_MOB`, `ROOM_INDOORS`, `ROOM_SAFE`) as a `legacy_flags` list in YAML or map to TASSMUD concepts if available.

4. Doors / D-block → `doors` block
   - Direction mapping: 0:north, 1:east, 2:south, 3:west, 4:up, 5:down
   - For each `D` block create a `doors.<dir>` entry with keys:
       - `to`: numeric room id or room key
       - `description`: the D-block description string
       - `keywords`: the D-block keywords string
       - `state`: map EX_* bits: if `EX_CLOSED` set `CLOSED` (or `LOCKED` + `locked=true` for EX_LOCKED); otherwise `OPEN`.
       - `locked`: true if `EX_LOCKED` set
       - `blocked`: true if `EX_PICKPROOF` set (or treat as special lock)
       - `key`: map MERC key vnum → TASSMUD item template id (needs object-mapping table)

5. Reciprocal doors
   - Ensure the destination room has the opposite-direction `doors` entry (create placeholder if missing). Conversion should insert both sides to preserve lock/state symmetry.

6. Resets / spawns / objects
   - `#RESETS` place mobiles/objects in rooms. Convert resets into `spawns` entries in the room YAML. Map MERC mob/object vnums to TASSMUD template IDs first.

Conversion workflow (recommended)
--------------------------------
1. Parse `#AREA` and create an `areas.yaml` entry with chosen `id` and `sector_type`.
2. For each `#ROOMS` `#vnum` entry: produce a `rooms.yaml` room object with `id`, `key`, `name`, `area_id`, `long_desc`.
3. Convert `E` sections → `extras` and `D` blocks → `doors` + `exits` in the room YAML.
4. Create or lookup mappings for object/mob vnums → template IDs and use these when populating door `key` and `spawns` entries.
5. Validate: ensure each `doors.<dir>.to` room exists or create a placeholder and add reciprocal door entries.

Example (MERC `D` block → `doors` YAML)

MERC excerpt:
```
D0
At the northern end of the temple hall is a statue and a huge altar.
~
~
0 -1 3054
```

Converted YAML snippet (inside room entry):
```yaml
doors:
  north:
    to: 3054
    description: "At the northern end of the temple hall is a statue and a huge altar."
    keywords: ""
    state: OPEN
    locked: false
    hidden: false
    blocked: false
    key: null
```

Notes / caveats
---------------
- MERC `locks` field may combine door bits and a numeric value; consult `merc.h` for exact bit values. See `src/main/resources/data/MERC/merc_mappings.yaml` for the mapping used by this project.
- Mapping object/mob vnums to TASSMUD templates is a separate step and required to preserve `key` references and spawn mappings.
- Keep `legacy_flags` in YAML until you decide whether to persist them in the DB or translate them to native TASSMUD flags.

Where to put generated output
-----------------------------
- `src/main/resources/data/<MERC_AREA>/rooms.yaml` — converted rooms and doors
- `src/main/resources/data/<MERC_AREA>/areas.yaml` — area metadata
- `src/main/resources/data/MERC/merc_mappings.yaml` — mapping reference (already present)

End
