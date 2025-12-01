package com.example.tassmud.persistence;

import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileBehavior;
import com.example.tassmud.model.MobileTemplate;

import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Data Access Object for mobile templates and instances.
 */
public class MobileDAO {
    
    private static final String URL = "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
    private static final String USER = "sa";
    private static final String PASS = "";
    
    public MobileDAO() {
        ensureTables();
    }
    
    private void ensureTables() {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement()) {
            
            // Mobile template table
            s.execute("CREATE TABLE IF NOT EXISTS mobile_template (" +
                "id INT PRIMARY KEY, " +
                "template_key VARCHAR(64) UNIQUE, " +
                "name VARCHAR(128), " +
                "short_desc VARCHAR(256), " +
                "long_desc CLOB, " +
                "keywords VARCHAR(512), " +  // Comma-separated
                "level INT DEFAULT 1, " +
                "hp_max INT DEFAULT 10, " +
                "mp_max INT DEFAULT 0, " +
                "mv_max INT DEFAULT 100, " +
                "str INT DEFAULT 10, " +
                "dex INT DEFAULT 10, " +
                "con INT DEFAULT 10, " +
                "intel INT DEFAULT 10, " +
                "wis INT DEFAULT 10, " +
                "cha INT DEFAULT 10, " +
                "armor INT DEFAULT 10, " +
                "fortitude INT DEFAULT 0, " +
                "reflex INT DEFAULT 0, " +
                "will_save INT DEFAULT 0, " +
                "base_damage INT DEFAULT 4, " +
                "damage_bonus INT DEFAULT 0, " +
                "attack_bonus INT DEFAULT 0, " +
                "behaviors VARCHAR(256) DEFAULT 'PASSIVE', " +  // Comma-separated list
                "aggro_range INT DEFAULT 0, " +
                "experience_value INT DEFAULT 10, " +
                "gold_min INT DEFAULT 0, " +
                "gold_max INT DEFAULT 0, " +
                "respawn_seconds INT DEFAULT 300, " +
                "template_json CLOB" +
            ")");
            
            // Mobile instance table
            s.execute("CREATE TABLE IF NOT EXISTS mobile_instance (" +
                "instance_id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                "template_id INT NOT NULL, " +
                "current_room_id INT, " +
                "spawn_room_id INT, " +
                "hp_cur INT, " +
                "mp_cur INT, " +
                "mv_cur INT, " +
                "is_dead BOOLEAN DEFAULT FALSE, " +
                "spawned_at BIGINT, " +
                "died_at BIGINT DEFAULT 0, " +
                "orig_uuid VARCHAR(64), " +
                "FOREIGN KEY(template_id) REFERENCES mobile_template(id)" +
            ")");
            
            // Migration: add autoflee column
            s.execute("ALTER TABLE mobile_template ADD COLUMN IF NOT EXISTS autoflee INT DEFAULT 0");
            // Migration: add orig_uuid to mobile_instance to tie instances to original spawn mappings
            s.execute("ALTER TABLE mobile_instance ADD COLUMN IF NOT EXISTS orig_uuid VARCHAR(64)");
            // Spawn mapping table: room_id, template_id, uuid
            s.execute("CREATE TABLE IF NOT EXISTS spawn_mapping (room_id INT NOT NULL, template_id INT NOT NULL, orig_uuid VARCHAR(64) NOT NULL, PRIMARY KEY(room_id, template_id, orig_uuid))");
            
            System.out.println("MobileDAO: ensured mobile_template and mobile_instance tables");
            
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create mobile tables", e);
        }
    }
    
    // ==================== TEMPLATE OPERATIONS ====================
    
    /**
     * Insert or update a mobile template.
     */
    public void upsertTemplate(MobileTemplate template) {
        String sql = "MERGE INTO mobile_template (id, template_key, name, short_desc, long_desc, keywords, " +
            "level, hp_max, mp_max, mv_max, str, dex, con, intel, wis, cha, " +
            "armor, fortitude, reflex, will_save, base_damage, damage_bonus, attack_bonus, " +
            "behaviors, aggro_range, experience_value, gold_min, gold_max, respawn_seconds, autoflee, template_json) " +
            "KEY(id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, template.getId());
            ps.setString(2, template.getKey());
            ps.setString(3, template.getName());
            ps.setString(4, template.getShortDesc());
            ps.setString(5, template.getLongDesc());
            ps.setString(6, String.join(",", template.getKeywords()));
            ps.setInt(7, template.getLevel());
            ps.setInt(8, template.getHpMax());
            ps.setInt(9, template.getMpMax());
            ps.setInt(10, template.getMvMax());
            ps.setInt(11, template.getStr());
            ps.setInt(12, template.getDex());
            ps.setInt(13, template.getCon());
            ps.setInt(14, template.getIntel());
            ps.setInt(15, template.getWis());
            ps.setInt(16, template.getCha());
            ps.setInt(17, template.getArmor());
            ps.setInt(18, template.getFortitude());
            ps.setInt(19, template.getReflex());
            ps.setInt(20, template.getWill());
            ps.setInt(21, template.getBaseDamage());
            ps.setInt(22, template.getDamageBonus());
            ps.setInt(23, template.getAttackBonus());
            // Serialize behaviors list to comma-separated string
            String behaviorsStr = template.getBehaviors().stream()
                .map(MobileBehavior::name)
                .reduce((a, b) -> a + "," + b)
                .orElse("PASSIVE");
            ps.setString(24, behaviorsStr);
            ps.setInt(25, template.getAggroRange());
            ps.setInt(26, template.getExperienceValue());
            ps.setInt(27, template.getGoldMin());
            ps.setInt(28, template.getGoldMax());
            ps.setInt(29, template.getRespawnSeconds());
            ps.setInt(30, template.getAutoflee());
            ps.setString(31, template.getTemplateJson());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert mobile template", e);
        }
    }
    
    /**
     * Get a mobile template by ID.
     */
    public MobileTemplate getTemplateById(int id) {
        String sql = "SELECT * FROM mobile_template WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return templateFromResultSet(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get mobile template", e);
        }
        return null;
    }
    
    /**
     * Get a mobile template by key.
     */
    public MobileTemplate getTemplateByKey(String key) {
        String sql = "SELECT * FROM mobile_template WHERE template_key = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return templateFromResultSet(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get mobile template by key", e);
        }
        return null;
    }
    
    /**
     * Search mobile templates by name (case-insensitive, partial match).
     */
    public List<MobileTemplate> searchTemplates(String searchStr) {
        List<MobileTemplate> results = new ArrayList<>();
        if (searchStr == null || searchStr.trim().isEmpty()) return results;
        
        String sql = "SELECT * FROM mobile_template WHERE LOWER(name) LIKE ? ORDER BY id";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "%" + searchStr.toLowerCase() + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(templateFromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to search mobile templates", e);
        }
        return results;
    }
    
    /**
     * Get all mobile templates.
     */
    public List<MobileTemplate> getAllTemplates() {
        List<MobileTemplate> results = new ArrayList<>();
        String sql = "SELECT * FROM mobile_template ORDER BY id";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(templateFromResultSet(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get all mobile templates", e);
        }
        return results;
    }
    
    private MobileTemplate templateFromResultSet(ResultSet rs) throws SQLException {
        String keywordsStr = rs.getString("keywords");
        List<String> keywords = keywordsStr != null && !keywordsStr.isEmpty() 
            ? Arrays.asList(keywordsStr.split(","))
            : new ArrayList<>();
        
        // Parse behaviors from comma-separated string
        String behaviorsStr = rs.getString("behaviors");
        List<MobileBehavior> behaviors = new ArrayList<>();
        if (behaviorsStr != null && !behaviorsStr.isEmpty()) {
            for (String b : behaviorsStr.split(",")) {
                MobileBehavior behavior = MobileBehavior.fromString(b.trim());
                if (behavior != null) {
                    behaviors.add(behavior);
                }
            }
        }
        if (behaviors.isEmpty()) {
            behaviors.add(MobileBehavior.PASSIVE); // Default
        }
        
        return new MobileTemplate(
            rs.getInt("id"),
            rs.getString("template_key"),
            rs.getString("name"),
            rs.getString("short_desc"),
            rs.getString("long_desc"),
            keywords,
            rs.getInt("level"),
            rs.getInt("hp_max"),
            rs.getInt("mp_max"),
            rs.getInt("mv_max"),
            rs.getInt("str"),
            rs.getInt("dex"),
            rs.getInt("con"),
            rs.getInt("intel"),
            rs.getInt("wis"),
            rs.getInt("cha"),
            rs.getInt("armor"),
            rs.getInt("fortitude"),
            rs.getInt("reflex"),
            rs.getInt("will_save"),
            rs.getInt("base_damage"),
            rs.getInt("damage_bonus"),
            rs.getInt("attack_bonus"),
            behaviors,
            rs.getInt("aggro_range"),
            rs.getInt("experience_value"),
            rs.getInt("gold_min"),
            rs.getInt("gold_max"),
            rs.getInt("respawn_seconds"),
            rs.getInt("autoflee"),
            rs.getString("template_json")
        );
    }
    
    // ==================== INSTANCE OPERATIONS ====================
    
    /**
     * Spawn a new mobile instance from a template.
     */
    public Mobile spawnMobile(MobileTemplate template, int roomId) {
        String sql = "INSERT INTO mobile_instance (template_id, current_room_id, spawn_room_id, " +
            "hp_cur, mp_cur, mv_cur, is_dead, spawned_at, died_at) VALUES (?,?,?,?,?,?,?,?,?)";
        
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            long now = System.currentTimeMillis();
            ps.setInt(1, template.getId());
            ps.setInt(2, roomId);
            ps.setInt(3, roomId);
            ps.setInt(4, template.getHpMax());
            ps.setInt(5, template.getMpMax());
            ps.setInt(6, template.getMvMax());
            ps.setBoolean(7, false);
            ps.setLong(8, now);
            ps.setLong(9, 0);
            ps.executeUpdate();
            
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    long instanceId = keys.getLong(1);
                    return new Mobile(instanceId, template, roomId);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to spawn mobile", e);
        }
        return null;
    }

    /**
     * Spawn a new mobile instance from a template and record an origin UUID on the instance.
     */
    public Mobile spawnMobile(MobileTemplate template, int roomId, String originUuid) {
        String sql = "INSERT INTO mobile_instance (template_id, current_room_id, spawn_room_id, " +
            "hp_cur, mp_cur, mv_cur, is_dead, spawned_at, died_at, orig_uuid) VALUES (?,?,?,?,?,?,?,?,?,?)";

        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            long now = System.currentTimeMillis();
            ps.setInt(1, template.getId());
            ps.setInt(2, roomId);
            ps.setInt(3, roomId);
            ps.setInt(4, template.getHpMax());
            ps.setInt(5, template.getMpMax());
            ps.setInt(6, template.getMvMax());
            ps.setBoolean(7, false);
            ps.setLong(8, now);
            ps.setLong(9, 0);
            ps.setString(10, originUuid);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    long instanceId = keys.getLong(1);
                    // Return the freshly persisted instance (loads orig_uuid and full fields)
                    return getInstanceById(instanceId);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to spawn mobile with UUID", e);
        }
        return null;
    }
    
    /**
     * Get a mobile instance by ID.
     */
    public Mobile getInstanceById(long instanceId) {
        String sql = "SELECT mi.*, mt.level, mt.name, mt.short_desc, mt.long_desc, mt.keywords, " +
            "mt.hp_max, mt.mp_max, mt.mv_max, mt.str, mt.dex, mt.con, mt.intel, mt.wis, mt.cha, " +
            "mt.armor, mt.fortitude, mt.reflex, mt.will_save, mt.behaviors, " +
            "mt.experience_value, mt.base_damage, mt.damage_bonus, mt.attack_bonus, mt.autoflee " +
            "FROM mobile_instance mi JOIN mobile_template mt ON mi.template_id = mt.id " +
            "WHERE mi.instance_id = ?";
        
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, instanceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mobileFromResultSet(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get mobile instance", e);
        }
        return null;
    }
    
    /**
     * Get all mobile instances in a room.
     */
    public List<Mobile> getMobilesInRoom(int roomId) {
        List<Mobile> results = new ArrayList<>();
        String sql = "SELECT mi.*, mt.level, mt.name, mt.short_desc, mt.long_desc, mt.keywords, " +
            "mt.hp_max, mt.mp_max, mt.mv_max, mt.str, mt.dex, mt.con, mt.intel, mt.wis, mt.cha, " +
            "mt.armor, mt.fortitude, mt.reflex, mt.will_save, mt.behaviors, " +
            "mt.experience_value, mt.base_damage, mt.damage_bonus, mt.attack_bonus, mt.autoflee " +
            "FROM mobile_instance mi JOIN mobile_template mt ON mi.template_id = mt.id " +
            "WHERE mi.current_room_id = ? AND mi.is_dead = FALSE";
        
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mobileFromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get mobiles in room", e);
        }
        return results;
    }
    
    /**
     * Get all mobile instances (alive or dead).
     */
    public List<Mobile> getAllInstances() {
        List<Mobile> results = new ArrayList<>();
        String sql = "SELECT mi.*, mt.level, mt.name, mt.short_desc, mt.long_desc, mt.keywords, " +
            "mt.hp_max, mt.mp_max, mt.mv_max, mt.str, mt.dex, mt.con, mt.intel, mt.wis, mt.cha, " +
            "mt.armor, mt.fortitude, mt.reflex, mt.will_save, mt.behaviors, " +
            "mt.experience_value, mt.base_damage, mt.damage_bonus, mt.attack_bonus, mt.autoflee " +
            "FROM mobile_instance mi JOIN mobile_template mt ON mi.template_id = mt.id";
        
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                results.add(mobileFromResultSet(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get all mobile instances", e);
        }
        return results;
    }

    /**
     * Delete all mobile instances from the database.
     * Used at server startup to ensure we don't accumulate mobs across restarts.
     */
    public void clearAllInstances() {
        String sql = "DELETE FROM mobile_instance";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            int deleted = ps.executeUpdate();
            System.out.println("MobileDAO: cleared " + deleted + " mobile instances from DB");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear mobile instances", e);
        }
    }
    
    /**
     * Update a mobile instance's state.
     */
    public void updateInstance(Mobile mobile) {
        String sql = "UPDATE mobile_instance SET current_room_id = ?, hp_cur = ?, mp_cur = ?, mv_cur = ?, " +
            "is_dead = ?, died_at = ? WHERE instance_id = ?";
        
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, mobile.getCurrentRoom());
            ps.setInt(2, mobile.getHpCur());
            ps.setInt(3, mobile.getMpCur());
            ps.setInt(4, mobile.getMvCur());
            ps.setBoolean(5, mobile.isDead());
            ps.setLong(6, mobile.getDiedAt());
            ps.setLong(7, mobile.getInstanceId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update mobile instance", e);
        }
    }
    
    /**
     * Delete a mobile instance.
     */
    public void deleteInstance(long instanceId) {
        String sql = "DELETE FROM mobile_instance WHERE instance_id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, instanceId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete mobile instance", e);
        }
    }
    
    /**
     * Find instances by template ID.
     */
    public List<Mobile> findInstancesByTemplateId(int templateId) {
        List<Mobile> results = new ArrayList<>();
        String sql = "SELECT mi.*, mt.level, mt.name, mt.short_desc, mt.long_desc, mt.keywords, " +
            "mt.hp_max, mt.mp_max, mt.mv_max, mt.str, mt.dex, mt.con, mt.intel, mt.wis, mt.cha, " +
            "mt.armor, mt.fortitude, mt.reflex, mt.will_save, mt.behaviors, " +
            "mt.experience_value, mt.base_damage, mt.damage_bonus, mt.attack_bonus, mt.autoflee " +
            "FROM mobile_instance mi JOIN mobile_template mt ON mi.template_id = mt.id " +
            "WHERE mi.template_id = ?";
        
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, templateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mobileFromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find mobile instances by template", e);
        }
        return results;
    }

    /**
     * Get all configured spawn mapping UUIDs for a room/template.
     */
    public List<String> getSpawnMappingUUIDs(int roomId, int templateId) {
        List<String> uuids = new ArrayList<>();
        String sql = "SELECT orig_uuid FROM spawn_mapping WHERE room_id = ? AND template_id = ? ORDER BY orig_uuid";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            ps.setInt(2, templateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    uuids.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get spawn mappings", e);
        }
        return uuids;
    }

    /**
     * Find a live mobile instance by its origin UUID.
     * Returns the Mobile if present and not dead, otherwise null.
     */
    public Mobile getInstanceByOriginUuid(String originUuid) {
        if (originUuid == null || originUuid.isEmpty()) return null;
        String sql = "SELECT mi.*, mt.level, mt.name, mt.short_desc, mt.long_desc, mt.keywords, " +
            "mt.hp_max, mt.mp_max, mt.mv_max, mt.str, mt.dex, mt.con, mt.intel, mt.wis, mt.cha, " +
            "mt.armor, mt.fortitude, mt.reflex, mt.will_save, mt.behaviors, " +
            "mt.experience_value, mt.base_damage, mt.damage_bonus, mt.attack_bonus, mt.autoflee " +
            "FROM mobile_instance mi JOIN mobile_template mt ON mi.template_id = mt.id " +
            "WHERE mi.orig_uuid = ? AND mi.is_dead = FALSE";

        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, originUuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mobileFromResultSet(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query instance by origin UUID", e);
        }
        return null;
    }

    /**
     * Ensure there are N spawn mapping UUIDs for the given room/template. Will insert additional UUIDs as needed.
     */
    public void ensureSpawnMappings(int roomId, int templateId, int quantity) {
        List<String> existing = getSpawnMappingUUIDs(roomId, templateId);
        int need = Math.max(0, quantity - existing.size());
        if (need <= 0) return;

        String sql = "INSERT INTO spawn_mapping (room_id, template_id, orig_uuid) VALUES (?,?,?)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < need; i++) {
                String uuid = java.util.UUID.randomUUID().toString();
                ps.setInt(1, roomId);
                ps.setInt(2, templateId);
                ps.setString(3, uuid);
                ps.addBatch();
            }
            ps.executeBatch();
            System.out.println("MobileDAO: added " + need + " spawn mapping(s) for template " + templateId + " in room " + roomId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to ensure spawn mappings", e);
        }
    }
    
    private Mobile mobileFromResultSet(ResultSet rs) throws SQLException {
        // Parse behaviors from comma-separated string
        String behaviorsStr = rs.getString("behaviors");
        List<MobileBehavior> behaviors = new ArrayList<>();
        if (behaviorsStr != null && !behaviorsStr.isEmpty()) {
            for (String b : behaviorsStr.split(",")) {
                MobileBehavior behavior = MobileBehavior.fromString(b.trim());
                if (behavior != null) {
                    behaviors.add(behavior);
                }
            }
        }
        if (behaviors.isEmpty()) {
            behaviors.add(MobileBehavior.PASSIVE); // Default
        }
        
        String origUuid = rs.getString("orig_uuid");
        return new Mobile(
            rs.getLong("instance_id"),
            rs.getInt("template_id"),
            rs.getInt("level"),
            rs.getString("name"),
            rs.getString("long_desc"),
            rs.getInt("hp_max"),
            rs.getInt("hp_cur"),
            rs.getInt("mp_max"),
            rs.getInt("mp_cur"),
            rs.getInt("mv_max"),
            rs.getInt("mv_cur"),
            rs.getObject("current_room_id") == null ? null : rs.getInt("current_room_id"),
            rs.getObject("spawn_room_id") == null ? null : rs.getInt("spawn_room_id"),
            rs.getInt("str"),
            rs.getInt("dex"),
            rs.getInt("con"),
            rs.getInt("intel"),
            rs.getInt("wis"),
            rs.getInt("cha"),
            rs.getInt("armor"),
            rs.getInt("fortitude"),
            rs.getInt("reflex"),
            rs.getInt("will_save"),
            rs.getString("short_desc"),
            behaviors,
            rs.getInt("experience_value"),
            rs.getInt("base_damage"),
            rs.getInt("damage_bonus"),
            rs.getInt("attack_bonus"),
            rs.getInt("autoflee"),
            origUuid,
            rs.getLong("spawned_at"),
            rs.getBoolean("is_dead"),
            rs.getLong("died_at")
        );
    }
}
