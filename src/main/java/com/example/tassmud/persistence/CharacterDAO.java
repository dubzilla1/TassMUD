package com.example.tassmud.persistence;


import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.Modifier;
import com.example.tassmud.model.Stat;
import com.example.tassmud.model.StatBlock;
import com.example.tassmud.util.PasswordUtil;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CharacterDAO {

    private static final Logger logger = LoggerFactory.getLogger(CharacterDAO.class);
    public CharacterDAO() {
        // Ensure character-related tables/migrations run only once per JVM startup
        MigrationManager.ensureMigration("CharacterDAO", this::ensureTable);
    }

    public void ensureTable() {
                // Characters table must exist before join tables that reference it
                try (Connection c = TransactionManager.getConnection();
                     Statement s = c.createStatement()) {
                    s.execute("CREATE TABLE IF NOT EXISTS characters (" +
                        "id IDENTITY PRIMARY KEY, " +
                        "name VARCHAR(100) UNIQUE NOT NULL, " +
                        "password_hash VARCHAR(512) NOT NULL, " +
                        "salt VARCHAR(512) NOT NULL, " +
                        "age INT DEFAULT 0, " +
                        "description VARCHAR(1024) DEFAULT '', " +
                        "hp_max INT DEFAULT 100, " +
                        "hp_cur INT DEFAULT 100, " +
                        "mp_max INT DEFAULT 50, " +
                        "mp_cur INT DEFAULT 50, " +
                        "mv_max INT DEFAULT 100, " +
                        "mv_cur INT DEFAULT 100, " +
                        "str INT DEFAULT 10, " +
                        "dex INT DEFAULT 10, " +
                        "con INT DEFAULT 10, " +
                        "intel INT DEFAULT 10, " +
                        "wis INT DEFAULT 10, " +
                        "cha INT DEFAULT 10, " +
                        "armor INT DEFAULT 10, " +
                        "fortitude INT DEFAULT 10, " +
                        "reflex INT DEFAULT 10, " +
                        "will INT DEFAULT 10, " +
                        "current_room INT, " +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP) ");

                    // For backwards-compatibility, add any missing columns (H2 supports IF NOT EXISTS)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS age INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS description VARCHAR(1024) DEFAULT ''");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS hp_max INT DEFAULT 100");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS hp_cur INT DEFAULT 100");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS mp_max INT DEFAULT 50");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS mp_cur INT DEFAULT 50");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS mv_max INT DEFAULT 100");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS mv_cur INT DEFAULT 100");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS str INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS dex INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS con INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS intel INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS wis INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS cha INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS armor INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS fortitude INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS reflex INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS will INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS current_room INT");
                    // Equipment bonus columns (persisted from equipped items)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS armor_equip_bonus INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS fortitude_equip_bonus INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS reflex_equip_bonus INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS will_equip_bonus INT DEFAULT 0");
                    // Current class reference (denormalized for convenience)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS current_class_id INT DEFAULT NULL");
                    // Autoflee threshold (0-100, defaults to 0)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS autoflee INT DEFAULT 0");
                    // Talent points for training abilities/skills/spells
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS talent_points INT DEFAULT 0");
                    // Trained ability score bonuses (added via talent points)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS trained_str INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS trained_dex INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS trained_con INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS trained_int INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS trained_wis INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS trained_cha INT DEFAULT 0");
                    // Gold pieces (currency)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS gold_pieces BIGINT DEFAULT 0");
                    // Autoloot and autogold flags (default true for convenience)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS autoloot BOOLEAN DEFAULT TRUE");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS autogold BOOLEAN DEFAULT TRUE");
                    // Autosac flag (auto-sacrifice empty corpses, default false - requires autoloot+autogold)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS autosac BOOLEAN DEFAULT FALSE");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS autojunk BOOLEAN DEFAULT FALSE");
                    // Autoassist flag (auto-assist group members in combat, default true)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS autoassist BOOLEAN DEFAULT TRUE");
                    // Ki resource pool for monks (current and max)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS ki_max INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS ki_cur INT DEFAULT 0");
                    // Player title (shown in who list and score)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS title VARCHAR(60) DEFAULT ''");
                    // Channel deafness flags (mute individual channels)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS deaf_chat BOOLEAN DEFAULT FALSE");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS deaf_yell BOOLEAN DEFAULT FALSE");
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to create characters table", e);
                }

        // Per-character flag table (expandable key/value toggles)
        try (Connection c = TransactionManager.getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS character_flag (" +
                    "character_id INT NOT NULL, " +
                    "k VARCHAR(200) NOT NULL, " +
                    "v VARCHAR(2000), " +
                    "PRIMARY KEY (character_id, k)" +
                    ")");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create character_flag table", e);
        }

        // Character modifiers (stat-affecting temporary/permanent effects)
        try (Connection c = TransactionManager.getConnection();
             Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS character_modifier (" +
                    "id VARCHAR(36) PRIMARY KEY, " +
                    "character_id INT NOT NULL, " +
                    "source VARCHAR(200), " +
                    "stat VARCHAR(50) NOT NULL, " +
                    "op VARCHAR(20) NOT NULL, " +
                    "val DOUBLE NOT NULL, " +
                    "expires_at BIGINT DEFAULT 0, " +
                    "priority INT DEFAULT 0 " +
                    ")");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create character_modifier table", e);
        }
    }

    // --- Character flag accessors ---
    public boolean setCharacterFlag(int characterId, String key, String value) {
        String sql = "MERGE INTO character_flag (character_id, k, v) KEY (character_id, k) VALUES (?, ?, ?)";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, key);
            ps.setString(3, value == null ? "" : value);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public String getCharacterFlag(int characterId, String key) {
        String sql = "SELECT v FROM character_flag WHERE character_id = ? AND k = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("v");
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    public boolean setCharacterFlagByName(String name, String key, String value) {
        Integer id = getCharacterIdByName(name);
        if (id == null) return false;
        return setCharacterFlag(id, key, value);
    }

    public String getCharacterFlagByName(String name, String key) {
        Integer id = getCharacterIdByName(name);
        if (id == null) return null;
        return getCharacterFlag(id, key);
    }

    public boolean isCharacterFlagTrueByName(String name, String key) {
        String v = getCharacterFlagByName(name, key);
        if (v == null) return false;
        // Accept a few common truthy values for flag checks so both '1' and 'true'
        // (or 'yes', 'on') work regardless of how they were set elsewhere.
        String tv = v.trim().toLowerCase();
        return tv.equals("1") || tv.equals("true") || tv.equals("yes") || tv.equals("on");
    }

    // Return list of columns for a given table (name case-insensitive)
    public java.util.List<String> listTableColumns(String tableName) {
        java.util.List<String> cols = new java.util.ArrayList<>();
        String sql = "SELECT COLUMN_NAME, TYPE_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tableName == null ? "" : tableName.trim().toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString("COLUMN_NAME") + " (" + rs.getString("TYPE_NAME") + ")");
                }
            }
        } catch (SQLException e) {
            // return empty list on error
        }
        return cols;
    }

    public Integer getCharacterIdByName(String name) {
        String sql = "SELECT id FROM characters WHERE name = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("id");
            }
        } catch (SQLException ignored) {}
        return null;
    }

    /**
     * Update a character's current class ID on the characters table.
     */
    public boolean updateCharacterClass(int characterId, Integer classId) {
        String sql = "UPDATE characters SET current_class_id = ? WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (classId == null) ps.setNull(1, Types.INTEGER); else ps.setInt(1, classId);
            ps.setInt(2, characterId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean updateCharacterClassByName(String name, Integer classId) {
        Integer id = getCharacterIdByName(name);
        if (id == null) return false;
        return updateCharacterClass(id, classId);
    }

    public CharacterRecord findByName(String name) {
        String sql = "SELECT name, password_hash, salt, age, description, hp_max, hp_cur, mp_max, mp_cur, mv_max, mv_cur, str, dex, con, intel, wis, cha, armor, fortitude, reflex, will, armor_equip_bonus, fortitude_equip_bonus, reflex_equip_bonus, will_equip_bonus, current_room, current_class_id, autoflee, talent_points, trained_str, trained_dex, trained_con, trained_int, trained_wis, trained_cha, gold_pieces, autoloot, autogold, autosac, autojunk, autoassist, ki_max, ki_cur, title, deaf_chat, deaf_yell FROM characters WHERE name = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                        return extractCharacterRecord(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query character", e);
        }
        return null;
    }
    
    /**
     * Find a character by their ID.
     */
    public CharacterRecord findById(int characterId) {
        String sql = "SELECT name, password_hash, salt, age, description, hp_max, hp_cur, mp_max, mp_cur, mv_max, mv_cur, str, dex, con, intel, wis, cha, armor, fortitude, reflex, will, armor_equip_bonus, fortitude_equip_bonus, reflex_equip_bonus, will_equip_bonus, current_room, current_class_id, autoflee, talent_points, trained_str, trained_dex, trained_con, trained_int, trained_wis, trained_cha, gold_pieces, autoloot, autogold, autosac, autojunk, autoassist, ki_max, ki_cur, title, deaf_chat, deaf_yell FROM characters WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return extractCharacterRecord(rs);
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to find character by ID: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Get character record by their ID (backward-compatible delegate to findById).
     */
    public CharacterRecord getCharacterById(int characterId) {
        return findById(characterId);
    }

    /**
     * Load all modifiers for a character.
     */
    public java.util.List<Modifier> getModifiersForCharacter(int characterId) {
        java.util.List<Modifier> out = new java.util.ArrayList<>();
        String sql = "SELECT id, source, stat, op, val, expires_at, priority FROM character_modifier WHERE character_id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                            java.util.UUID id = java.util.UUID.fromString(rs.getString("id"));
                            String source = rs.getString("source");
                            Stat stat = Stat.valueOf(rs.getString("stat"));
                            Modifier.Op op = Modifier.Op.valueOf(rs.getString("op"));
                            double value = rs.getDouble("val");
                            long expiresAt = rs.getLong("expires_at");
                            int priority = rs.getInt("priority");
                            Modifier m = new Modifier(id, source, stat, op, value, expiresAt, priority);
                        out.add(m);
                    } catch (IllegalArgumentException iae) {
                        // skip malformed rows
                    }
                }
            }
        } catch (SQLException e) {
            // return what we have on error
        }
        return out;
    }

    /**
     * Persist all modifiers for a character. Existing rows for the character are replaced.
     */
    public boolean saveModifiersForCharacter(int characterId, GameCharacter ch) {
        // Remove expired modifiers from the Character instance before saving
        java.util.List<Modifier> mods = ch.getAllModifiers();
        mods.removeIf(Modifier::isExpired);

        String deleteSql = "DELETE FROM character_modifier WHERE character_id = ?";
        String insertSql = "INSERT INTO character_modifier (id, character_id, source, stat, op, val, expires_at, priority) VALUES (?,?,?,?,?,?,?,?)";
        try (Connection c = TransactionManager.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement(deleteSql)) {
                del.setInt(1, characterId);
                del.executeUpdate();
            }

            try (PreparedStatement ins = c.prepareStatement(insertSql)) {
                for (Modifier m : mods) {
                    ins.setString(1, m.id().toString());
                    ins.setInt(2, characterId);
                    ins.setString(3, m.source());
                    ins.setString(4, m.stat().name());
                    ins.setString(5, m.op().name());
                    ins.setDouble(6, m.value());
                    ins.setLong(7, m.expiresAtMillis());
                    ins.setInt(8, m.priority());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            c.commit();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Persist a list of modifiers for a character. Replaces existing rows for the character.
     */
    public boolean saveModifierListForCharacter(int characterId, java.util.List<Modifier> mods) {
        if (mods == null) mods = new java.util.ArrayList<>();
        mods.removeIf(Modifier::isExpired);

        String deleteSql = "DELETE FROM character_modifier WHERE character_id = ?";
        String insertSql = "INSERT INTO character_modifier (id, character_id, source, stat, op, val, expires_at, priority) VALUES (?,?,?,?,?,?,?,?)";
        try (Connection c = TransactionManager.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement(deleteSql)) {
                del.setInt(1, characterId);
                del.executeUpdate();
            }

            try (PreparedStatement ins = c.prepareStatement(insertSql)) {
                for (Modifier m : mods) {
                    ins.setString(1, m.id().toString());
                    ins.setInt(2, characterId);
                    ins.setString(3, m.source());
                    ins.setString(4, m.stat().name());
                    ins.setString(5, m.op().name());
                    ins.setDouble(6, m.value());
                    ins.setLong(7, m.expiresAtMillis());
                    ins.setInt(8, m.priority());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            c.commit();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean createCharacter(GameCharacter ch, String passwordHashBase64, String saltBase64) {
        String sql = "INSERT INTO characters (name, password_hash, salt, age, description, hp_max, hp_cur, mp_max, mp_cur, mv_max, mv_cur, str, dex, con, intel, wis, cha, armor, fortitude, reflex, will, current_room) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ch.getName());
            ps.setString(2, passwordHashBase64);
            ps.setString(3, saltBase64);
            ps.setInt(4, ch.getAge());
            ps.setString(5, ch.getDescription());
            ps.setInt(6, ch.getHpMax());
            ps.setInt(7, ch.getHpCur());
            ps.setInt(8, ch.getMpMax());
            ps.setInt(9, ch.getMpCur());
            ps.setInt(10, ch.getMvMax());
            ps.setInt(11, ch.getMvCur());
            ps.setInt(12, ch.getStr());
            ps.setInt(13, ch.getDex());
            ps.setInt(14, ch.getCon());
            ps.setInt(15, ch.getIntel());
            ps.setInt(16, ch.getWis());
            ps.setInt(17, ch.getCha());
            ps.setInt(18, ch.getArmor());
            ps.setInt(19, ch.getFortitude());
            ps.setInt(20, ch.getReflex());
            ps.setInt(21, ch.getWill());
            // current_room may be null
            if (ch.getCurrentRoom() == null) ps.setNull(22, Types.INTEGER); else ps.setInt(22, ch.getCurrentRoom());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public int getAnyRoomId() {
        return DaoProvider.rooms().getAnyRoomId();
    }

    public boolean updateCharacterRoom(String name, Integer roomId) {
        String sql = "UPDATE characters SET current_room = ? WHERE name = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (roomId == null) ps.setNull(1, Types.INTEGER); else ps.setInt(1, roomId);
            ps.setString(2, name);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    // Persist mutable character state: current HP/MP/MV/KI and room
    public boolean saveCharacterStateByName(String name, int hpCur, int mpCur, int mvCur, int kiCur, Integer currentRoom) {
        String sql = "UPDATE characters SET hp_cur = ?, mp_cur = ?, mv_cur = ?, ki_cur = ?, current_room = ? WHERE name = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, hpCur);
            ps.setInt(2, mpCur);
            ps.setInt(3, mvCur);
            ps.setInt(4, kiCur);
            if (currentRoom == null) ps.setNull(5, Types.INTEGER); else ps.setInt(5, currentRoom);
            ps.setString(6, name);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /** @deprecated Use the overload that includes kiCur. */
    @Deprecated
    public boolean saveCharacterStateByName(String name, int hpCur, int mpCur, int mvCur, Integer currentRoom) {
        return saveCharacterStateByName(name, hpCur, mpCur, mvCur, 0, currentRoom);
    }

    /**
     * Update a character's ki pool max and current values.
     */
    public boolean saveKiByName(String name, int kiMax, int kiCur) {
        String sql = "UPDATE characters SET ki_max = ?, ki_cur = ? WHERE name = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, kiMax);
            ps.setInt(2, kiCur);
            ps.setString(3, name);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }
    
    /**
     * Deduct movement points from a character.
     * @return true if successful, false if failed or insufficient points
     */
    public boolean deductMovementPoints(String name, int cost) {
        String sql = "UPDATE characters SET mv_cur = mv_cur - ? WHERE name = ? AND mv_cur >= ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, cost);
            ps.setString(2, name);
            ps.setInt(3, cost);
            int rows = ps.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            return false;
        }
    }
    
    /**
     * Deduct mana points from a character.
     * @return true if successful, false if failed or insufficient mana
     */
    public boolean deductManaPoints(String name, int cost) {
        String sql = "UPDATE characters SET mp_cur = mp_cur - ? WHERE name = ? AND mp_cur >= ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, cost);
            ps.setString(2, name);
            ps.setInt(3, cost);
            int rows = ps.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            return false;
        }
    }
    
    /**
     * Add to a character's max and current HP/MP/MV (used for level-up bonuses).
     * Increases both max and current by the specified amounts.
     */
    public boolean addVitals(int characterId, int hpAdd, int mpAdd, int mvAdd) {
        String sql = "UPDATE characters SET hp_max = hp_max + ?, hp_cur = hp_cur + ?, " +
                     "mp_max = mp_max + ?, mp_cur = mp_cur + ?, " +
                     "mv_max = mv_max + ?, mv_cur = mv_cur + ? WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, hpAdd);
            ps.setInt(2, hpAdd);
            ps.setInt(3, mpAdd);
            ps.setInt(4, mpAdd);
            ps.setInt(5, mvAdd);
            ps.setInt(6, mvAdd);
            ps.setInt(7, characterId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.warn("Failed to add vitals: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Restore a character to full HP/MP/MV (set current = max).
     */
    public boolean restoreVitals(int characterId) {
        String sql = "UPDATE characters SET hp_cur = hp_max, mp_cur = mp_max, mv_cur = mv_max WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.warn("Failed to restore vitals: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Set specific vitals for a character (used for death/revival).
     * @param characterId The character to update
     * @param hp The HP to set (or null to leave unchanged)
     * @param mp The MP to set (or null to leave unchanged)
     * @param mv The MV to set (or null to leave unchanged)
     */
    public boolean setVitals(int characterId, Integer hp, Integer mp, Integer mv) {
        StringBuilder sql = new StringBuilder("UPDATE characters SET ");
        List<Integer> params = new ArrayList<>();
        boolean first = true;
        
        if (hp != null) {
            sql.append("hp_cur = ?");
            params.add(hp);
            first = false;
        }
        if (mp != null) {
            if (!first) sql.append(", ");
            sql.append("mp_cur = ?");
            params.add(mp);
            first = false;
        }
        if (mv != null) {
            if (!first) sql.append(", ");
            sql.append("mv_cur = ?");
            params.add(mv);
        }
        
        if (params.isEmpty()) return true; // Nothing to update
        
        sql.append(" WHERE id = ?");
        
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Integer p : params) {
                ps.setInt(idx++, p);
            }
            ps.setInt(idx, characterId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.warn("Failed to set vitals: {}", e.getMessage());
            return false;
        }
    }

    public boolean verifyPassword(String name, char[] password) {
        CharacterRecord rec = findByName(name);
        if (rec == null) return false;
        byte[] salt = java.util.Base64.getDecoder().decode(rec.saltBase64);
        String computed = PasswordUtil.hashPasswordBase64(password, salt);
        return PasswordUtil.constantTimeEquals(rec.passwordHashBase64, computed);
    }

    public static class CharacterRecord {
        public final String name;
        public final String passwordHashBase64;
        public final String saltBase64;
        public final int age;
        public final String description;
        public final int hpMax;
        public final int hpCur;
        public final int mpMax;
        public final int mpCur;
        public final int mvMax;
        public final int mvCur;
        public final StatBlock baseStats;
        // Equipment bonuses (persisted from equipped items)
        public final int armorEquipBonus;
        public final int fortitudeEquipBonus;
        public final int reflexEquipBonus;
        public final int willEquipBonus;
        public final Integer currentRoom;
        public final Integer currentClassId;
        public final int autoflee;  // Auto-flee threshold (0-100)
        // Talent points and trained ability bonuses
        public final int talentPoints;
        public final int trainedStr;
        public final int trainedDex;
        public final int trainedCon;
        public final int trainedInt;
        public final int trainedWis;
        public final int trainedCha;
        // Currency
        public final long goldPieces;
        // Auto-loot settings
        public final boolean autoloot;  // Auto-loot items from corpses
        public final boolean autogold;  // Auto-loot gold from corpses
        public final boolean autosac;   // Auto-sacrifice empty corpses (requires autoloot+autogold)
        public final boolean autojunk;
        public final boolean autoassist; // Auto-assist group members in combat
        // Ki resource pool (monks)
        public final int kiMax;
        public final int kiCur;
        public final String title;
        // Channel deafness flags
        public final boolean deafChat;
        public final boolean deafYell;

        // Convenience methods to get total saves (base + equipment)
        public int getArmorTotal() { return baseStats.armor() + armorEquipBonus; }
        public int getFortitudeTotal() { return baseStats.fortitude() + fortitudeEquipBonus; }
        public int getReflexTotal() { return baseStats.reflex() + reflexEquipBonus; }
        public int getWillTotal() { return baseStats.will() + willEquipBonus; }
        
        // Convenience methods to get total ability scores (base + trained)
        public int getStrTotal() { return baseStats.str() + trainedStr; }
        public int getDexTotal() { return baseStats.dex() + trainedDex; }
        public int getConTotal() { return baseStats.con() + trainedCon; }
        public int getIntTotal() { return baseStats.intel() + trainedInt; }
        public int getWisTotal() { return baseStats.wis() + trainedWis; }
        public int getChaTotal() { return baseStats.cha() + trainedCha; }

        /** Creates a new builder for constructing CharacterRecord instances. */
        public static Builder builder() { return new Builder(); }

        CharacterRecord(String name, String passwordHashBase64, String saltBase64,
                               int age, String description,
                               int hpMax, int hpCur,
                               int mpMax, int mpCur,
                               int mvMax, int mvCur,
                               StatBlock baseStats,
                               int armorEquipBonus, int fortitudeEquipBonus, int reflexEquipBonus, int willEquipBonus,
                               Integer currentRoom,
                               Integer currentClassId,
                               int autoflee,
                               int talentPoints,
                               int trainedStr, int trainedDex, int trainedCon, int trainedInt, int trainedWis, int trainedCha,
                               long goldPieces,
                               boolean autoloot, boolean autogold, boolean autosac, boolean autojunk,
                               boolean autoassist,
                               int kiMax, int kiCur,
                               String title,
                               boolean deafChat, boolean deafYell) {
            this.name = name;
            this.passwordHashBase64 = passwordHashBase64;
            this.saltBase64 = saltBase64;
            this.age = age;
            this.description = description;
            this.hpMax = hpMax;
            this.hpCur = hpCur;
            this.mpMax = mpMax;
            this.mpCur = mpCur;
            this.mvMax = mvMax;
            this.mvCur = mvCur;
            this.baseStats = baseStats;
            this.armorEquipBonus = armorEquipBonus;
            this.fortitudeEquipBonus = fortitudeEquipBonus;
            this.reflexEquipBonus = reflexEquipBonus;
            this.willEquipBonus = willEquipBonus;
            this.currentRoom = currentRoom;
            this.currentClassId = currentClassId;
            this.autoflee = autoflee;
            this.talentPoints = talentPoints;
            this.trainedStr = trainedStr;
            this.trainedDex = trainedDex;
            this.trainedCon = trainedCon;
            this.trainedInt = trainedInt;
            this.trainedWis = trainedWis;
            this.trainedCha = trainedCha;
            this.goldPieces = goldPieces;
            this.autoloot = autoloot;
            this.autogold = autogold;
            this.autosac = autosac;
            this.autojunk = autojunk;
            this.autoassist = autoassist;
            this.kiMax = kiMax;
            this.kiCur = kiCur;
            this.title = title;
            this.deafChat = deafChat;
            this.deafYell = deafYell;
        }

        /** Fluent builder for {@link CharacterRecord}. */
        public static class Builder {
            private String name;
            private String passwordHashBase64;
            private String saltBase64;
            private int age;
            private String description;
            private int hpMax, hpCur, mpMax, mpCur, mvMax, mvCur;
            private int str, dex, con, intel, wis, cha;
            private int armor, fortitude, reflex, will;
            private int armorEquipBonus, fortitudeEquipBonus, reflexEquipBonus, willEquipBonus;
            private Integer currentRoom;
            private Integer currentClassId;
            private int autoflee;
            private int talentPoints;
            private int trainedStr, trainedDex, trainedCon, trainedInt, trainedWis, trainedCha;
            private long goldPieces;
            private boolean autoloot, autogold, autosac, autojunk, autoassist;
            private int kiMax, kiCur;
            private String title;
            private boolean deafChat, deafYell;

            private Builder() {}

            public Builder name(String v) { this.name = v; return this; }
            public Builder passwordHashBase64(String v) { this.passwordHashBase64 = v; return this; }
            public Builder saltBase64(String v) { this.saltBase64 = v; return this; }
            public Builder age(int v) { this.age = v; return this; }
            public Builder description(String v) { this.description = v; return this; }
            public Builder hpMax(int v) { this.hpMax = v; return this; }
            public Builder hpCur(int v) { this.hpCur = v; return this; }
            public Builder mpMax(int v) { this.mpMax = v; return this; }
            public Builder mpCur(int v) { this.mpCur = v; return this; }
            public Builder mvMax(int v) { this.mvMax = v; return this; }
            public Builder mvCur(int v) { this.mvCur = v; return this; }
            public Builder str(int v) { this.str = v; return this; }
            public Builder dex(int v) { this.dex = v; return this; }
            public Builder con(int v) { this.con = v; return this; }
            public Builder intel(int v) { this.intel = v; return this; }
            public Builder wis(int v) { this.wis = v; return this; }
            public Builder cha(int v) { this.cha = v; return this; }
            public Builder armor(int v) { this.armor = v; return this; }
            public Builder fortitude(int v) { this.fortitude = v; return this; }
            public Builder reflex(int v) { this.reflex = v; return this; }
            public Builder will(int v) { this.will = v; return this; }
            public Builder armorEquipBonus(int v) { this.armorEquipBonus = v; return this; }
            public Builder fortitudeEquipBonus(int v) { this.fortitudeEquipBonus = v; return this; }
            public Builder reflexEquipBonus(int v) { this.reflexEquipBonus = v; return this; }
            public Builder willEquipBonus(int v) { this.willEquipBonus = v; return this; }
            public Builder currentRoom(Integer v) { this.currentRoom = v; return this; }
            public Builder currentClassId(Integer v) { this.currentClassId = v; return this; }
            public Builder autoflee(int v) { this.autoflee = v; return this; }
            public Builder talentPoints(int v) { this.talentPoints = v; return this; }
            public Builder trainedStr(int v) { this.trainedStr = v; return this; }
            public Builder trainedDex(int v) { this.trainedDex = v; return this; }
            public Builder trainedCon(int v) { this.trainedCon = v; return this; }
            public Builder trainedInt(int v) { this.trainedInt = v; return this; }
            public Builder trainedWis(int v) { this.trainedWis = v; return this; }
            public Builder trainedCha(int v) { this.trainedCha = v; return this; }
            public Builder goldPieces(long v) { this.goldPieces = v; return this; }
            public Builder autoloot(boolean v) { this.autoloot = v; return this; }
            public Builder autogold(boolean v) { this.autogold = v; return this; }
            public Builder autosac(boolean v) { this.autosac = v; return this; }
            public Builder autojunk(boolean v) { this.autojunk = v; return this; }
            public Builder autoassist(boolean v) { this.autoassist = v; return this; }
            public Builder kiMax(int v) { this.kiMax = v; return this; }
            public Builder kiCur(int v) { this.kiCur = v; return this; }
            public Builder title(String v) { this.title = v; return this; }
            public Builder deafChat(boolean v) { this.deafChat = v; return this; }
            public Builder deafYell(boolean v) { this.deafYell = v; return this; }

            public CharacterRecord build() {
                StatBlock stats = new StatBlock(str, dex, con, intel, wis, cha,
                    armor, fortitude, reflex, will);
                return new CharacterRecord(name, passwordHashBase64, saltBase64, age, description,
                    hpMax, hpCur, mpMax, mpCur, mvMax, mvCur,
                    stats,
                    armorEquipBonus, fortitudeEquipBonus, reflexEquipBonus, willEquipBonus,
                    currentRoom, currentClassId, autoflee,
                    talentPoints, trainedStr, trainedDex, trainedCon, trainedInt, trainedWis, trainedCha,
                    goldPieces, autoloot, autogold, autosac, autojunk, autoassist,
                    kiMax, kiCur, title, deafChat, deafYell);
            }
        }
    }

    /** Extract a CharacterRecord from a ResultSet row using the builder pattern. */
    private CharacterRecord extractCharacterRecord(ResultSet rs) throws SQLException {
        Integer currentRoom = rs.getObject("current_room") == null ? null : rs.getInt("current_room");
        Integer currentClassId = rs.getObject("current_class_id") == null ? null : rs.getInt("current_class_id");
        return CharacterRecord.builder()
            .name(rs.getString("name"))
            .passwordHashBase64(rs.getString("password_hash"))
            .saltBase64(rs.getString("salt"))
            .age(rs.getInt("age"))
            .description(rs.getString("description"))
            .hpMax(rs.getInt("hp_max")).hpCur(rs.getInt("hp_cur"))
            .mpMax(rs.getInt("mp_max")).mpCur(rs.getInt("mp_cur"))
            .mvMax(rs.getInt("mv_max")).mvCur(rs.getInt("mv_cur"))
            .str(rs.getInt("str")).dex(rs.getInt("dex")).con(rs.getInt("con"))
            .intel(rs.getInt("intel")).wis(rs.getInt("wis")).cha(rs.getInt("cha"))
            .armor(rs.getInt("armor")).fortitude(rs.getInt("fortitude"))
            .reflex(rs.getInt("reflex")).will(rs.getInt("will"))
            .armorEquipBonus(rs.getInt("armor_equip_bonus"))
            .fortitudeEquipBonus(rs.getInt("fortitude_equip_bonus"))
            .reflexEquipBonus(rs.getInt("reflex_equip_bonus"))
            .willEquipBonus(rs.getInt("will_equip_bonus"))
            .currentRoom(currentRoom)
            .currentClassId(currentClassId)
            .autoflee(rs.getInt("autoflee"))
            .talentPoints(rs.getInt("talent_points"))
            .trainedStr(rs.getInt("trained_str")).trainedDex(rs.getInt("trained_dex"))
            .trainedCon(rs.getInt("trained_con")).trainedInt(rs.getInt("trained_int"))
            .trainedWis(rs.getInt("trained_wis")).trainedCha(rs.getInt("trained_cha"))
            .goldPieces(rs.getLong("gold_pieces"))
            .autoloot(rs.getBoolean("autoloot")).autogold(rs.getBoolean("autogold"))
            .autosac(rs.getBoolean("autosac")).autojunk(rs.getBoolean("autojunk"))
            .autoassist(rs.getBoolean("autoassist"))
            .kiMax(rs.getInt("ki_max")).kiCur(rs.getInt("ki_cur"))
            .title(rs.getString("title"))
            .deafChat(rs.getBoolean("deaf_chat")).deafYell(rs.getBoolean("deaf_yell"))
            .build();
    }

    /**
     * Get character name by their ID.
     */
    public String getCharacterNameById(int characterId) {
        String sql = "SELECT name FROM characters WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("name");
                }
            }
        } catch (SQLException e) {
            // ignore
        }
        return null;
    }

    /**
     * Set a character's autoflee threshold.
     * @param characterId the character ID
     * @param autoflee the autoflee percentage (0-100)
     * @return true if successful
     */
    public boolean setAutoflee(int characterId, int autoflee) {
        // Clamp to valid range
        autoflee = Math.max(0, Math.min(100, autoflee));
        
        String sql = "UPDATE characters SET autoflee = ? WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, autoflee);
            ps.setInt(2, characterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warn("Failed to set autoflee: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Get a character's autoflee threshold.
     * @param characterId the character ID
     * @return the autoflee percentage (0-100), or 0 if not found
     */
    public int getAutoflee(int characterId) {
        String sql = "SELECT autoflee FROM characters WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("autoflee");
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to get autoflee: {}", e.getMessage());
        }
        return 0;
    }
    
    public String getTitle(int characterId) {
        String sql = "SELECT title FROM characters WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("title");
            }
        } catch (SQLException e) {
            logger.warn("Failed to get title: {}", e.getMessage());
        }
        return "";
    }

    public boolean setTitle(int characterId, String title) {
        String sql = "UPDATE characters SET title = ? WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, title != null ? title : "");
            ps.setInt(2, characterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warn("Failed to set title: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Set a character's autoloot flag.
     * @param characterId the character ID
     * @param autoloot whether to automatically loot items from corpses
     * @return true if successful
     */
    public boolean setAutoloot(int characterId, boolean autoloot) {
        return setAutoFlag(characterId, "autoloot", autoloot);
    }
    
    /** @see #setAutoFlag(int, String, boolean) */
    public boolean setAutogold(int characterId, boolean autogold) {
        return setAutoFlag(characterId, "autogold", autogold);
    }
    
    /** @see #setAutoFlag(int, String, boolean) */
    public boolean setAutosac(int characterId, boolean autosac) {
        return setAutoFlag(characterId, "autosac", autosac);
    }
    
    /** @see #setAutoFlag(int, String, boolean) */
    public boolean setAutoassist(int characterId, boolean autoassist) {
        return setAutoFlag(characterId, "autoassist", autoassist);
    }
    
    // ===================== TALENT POINTS =====================
    
    /**
     * Get a character's current talent points.
     * @param characterId the character ID
     * @return the number of unspent talent points
     */
    public int getTalentPoints(int characterId) {
        String sql = "SELECT talent_points FROM characters WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("talent_points");
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to get talent points: {}", e.getMessage());
        }
        return 0;
    }
    
    /**
     * Set a character's talent points.
     * @param characterId the character ID
     * @param points the new talent point total
     * @return true if successful
     */
    public boolean setTalentPoints(int characterId, int points) {
        String sql = "UPDATE characters SET talent_points = ? WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(0, points));
            ps.setInt(2, characterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warn("Failed to set talent points: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Add talent points to a character (e.g., on level-up).
     * @param characterId the character ID
     * @param points the points to add
     * @return true if successful
     */
    public boolean addTalentPoints(int characterId, int points) {
        String sql = "UPDATE characters SET talent_points = talent_points + ? WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, points);
            ps.setInt(2, characterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warn("Failed to add talent points: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Get the trained bonus for an ability score.
     * @param characterId the character ID
     * @param ability the ability name (str, dex, con, int, wis, cha)
     * @return the trained bonus (0 if none or invalid ability)
     */
    public int getTrainedAbility(int characterId, String ability) {
        String column = getTrainedAbilityColumn(ability);
        if (column == null) return 0;
        
        String sql = "SELECT " + column + " FROM characters WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to get trained ability: {}", e.getMessage());
        }
        return 0;
    }
    
    /**
     * Set the trained bonus for an ability score.
     * @param characterId the character ID
     * @param ability the ability name (str, dex, con, int, wis, cha)
     * @param bonus the new trained bonus
     * @return true if successful
     */
    public boolean setTrainedAbility(int characterId, String ability, int bonus) {
        String column = getTrainedAbilityColumn(ability);
        if (column == null) return false;
        
        String sql = "UPDATE characters SET " + column + " = ? WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(0, bonus));
            ps.setInt(2, characterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warn("Failed to set trained ability: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Increment the trained bonus for an ability by 1.
     * @param characterId the character ID
     * @param ability the ability name (str, dex, con, int, wis, cha)
     * @return true if successful
     */
    public boolean incrementTrainedAbility(int characterId, String ability) {
        String column = getTrainedAbilityColumn(ability);
        if (column == null) return false;
        
        String sql = "UPDATE characters SET " + column + " = " + column + " + 1 WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warn("Failed to increment trained ability: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Map ability name to database column.
     */
    private String getTrainedAbilityColumn(String ability) {
        if (ability == null) return null;
        switch (ability.toLowerCase()) {
            case "str": case "strength": return "trained_str";
            case "dex": case "dexterity": return "trained_dex";
            case "con": case "constitution": return "trained_con";
            case "int": case "intel": case "intelligence": return "trained_int";
            case "wis": case "wisdom": return "trained_wis";
            case "cha": case "charisma": return "trained_cha";
            default: return null;
        }
    }
    
    /**
     * Calculate the talent point cost to train an ability from its current total to the next point.
     * Costs: 10-16 = 1 point, 17-18 = 2 points, 19-20 = 4 points, 21+ = impossible
     * @param currentTotal the current total ability score (base + trained)
     * @return the cost, or -1 if training is not possible
     */
    public static int getAbilityTrainingCost(int currentTotal) {
        if (currentTotal < 10) return 1;  // Below minimum, easy to train
        if (currentTotal <= 16) return 1;
        if (currentTotal <= 18) return 2;
        if (currentTotal <= 20) return 4;
        return -1;  // Cannot train above 20
    }

    // ========== Gold Methods ==========

    /**
     * Get the current gold pieces for a character.
     * @param characterId the character's ID
     * @return the amount of gold pieces, or 0 if not found
     */
    public long getGold(int characterId) {
        String sql = "SELECT gold_pieces FROM characters WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("gold_pieces");
                }
            }
        } catch (SQLException e) {
            logger.warn("Error getting gold for character {}: {}", characterId, e.getMessage());
        }
        return 0;
    }

    /**
     * Set the gold pieces for a character to a specific amount.
     * @param characterId the character's ID
     * @param amount the new gold amount (must be >= 0)
     * @return true if successful, false otherwise
     */
    public boolean setGold(int characterId, long amount) {
        if (amount < 0) {
            logger.warn("Attempted to set negative gold for character {}", characterId);
            return false;
        }
        String sql = "UPDATE characters SET gold_pieces = ? WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setInt(2, characterId);
            int rows = ps.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            logger.warn("Error setting gold for character {}: {}", characterId, e.getMessage());
        }
        return false;
    }

    /**
     * Add gold pieces to a character's current amount.
     * @param characterId the character's ID
     * @param amount the amount to add (can be negative to subtract)
     * @return true if successful, false otherwise
     */
    public boolean addGold(int characterId, long amount) {
        String sql = "UPDATE characters SET gold_pieces = gold_pieces + ? WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setInt(2, characterId);
            int rows = ps.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            logger.warn("Error adding gold for character {}: {}", characterId, e.getMessage());
        }
        return false;
    }
    
    /**
     * Set a character attribute by name. Used by GM cset command.
     * Returns a result message describing success or failure.
     * 
     * Supported attributes:
     * - Vitals: hp, hpmax, mp, mpmax, mv, mvmax
     * - Base Abilities: str, dex, con, int, wis, cha
     * - Trained Abilities: trained_str, trained_dex, trained_con, trained_int, trained_wis, trained_cha
     * - Saves: armor, fortitude, reflex, will
     * - Equipment bonuses: armor_equip, fort_equip, reflex_equip, will_equip
     * - Other: age, room, class, autoflee, talents, gold, xp, level
     */
    public String setCharacterAttribute(int characterId, String attribute, String value) {
        if (attribute == null || value == null) {
            return "Attribute and value are required.";
        }
        
        String attr = attribute.toLowerCase().trim();
        
        // Handle special attributes first
        switch (attr) {
            case "xp": {
                try {
                    int xp = Integer.parseInt(value);
                    CharacterClassDAO classDAO = DaoProvider.classes();
                    Integer classId = classDAO.getCharacterCurrentClassId(characterId);
                    if (classId == null) return "Character has no class.";
                    String sql = "UPDATE character_class_progress SET class_xp = ? WHERE character_id = ? AND class_id = ?";
                    try (Connection c = TransactionManager.getConnection();
                         PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setInt(1, Math.max(0, xp));
                        ps.setInt(2, characterId);
                        ps.setInt(3, classId);
                        if (ps.executeUpdate() > 0) return "XP set to " + xp;
                    }
                    return "Failed to set XP.";
                } catch (Exception e) { return "Invalid XP value: " + value; }
            }
            case "level": {
                try {
                    int level = Integer.parseInt(value);
                    if (level < 1 || level > 55) return "Level must be between 1 and 55.";
                    CharacterClassDAO classDAO = DaoProvider.classes();
                    Integer classId = classDAO.getCharacterCurrentClassId(characterId);
                    if (classId == null) return "Character has no class.";
                    String sql = "UPDATE character_class_progress SET class_level = ? WHERE character_id = ? AND class_id = ?";
                    try (Connection c = TransactionManager.getConnection();
                         PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setInt(1, level);
                        ps.setInt(2, characterId);
                        ps.setInt(3, classId);
                        if (ps.executeUpdate() > 0) return "Level set to " + level;
                    }
                    return "Failed to set level.";
                } catch (Exception e) { return "Invalid level value: " + value; }
            }
            case "class": {
                try {
                    int classId = Integer.parseInt(value);
                    String sql = "UPDATE characters SET current_class_id = ? WHERE id = ?";
                    try (Connection c = TransactionManager.getConnection();
                         PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setInt(1, classId);
                        ps.setInt(2, characterId);
                        if (ps.executeUpdate() > 0) return "Class ID set to " + classId;
                    }
                    return "Failed to set class.";
                } catch (Exception e) { return "Invalid class ID: " + value; }
            }
            case "description": case "desc": {
                String sql = "UPDATE characters SET description = ? WHERE id = ?";
                try (Connection c = TransactionManager.getConnection();
                     PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, value);
                    ps.setInt(2, characterId);
                    if (ps.executeUpdate() > 0) return "Description set.";
                } catch (SQLException e) { return "Failed to set description: " + e.getMessage(); }
                return "Failed to set description.";
            }
        }
        
        // Map attribute name to column
        String column = null;
        boolean isLong = false;
        
        switch (attr) {
            // Vitals
            case "hp": case "hpcur": case "hp_cur": column = "hp_cur"; break;
            case "hpmax": case "hp_max": column = "hp_max"; break;
            case "mp": case "mpcur": case "mp_cur": column = "mp_cur"; break;
            case "mpmax": case "mp_max": column = "mp_max"; break;
            case "mv": case "mvcur": case "mv_cur": column = "mv_cur"; break;
            case "mvmax": case "mv_max": column = "mv_max"; break;
            
            // Base abilities
            case "str": case "strength": column = "str"; break;
            case "dex": case "dexterity": column = "dex"; break;
            case "con": case "constitution": column = "con"; break;
            case "int": case "intel": case "intelligence": column = "intel"; break;
            case "wis": case "wisdom": column = "wis"; break;
            case "cha": case "charisma": column = "cha"; break;
            
            // Trained abilities
            case "trained_str": case "trainedstr": case "tstr": column = "trained_str"; break;
            case "trained_dex": case "traineddex": case "tdex": column = "trained_dex"; break;
            case "trained_con": case "trainedcon": case "tcon": column = "trained_con"; break;
            case "trained_int": case "trainedint": case "tint": column = "trained_int"; break;
            case "trained_wis": case "trainedwis": case "twis": column = "trained_wis"; break;
            case "trained_cha": case "trainedcha": case "tcha": column = "trained_cha"; break;
            
            // Saves
            case "armor": case "ac": column = "armor"; break;
            case "fortitude": case "fort": column = "fortitude"; break;
            case "reflex": case "ref": column = "reflex"; break;
            case "will": column = "will"; break;
            
            // Equipment bonuses
            case "armor_equip": case "armorequip": case "ac_equip": column = "armor_equip_bonus"; break;
            case "fort_equip": case "fortequip": case "fortitude_equip": column = "fortitude_equip_bonus"; break;
            case "reflex_equip": case "reflexequip": case "ref_equip": column = "reflex_equip_bonus"; break;
            case "will_equip": case "willequip": column = "will_equip_bonus"; break;
            
            // Other
            case "age": column = "age"; break;
            case "room": case "currentroom": case "current_room": column = "current_room"; break;
            case "autoflee": column = "autoflee"; break;
            case "talents": case "talentpoints": case "talent_points": column = "talent_points"; break;
            case "gold": case "goldpieces": case "gold_pieces": column = "gold_pieces"; isLong = true; break;
            
            default:
                return "Unknown attribute: " + attribute + ". Use CSET LIST to see available attributes.";
        }
        
        // Parse and update
        String sql = "UPDATE characters SET " + column + " = ? WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (isLong) {
                long longVal = Long.parseLong(value);
                ps.setLong(1, longVal);
            } else {
                int intVal = Integer.parseInt(value);
                ps.setInt(1, intVal);
            }
            ps.setInt(2, characterId);
            if (ps.executeUpdate() > 0) {
                return attr.toUpperCase() + " set to " + value;
            }
            return "Failed to update " + attr + ".";
        } catch (NumberFormatException e) {
            return "Invalid numeric value: " + value;
        } catch (SQLException e) {
            return "Database error: " + e.getMessage();
        }
    }
    
    /**
     * Get list of all settable attributes for CSET command.
     */
    public static java.util.List<String> getSettableAttributes() {
        return java.util.Arrays.asList(
            "hp", "hpmax", "mp", "mpmax", "mv", "mvmax",
            "str", "dex", "con", "int", "wis", "cha",
            "trained_str", "trained_dex", "trained_con", "trained_int", "trained_wis", "trained_cha",
            "armor", "fortitude", "reflex", "will",
            "armor_equip", "fort_equip", "reflex_equip", "will_equip",
            "age", "room", "class", "autoflee", "talents", "gold", "xp", "level", "description"
        );
    }

    public boolean setAutojunk(int characterId, boolean autojunk) {
        return setAutoFlag(characterId, "autojunk", autojunk);
    }

    /**
     * Generic setter for boolean auto-flags (autoloot, autogold, autosac, autojunk, autoassist).
     * The column name is validated against an allowlist to prevent SQL injection.
     */
    public boolean setAutoFlag(int characterId, String flagColumn, boolean value) {
        // Allowlist of valid auto-flag columns
        if (!Set.of("autoloot", "autogold", "autosac", "autojunk", "autoassist", "deaf_chat", "deaf_yell").contains(flagColumn)) {
            logger.warn("Invalid auto-flag column: {}", flagColumn);
            return false;
        }
        String sql = "UPDATE characters SET " + flagColumn + " = ? WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBoolean(1, value);
            ps.setInt(2, characterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warn("Failed to set {}: {}", flagColumn, e.getMessage());
            return false;
        }
    }

    public int getPlayerLevel(Integer characterId) {
        if (characterId == null) return 1;
        CharacterClassDAO classDAO = DaoProvider.classes();
        
        Integer currentClassId = classDAO.getCharacterCurrentClassId(characterId);
        if (currentClassId != null) {
            return Math.max(1, classDAO.getCharacterClassLevel(characterId, currentClassId));
        }
        
        // Fallback to character base level (if any)
        return 1;
    }
}
