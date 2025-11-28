**Project Summary**

TassMUD is a Java 17 Maven project: a lightweight MUD server used for development and testing. It runs as a single JVM process, exposes a telnet-like interface through `Server`/`ClientHandler`, and persists game state to an embedded H2 file database.

Key facts for LLM agents:

- **Build**: `mvn -DskipTests package` (produces a shaded jar `target/tass-mud-0.1.0-shaded.jar`).
- **Run (dev)**: use the included PowerShell script `target\scripts\restart-mud.ps1` which stops running Java processes, rebuilds, and launches the jar.
- **DB**: H2 file-backed DB at `./data/tassmud` (files: `data/tassmud.mv.db`, `data/tassmud.trace.db`). DAOs use URL: `jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1`.
- **Important**: H2 file locking prevents multiple processes from opening the DB simultaneously. Stop the running server before running other tools that open the DB.

Where to look (important files & folders)

- `src/main/java/com/example/tassmud/` — Java sources.
  - `Server.java`, `ClientHandler.java` — server lifecycle and per-connection command dispatch.
  - `CommandParser.java` — maps user input to commands and supports prefix-based resolution.
  - `CharacterDAO.java`, `ItemDAO.java` — persistence; `ensureTables()` performs CREATE/ALTER migrations at startup.
  - `HelpManager.java`, `HelpPage.java` — help YAML loader and lookup.
  - `tools/SchemaInspector.java` — helper that instantiates DAOs and prints INFORMATION_SCHEMA rows (useful for debugging migrations).
- `src/main/resources/data/` — seed CSV/YAML data: `areas.csv`, `rooms.csv`, `items.yaml` (templates), `skills.csv`, `spells.csv`.
- `src/main/resources/help/` — YAML help pages (packaged into shaded JAR).
- `target/scripts/restart-mud.ps1` — development restart script (copied from `src/main/java/.../scripts`).

Runtime / Behavior Notes

- DAOs are responsible for creating tables and running additive migrations with `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`. This is best-effort and logs migration messages during startup (e.g. "Migration: ensured column item_template.traits").
- `CharacterDAO` and `ItemDAO` now point at the same file-backed H2 DB so the server state is persistent across restarts.
- To avoid the H2 "connection aborted" or lock errors, ensure the server is stopped before running any external tool that opens the DB (for example, the `SchemaInspector` tool or manual `java -cp ...` runs).

Common developer tasks & commands

- Build the shaded jar

```powershell
mvn -DskipTests package
```

- Restart the server (stops any java processes, builds and starts the jar)

```powershell
powershell -ExecutionPolicy Bypass -File .\target\scripts\restart-mud.ps1
```

- Run the in-repo SchemaInspector (prints data dir, runs DAO migrations, lists tables/columns)

```powershell
java -cp .\target\tass-mud-0.1.0-shaded.jar com.example.tassmud.tools.SchemaInspector
```

- In-game GM debug commands (available via the server console / telnet client):
  - `dbinfo` — GM-only schema listing (implemented in `ClientHandler`).
  - `save` — saves character mutable state to DB.

Troubleshooting tips for agents

- If you see H2-related NoClassDefFoundError or socket connection aborted errors when running external tools, it's usually due to:
  - Running a second process against the same file-backed DB while the server is running (stop the server first), or
  - A classpath mismatch when running a non-shaded tools or different H2 versions (use the shaded jar so the H2 classes match the server runtime).
- To verify migrations ran, look for migration log lines in server stdout (lines beginning with `Migration: ensured ...`). If those are missing, ensure DAOs are instantiated during server startup or call them explicitly in a small in-process helper like `SchemaInspector`.

Notes about recent design decisions (so LLMs understand context)

- Item model evolution: templates moved from flat CSV to structured YAML/JSON (`items.yaml`) stored in `item_template.template_json`. Templates carry `traits`/`keywords`. `ItemDAO` stores `template_json` in a CLOB column.
- Item instances: `item_instance` rows include `location_room_id`, `owner_character_id`, and `container_instance_id` (a union of possible locations). `createInstance` has overloads to create instances in containers. `moveInstanceTo*` helpers update the proper location column and clear others.
- Equipment: `character_equipment` table maps `slot_id` → `item_instance_id`. `EquipmentSlot` enum defines numeric slot ids used by DAOs.
- Help pages: included in shaded JAR; `HelpManager` loads them from the classpath.

If you want, I can also:
- Add a short README section (`docs/agents.md`) with runnable examples (I already added this file), or
- Add more debug commands to the running server (e.g., a `schema-dump` command that prints INFORMATION_SCHEMA output without stopping the server).

Contact points in code for common edits

- Adding commands: `ClientHandler.java` + `CommandParser.java`.
- DB schema changes: `CharacterDAO.java` and `ItemDAO.java` (update `ensureTables()` and add additive `ALTER TABLE` migrations).
- Seed data: `src/main/resources/data/*` and loaders in `DataLoader.java`.

End of agents.md