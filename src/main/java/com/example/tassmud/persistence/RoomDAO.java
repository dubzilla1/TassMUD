package com.example.tassmud.persistence;

import com.example.tassmud.model.Area;
import com.example.tassmud.model.Door;
import com.example.tassmud.model.Room;
import com.example.tassmud.model.RoomFlag;
import com.example.tassmud.model.SectorType;

import java.sql.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DAO for room, area, door, room_extra, and room_flag tables.
 * Extracted from CharacterDAO to separate spatial/world concerns from character data.
 */
public class RoomDAO {

    private static final Logger logger = LoggerFactory.getLogger(RoomDAO.class);

    private static final String URL = System.getProperty("tassmud.db.url",
            "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1");
    public RoomDAO() {
        MigrationManager.ensureMigration("RoomDAO", this::ensureTable);
    }

    // ========================== Schema ==========================

    public void ensureTable() {
        // Area table
        try (Connection c = TransactionManager.getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS area (" +
                    "id IDENTITY PRIMARY KEY, " +
                    "name VARCHAR(200) UNIQUE NOT NULL, " +
                    "description VARCHAR(2048) DEFAULT '' " +
                    ")");
            s.execute("ALTER TABLE area ADD COLUMN IF NOT EXISTS sector_type VARCHAR(50) DEFAULT 'FIELD'");
            logger.debug("Migration: ensured column area.sector_type");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create area table", e);
        }

        // Room table with explicit exits (nullable room IDs)
        try (Connection c = TransactionManager.getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS room (" +
                    "id IDENTITY PRIMARY KEY, " +
                    "area_id INT NOT NULL, " +
                    "name VARCHAR(200) NOT NULL, " +
                    "short_desc VARCHAR(512) DEFAULT '', " +
                    "long_desc VARCHAR(2048) DEFAULT '', " +
                    "exit_n INT, " +
                    "exit_e INT, " +
                    "exit_s INT, " +
                    "exit_w INT, " +
                    "exit_u INT, " +
                    "exit_d INT " +
                    ")");
            s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS area_id INT NOT NULL");
            s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS name VARCHAR(200) NOT NULL");
            s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS short_desc VARCHAR(512) DEFAULT ''");
            s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS long_desc VARCHAR(2048) DEFAULT ''");
            s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS exit_n INT");
            s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS exit_e INT");
            s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS exit_s INT");
            s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS exit_w INT");
            s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS exit_u INT");
            s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS exit_d INT");
            s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS move_cost INT");
            logger.debug("Migration: ensured column room.move_cost");

            // Door table for exit metadata (open/closed/locked/hidden/blocked)
            s.execute("CREATE TABLE IF NOT EXISTS door (" +
                      "from_room_id INT NOT NULL, " +
                      "direction VARCHAR(16) NOT NULL, " +
                      "to_room_id INT, " +
                      "state VARCHAR(32) DEFAULT 'OPEN', " +
                      "locked BOOLEAN DEFAULT FALSE, " +
                      "hidden BOOLEAN DEFAULT FALSE, " +
                      "blocked BOOLEAN DEFAULT FALSE, " +
                      "key_item_id INT, " +
                      "description VARCHAR(2048) DEFAULT '', " +
                      "PRIMARY KEY (from_room_id, direction) " +
                      ")");
            s.execute("ALTER TABLE door ADD COLUMN IF NOT EXISTS state VARCHAR(32) DEFAULT 'OPEN'");
            s.execute("ALTER TABLE door ADD COLUMN IF NOT EXISTS locked BOOLEAN DEFAULT FALSE");
            s.execute("ALTER TABLE door ADD COLUMN IF NOT EXISTS hidden BOOLEAN DEFAULT FALSE");
            s.execute("ALTER TABLE door ADD COLUMN IF NOT EXISTS blocked BOOLEAN DEFAULT FALSE");
            s.execute("ALTER TABLE door ADD COLUMN IF NOT EXISTS description VARCHAR(2048) DEFAULT ''");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create room table", e);
        }

        // Room extras table: key/value textual extras for rooms (e.g., plaques, signs)
        try (Connection c = TransactionManager.getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS room_extra (room_id INT NOT NULL, k VARCHAR(200) NOT NULL, v VARCHAR(2048), PRIMARY KEY(room_id, k))");
            s.execute("ALTER TABLE room_extra ADD COLUMN IF NOT EXISTS v VARCHAR(2048)");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create room_extra table", e);
        }

        // Room flags table (maps room_id -> flag key)
        try (Connection c = TransactionManager.getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS room_flag (" +
                    "room_id INT NOT NULL, " +
                    "flag VARCHAR(50) NOT NULL, " +
                    "PRIMARY KEY (room_id, flag)" +
                    ")");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create room_flag table", e);
        }
    }

    // ========================== Area Methods ==========================

    public int addArea(String name, String description) {
        String sql = "INSERT INTO area (name, description) VALUES (?, ?)";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, description);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            logger.warn("[RoomDAO] addArea (auto id) failed for name={}: {}", name, e.getMessage(), e);
            return -1;
        }
        return -1;
    }

    public int addAreaWithId(int id, String name, String description) {
        return addAreaWithId(id, name, description, SectorType.FIELD);
    }

    public int addAreaWithId(int id, String name, String description, SectorType sectorType) {
        String sql = "MERGE INTO area (id, name, description, sector_type) KEY(id) VALUES (?, ?, ?, ?)";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setString(2, name);
            ps.setString(3, description == null ? "" : description);
            ps.setString(4, sectorType != null ? sectorType.name() : "FIELD");
            ps.executeUpdate();
            return id;
        } catch (SQLException e) {
            // If MERGE failed, try a direct INSERT with explicit id as a fallback
            String insertSql = "INSERT INTO area (id, name, description, sector_type) VALUES (?, ?, ?, ?)";
            try (Connection c2 = TransactionManager.getConnection();
                 PreparedStatement psIns = c2.prepareStatement(insertSql)) {
                psIns.setInt(1, id);
                psIns.setString(2, name);
                psIns.setString(3, description == null ? "" : description);
                psIns.setString(4, sectorType != null ? sectorType.name() : "FIELD");
                psIns.executeUpdate();
                return id;
            } catch (SQLException e2) {
                // fallback: try to find by name
                String sql2 = "SELECT id FROM area WHERE name = ?";
                try (Connection c3 = TransactionManager.getConnection();
                     PreparedStatement ps2 = c3.prepareStatement(sql2)) {
                    ps2.setString(1, name);
                    try (ResultSet rs = ps2.executeQuery()) {
                        if (rs.next()) return rs.getInt(1);
                    }
                } catch (SQLException ignored) {}
                return -1;
            }
        }
    }

    public Area getAreaById(int id) {
        String sql = "SELECT id, name, description, sector_type FROM area WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String sectorStr = rs.getString("sector_type");
                    SectorType sectorType = SectorType.fromString(sectorStr);
                    return new Area(rs.getInt("id"), rs.getString("name"), rs.getString("description"), sectorType);
                }
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    // ========================== Room Methods ==========================

    public int addRoom(int areaId, String name, String shortDesc, String longDesc,
                       Integer exitN, Integer exitE, Integer exitS, Integer exitW, Integer exitU, Integer exitD) {
        String sql = "INSERT INTO room (area_id, name, short_desc, long_desc, exit_n, exit_e, exit_s, exit_w, exit_u, exit_d) VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, areaId);
            ps.setString(2, name);
            ps.setString(3, shortDesc == null ? "" : shortDesc);
            ps.setString(4, longDesc == null ? "" : longDesc);
            if (exitN == null) ps.setNull(5, Types.INTEGER); else ps.setInt(5, exitN);
            if (exitE == null) ps.setNull(6, Types.INTEGER); else ps.setInt(6, exitE);
            if (exitS == null) ps.setNull(7, Types.INTEGER); else ps.setInt(7, exitS);
            if (exitW == null) ps.setNull(8, Types.INTEGER); else ps.setInt(8, exitW);
            if (exitU == null) ps.setNull(9, Types.INTEGER); else ps.setInt(9, exitU);
            if (exitD == null) ps.setNull(10, Types.INTEGER); else ps.setInt(10, exitD);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            logger.warn("[RoomDAO] addRoom (auto id) failed for areaId={} name={}: {}", areaId, name, e.getMessage(), e);
            return -1;
        }
        return -1;
    }

    public int addRoomWithId(int id, int areaId, String name, String shortDesc, String longDesc,
                             Integer exitN, Integer exitE, Integer exitS, Integer exitW, Integer exitU, Integer exitD) {
        String sql = "MERGE INTO room (id, area_id, name, short_desc, long_desc, exit_n, exit_e, exit_s, exit_w, exit_u, exit_d) KEY(id) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setInt(2, areaId);
            ps.setString(3, name);
            ps.setString(4, shortDesc == null ? "" : shortDesc);
            ps.setString(5, longDesc == null ? "" : longDesc);
            if (exitN == null) ps.setNull(6, Types.INTEGER); else ps.setInt(6, exitN);
            if (exitE == null) ps.setNull(7, Types.INTEGER); else ps.setInt(7, exitE);
            if (exitS == null) ps.setNull(8, Types.INTEGER); else ps.setInt(8, exitS);
            if (exitW == null) ps.setNull(9, Types.INTEGER); else ps.setInt(9, exitW);
            if (exitU == null) ps.setNull(10, Types.INTEGER); else ps.setInt(10, exitU);
            if (exitD == null) ps.setNull(11, Types.INTEGER); else ps.setInt(11, exitD);
            ps.executeUpdate();
            return id;
        } catch (SQLException e) {
            logger.warn("[RoomDAO] addRoomWithId failed for id={}: {}", id, e.getMessage(), e);
            return -1;
        }
    }

    public Room getRoomById(int id) {
        String sql = "SELECT id, area_id, name, short_desc, long_desc, exit_n, exit_e, exit_s, exit_w, exit_u, exit_d, move_cost FROM room WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Integer exitN = rs.getObject("exit_n") == null ? null : rs.getInt("exit_n");
                    Integer exitE = rs.getObject("exit_e") == null ? null : rs.getInt("exit_e");
                    Integer exitS = rs.getObject("exit_s") == null ? null : rs.getInt("exit_s");
                    Integer exitW = rs.getObject("exit_w") == null ? null : rs.getInt("exit_w");
                    Integer exitU = rs.getObject("exit_u") == null ? null : rs.getInt("exit_u");
                    Integer exitD = rs.getObject("exit_d") == null ? null : rs.getInt("exit_d");
                    Integer moveCost = rs.getObject("move_cost") == null ? null : rs.getInt("move_cost");
                    return new Room(rs.getInt("id"), rs.getInt("area_id"), rs.getString("name"),
                            rs.getString("short_desc"), rs.getString("long_desc"),
                            exitN, exitE, exitS, exitW, exitU, exitD, moveCost);
                }
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    /**
     * Get the movement cost for entering a room.
     * Uses the room's custom move_cost if set, otherwise falls back to the area's sector type.
     * @return movement cost in movement points
     */
    public int getMoveCostForRoom(int roomId) {
        Room room = getRoomById(roomId);
        if (room == null) return 1;

        if (room.hasCustomMoveCost()) {
            return room.getMoveCost();
        }

        Area area = getAreaById(room.getAreaId());
        if (area == null) return 1;

        return area.getMoveCost();
    }

    public boolean updateRoomExits(int roomId, Integer exitN, Integer exitE, Integer exitS, Integer exitW, Integer exitU, Integer exitD) {
        String sql = "UPDATE room SET exit_n = ?, exit_e = ?, exit_s = ?, exit_w = ?, exit_u = ?, exit_d = ? WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (exitN == null) ps.setNull(1, Types.INTEGER); else ps.setInt(1, exitN);
            if (exitE == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, exitE);
            if (exitS == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, exitS);
            if (exitW == null) ps.setNull(4, Types.INTEGER); else ps.setInt(4, exitW);
            if (exitU == null) ps.setNull(5, Types.INTEGER); else ps.setInt(5, exitU);
            if (exitD == null) ps.setNull(6, Types.INTEGER); else ps.setInt(6, exitD);
            ps.setInt(7, roomId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Get the id of any room in the database (used for fallback room assignment).
     */
    public int getAnyRoomId() {
        String sql = "SELECT id FROM room LIMIT 1";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt("id");
        } catch (SQLException ignored) {}
        return -1;
    }

    // ========================== Door Methods ==========================

    /**
     * Insert or update a door record for an exit. Uses MERGE so it's idempotent.
     */
    public boolean upsertDoor(int fromRoomId, String direction, Integer toRoomId, String state,
                              boolean locked, boolean hidden, boolean blocked, Integer keyItemId, String description) {
        String sql = "MERGE INTO door (from_room_id, direction, to_room_id, state, locked, hidden, blocked, key_item_id, description) KEY(from_room_id, direction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, fromRoomId);
            ps.setString(2, direction == null ? "" : direction.toLowerCase());
            if (toRoomId == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, toRoomId);
            ps.setString(4, state == null ? "OPEN" : state);
            ps.setBoolean(5, locked);
            ps.setBoolean(6, hidden);
            ps.setBoolean(7, blocked);
            if (keyItemId == null) ps.setNull(8, Types.INTEGER); else ps.setInt(8, keyItemId);
            if (description == null) ps.setString(9, ""); else ps.setString(9, description);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.warn("Failed to upsert door for room {} dir {}: {}", fromRoomId, direction, e.getMessage());
            return false;
        }
    }

    public List<Door> getDoorsForRoom(int fromRoomId) {
        List<Door> out = new ArrayList<>();
        String sql = "SELECT direction, to_room_id, state, locked, hidden, blocked, key_item_id FROM door WHERE from_room_id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, fromRoomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String dir = rs.getString("direction");
                    Integer to = rs.getObject("to_room_id") == null ? null : rs.getInt("to_room_id");
                    String state = rs.getString("state");
                    boolean locked = rs.getBoolean("locked");
                    boolean hidden = rs.getBoolean("hidden");
                    boolean blocked = rs.getBoolean("blocked");
                    Integer keyId = rs.getObject("key_item_id") == null ? null : rs.getInt("key_item_id");
                    String desc = "";
                    try { desc = rs.getString("description"); } catch (Exception ignored) {}
                    out.add(new Door(fromRoomId, dir, to, state, locked, hidden, blocked, keyId, desc));
                }
            }
        } catch (SQLException e) {
            // return what we have
        }
        return out;
    }

    public Door getDoor(int fromRoomId, String direction) {
        String sql = "SELECT direction, to_room_id, state, locked, hidden, blocked, key_item_id, description FROM door WHERE from_room_id = ? AND direction = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, fromRoomId);
            ps.setString(2, direction == null ? "" : direction.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Integer to = rs.getObject("to_room_id") == null ? null : rs.getInt("to_room_id");
                    String state = rs.getString("state");
                    boolean locked = rs.getBoolean("locked");
                    boolean hidden = rs.getBoolean("hidden");
                    boolean blocked = rs.getBoolean("blocked");
                    Integer keyId = rs.getObject("key_item_id") == null ? null : rs.getInt("key_item_id");
                    String desc = "";
                    try { desc = rs.getString("description"); } catch (Exception ignored) {}
                    return new Door(fromRoomId, direction, to, state, locked, hidden, blocked, keyId, desc);
                }
            }
        } catch (SQLException e) {
            // ignore
        }
        return null;
    }

    // ========================== Room Extras ==========================

    public boolean upsertRoomExtra(int roomId, String key, String value) {
        String sql = "MERGE INTO room_extra (room_id, k, v) KEY(room_id, k) VALUES (?, ?, ?)";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            ps.setString(2, key == null ? "" : key);
            ps.setString(3, value == null ? "" : value);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public Map<String, String> getRoomExtras(int roomId) {
        Map<String, String> out = new HashMap<>();
        String sql = "SELECT k, v FROM room_extra WHERE room_id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("k"), rs.getString("v"));
                }
            }
        } catch (SQLException e) {
            // ignore
        }
        return out;
    }

    public String getRoomExtra(int roomId, String key) {
        String sql = "SELECT v FROM room_extra WHERE room_id = ? AND k = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            ps.setString(2, key == null ? "" : key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("v");
            }
        } catch (SQLException e) {
            // ignore
        }
        return null;
    }

    // ========================== Room Flags ==========================

    /**
     * Add a flag to a room. Uses MERGE so it's idempotent.
     */
    public boolean addRoomFlag(int roomId, RoomFlag flag) {
        if (flag == null) return false;
        return addRoomFlag(roomId, flag.getKey());
    }

    /**
     * Add a flag to a room by key string. Uses MERGE so it's idempotent.
     */
    public boolean addRoomFlag(int roomId, String flagKey) {
        if (flagKey == null || flagKey.trim().isEmpty()) return false;
        String sql = "MERGE INTO room_flag (room_id, flag) KEY(room_id, flag) VALUES (?, ?)";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            ps.setString(2, flagKey.toLowerCase().trim());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.warn("Failed to add room flag {} to room {}: {}", flagKey, roomId, e.getMessage());
            return false;
        }
    }

    /** Remove a flag from a room. */
    public boolean removeRoomFlag(int roomId, RoomFlag flag) {
        if (flag == null) return false;
        return removeRoomFlag(roomId, flag.getKey());
    }

    /** Remove a flag from a room by key string. */
    public boolean removeRoomFlag(int roomId, String flagKey) {
        if (flagKey == null || flagKey.trim().isEmpty()) return false;
        String sql = "DELETE FROM room_flag WHERE room_id = ? AND flag = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            ps.setString(2, flagKey.toLowerCase().trim());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.warn("Failed to remove room flag {} from room {}: {}", flagKey, roomId, e.getMessage());
            return false;
        }
    }

    /** Check if a room has a specific flag. */
    public boolean hasRoomFlag(int roomId, RoomFlag flag) {
        if (flag == null) return false;
        return hasRoomFlag(roomId, flag.getKey());
    }

    /** Check if a room has a specific flag by key string. */
    public boolean hasRoomFlag(int roomId, String flagKey) {
        if (flagKey == null || flagKey.trim().isEmpty()) return false;
        String sql = "SELECT 1 FROM room_flag WHERE room_id = ? AND flag = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            ps.setString(2, flagKey.toLowerCase().trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /** Get all flags for a room. */
    public Set<RoomFlag> getRoomFlags(int roomId) {
        Set<RoomFlag> flags = EnumSet.noneOf(RoomFlag.class);
        String sql = "SELECT flag FROM room_flag WHERE room_id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString("flag");
                    RoomFlag flag = RoomFlag.fromKey(key);
                    if (flag != null) {
                        flags.add(flag);
                    }
                }
            }
        } catch (SQLException e) {
            // return what we have
        }
        return flags;
    }

    /**
     * Set all flags for a room (replaces existing flags).
     */
    public boolean setRoomFlags(int roomId, Set<RoomFlag> flags) {
        try (Connection c = TransactionManager.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM room_flag WHERE room_id = ?")) {
                ps.setInt(1, roomId);
                ps.executeUpdate();
            }
            if (flags != null && !flags.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO room_flag (room_id, flag) VALUES (?, ?)")) {
                    for (RoomFlag flag : flags) {
                        ps.setInt(1, roomId);
                        ps.setString(2, flag.getKey());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            return true;
        } catch (SQLException e) {
            logger.warn("Failed to set room flags for room {}: {}", roomId, e.getMessage());
            return false;
        }
    }

    // ========================== Convenience Flag Checks ==========================

    /** Check if a room is a SAFE room (no combat allowed). */
    public boolean isRoomSafe(int roomId) {
        return hasRoomFlag(roomId, RoomFlag.SAFE);
    }

    /** Check if a room is a PRISON room (no exit except GM teleport). */
    public boolean isRoomPrison(int roomId) {
        return hasRoomFlag(roomId, RoomFlag.PRISON);
    }

    /** Check if a room has NO_MOB flag (mobs cannot enter by normal movement). */
    public boolean isRoomNoMob(int roomId) {
        return hasRoomFlag(roomId, RoomFlag.NO_MOB);
    }

    /** Check if a room is PRIVATE (only one non-GM PC allowed). */
    public boolean isRoomPrivate(int roomId) {
        return hasRoomFlag(roomId, RoomFlag.PRIVATE);
    }

    /** Check if a room has NO_RECALL flag. */
    public boolean isRoomNoRecall(int roomId) {
        return hasRoomFlag(roomId, RoomFlag.NO_RECALL);
    }

    /** Check if a room is DARK (requires light source). */
    public boolean isRoomDark(int roomId) {
        return hasRoomFlag(roomId, RoomFlag.DARK);
    }
}
