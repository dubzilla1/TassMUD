package com.example.tassmud.persistence;

import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileBehavior;
import com.example.tassmud.model.MobileTemplate;

import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data Access Object for mobile templates and instances.
 */
public class MobileDAO {
    private static final Logger logger = LoggerFactory.getLogger(MobileDAO.class);
    
    private final String url;
    private static final String URL = System.getProperty("tassmud.db.url", "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1");
    private static final String USER = "sa";
    private static final String PASS = "";

    public MobileDAO() {
        this.url = System.getProperty("tassmud.db.url", "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1");
        // Run migrations/ensureTables once per DAO class to avoid repeating expensive
        // schema checks for every DAO instance during startup.
        MigrationManager.ensureMigration("MobileDAO", this::ensureTables);
    }
    
    private void ensureTables() {
        try (Connection c = DriverManager.getConnection(url, USER, PASS);
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
                "fortitude_cur INT, " +
                "reflex_cur INT, " +
                "will_cur INT, " +
                "is_dead BOOLEAN DEFAULT FALSE, " +
                "spawned_at BIGINT, " +
                "died_at BIGINT DEFAULT 0, " +
                "orig_uuid VARCHAR(64) " +
            ")");
            
            // Migration: add autoflee column
            s.execute("ALTER TABLE mobile_template ADD COLUMN IF NOT EXISTS autoflee INT DEFAULT 0");
            // Migration: increase template_key length if needed (best-effort)
            try {
                s.execute("ALTER TABLE mobile_template ALTER COLUMN template_key SET DATA TYPE VARCHAR(200)");
                logger.debug("Migration: ensured mobile_template.template_key length >= 200");
            } catch (SQLException ignore) {
                // Best-effort; older H2 versions or already-correct types may throw. Log and continue.
                logger.debug("Migration: skipping template_key resize (may already be adequate)");
            }
            // Migration: add orig_uuid to mobile_instance to tie instances to original spawn mappings
            s.execute("ALTER TABLE mobile_instance ADD COLUMN IF NOT EXISTS orig_uuid VARCHAR(64)");
            // Spawn mapping table: room_id, template_id, uuid
            s.execute("CREATE TABLE IF NOT EXISTS spawn_mapping (room_id INT NOT NULL, template_id INT NOT NULL, orig_uuid VARCHAR(64) NOT NULL, PRIMARY KEY(room_id, template_id, orig_uuid))");
            // Mobile-instance item markers: link mobile instances to item instances they 'own' (equip or inventory)
            s.execute("CREATE TABLE IF NOT EXISTS mobile_instance_item (mobile_instance_id BIGINT NOT NULL, item_instance_id BIGINT NOT NULL, kind VARCHAR(32) NOT NULL, PRIMARY KEY(mobile_instance_id, item_instance_id))");
            
            logger.info("MobileDAO: ensured mobile_template and mobile_instance tables");
            
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
        
           try (Connection c = DriverManager.getConnection(url, USER, PASS)) {
            // If a different row already exists with the same template_key,
            // reuse that id to avoid UNIQUE constraint violations on template_key.
            int targetId = template.getId();
            String existingSql = "SELECT id FROM mobile_template WHERE template_key = ?";
            try (PreparedStatement checkPs = c.prepareStatement(existingSql)) {
                checkPs.setString(1, template.getKey());
                try (ResultSet rs = checkPs.executeQuery()) {
                    if (rs.next()) {
                        int existingId = rs.getInt(1);
                        if (existingId != targetId) {
                            logger.info("MobileDAO.upsertTemplate: template_key '{}' already exists as id={}, reusing that id instead of {}", template.getKey(), existingId, targetId);
                            targetId = existingId;
                        }
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, targetId);
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
                // Note: if we changed targetId above we already set it into slot 1
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert mobile template", e);
        }
    }
    
    /**
     * Get a mobile template by ID.
     */
    public MobileTemplate getTemplateById(int id) {
        String sql = "SELECT * FROM mobile_template WHERE id = ?";
        try (Connection c = DriverManager.getConnection(url, USER, PASS);
               PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            logger.debug("[MobileDAO] getTemplateById: executing against URL={} SQL={} id={}", url, sql, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    logger.debug("[MobileDAO] getTemplateById: found template id={} name={}", id, rs.getString("name"));
                    return templateFromResultSet(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get mobile template", e);
        }
        // Not found - print diagnostic summary of templates present (first few)
        try {
            List<Integer> ids = getAllTemplateIds();
            logger.debug("[MobileDAO] getTemplateById: template not found: {} ; total templates={} sample_ids={}", id, ids.size(), (ids.size() <= 10 ? ids : ids.subList(0,10)));
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Return a list of all template ids (ordered). Useful for diagnostics.
     */
    public List<Integer> getAllTemplateIds() {
        List<Integer> ids = new ArrayList<>();
        String sql = "SELECT id FROM mobile_template ORDER BY id";
        try (Connection c = DriverManager.getConnection(url, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getInt(1));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list mobile template ids", e);
        }
        return ids;
    }
    
    /**
     * Get a mobile template by key.
     */
    public MobileTemplate getTemplateByKey(String key) {
        String sql = "SELECT * FROM mobile_template WHERE template_key = ?";
           try (Connection c = DriverManager.getConnection(url, USER, PASS);
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
        try (Connection c = DriverManager.getConnection(url, USER, PASS);
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
           try (Connection c = DriverManager.getConnection(url, USER, PASS);
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
            "hp_cur, mp_cur, mv_cur, fortitude_cur, reflex_cur, will_cur, is_dead, spawned_at, died_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        
        try (Connection c = DriverManager.getConnection(System.getProperty("tassmud.db.url", "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1"), USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            long now = System.currentTimeMillis();
            ps.setInt(1, template.getId());
            ps.setInt(2, roomId);
            ps.setInt(3, roomId);
            ps.setInt(4, template.getHpMax());
            ps.setInt(5, template.getMpMax());
            ps.setInt(6, template.getMvMax());
            ps.setInt(7, template.getFortitude());
            ps.setInt(8, template.getReflex());
            ps.setInt(9, template.getWill());
            ps.setBoolean(10, false);
            ps.setLong(11, now);
            ps.setLong(12, 0);
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
        // If an instance already exists for this origin UUID (possibly due to a
        // race or repeated checks), return it instead of inserting a duplicate.
        try {
            Mobile existing = getInstanceByOriginUuid(originUuid);
            if (existing != null) return existing;
        } catch (Exception ignored) {
            // Ignore and proceed to attempt insert; insertion may fail if DB state is odd.
        }
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
        
           try (Connection c = DriverManager.getConnection(url, USER, PASS);
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
        
           try (Connection c = DriverManager.getConnection(url, USER, PASS);
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
            logger.info("MobileDAO: cleared {} mobile instances from DB", deleted);
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
            logger.info("MobileDAO: added {} spawn mapping(s) for template {} in room {}", need, templateId, roomId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to ensure spawn mappings", e);
        }
    }

    /**
     * Persist a mobile -> item marker (equip or inventory).
     */
    public void addMobileItemMarker(long mobileInstanceId, long itemInstanceId, String kind) {
        String sql = "MERGE INTO mobile_instance_item (mobile_instance_id, item_instance_id, kind) KEY (mobile_instance_id, item_instance_id) VALUES (?,?,?)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, mobileInstanceId);
            ps.setLong(2, itemInstanceId);
            ps.setString(3, kind == null ? "inventory" : kind);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add mobile item marker", e);
        }
    }

    /** Simple marker record returned when loading markers. */
    public static class MobileItemMarker { public final long itemInstanceId; public final String kind; public MobileItemMarker(long itemInstanceId, String kind) { this.itemInstanceId = itemInstanceId; this.kind = kind; } }

    /**
     * Get persisted item markers for a mobile instance.
     */
    public List<MobileItemMarker> getMobileItemMarkers(long mobileInstanceId) {
        List<MobileItemMarker> out = new ArrayList<>();
        String sql = "SELECT item_instance_id, kind FROM mobile_instance_item WHERE mobile_instance_id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, mobileInstanceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new MobileItemMarker(rs.getLong(1), rs.getString(2)));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get mobile item markers", e);
        }
        return out;
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

        // Parse keywords from template column (comma-separated)
        String keywordsStr = rs.getString("keywords");
        List<String> keywords = new ArrayList<>();
        if (keywordsStr != null && !keywordsStr.isEmpty()) {
            for (String k : keywordsStr.split("[,]")) {
                String t = k.trim();
                if (!t.isEmpty()) keywords.add(t);
            }
        }

        String origUuid = rs.getString("orig_uuid");
        Mobile mob = new Mobile(
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
            keywords,
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

        // Load persisted mobile->item markers and attach harmless modifiers so death handling can find them
        try {
            List<MobileItemMarker> markers = getMobileItemMarkers(mob.getInstanceId());
            for (MobileItemMarker mm : markers) {
                try {
                    String src = (mm.kind != null && mm.kind.equalsIgnoreCase("equip")) ? "equip#" + mm.itemInstanceId : "inventory#" + mm.itemInstanceId;
                    com.example.tassmud.model.Modifier m = new com.example.tassmud.model.Modifier(src, com.example.tassmud.model.Stat.ATTACK_HIT_BONUS, com.example.tassmud.model.Modifier.Op.ADD, 0, 0L, 0);
                    java.util.UUID id = mob.addModifier(m);
                    mob.addEquipModifier(id);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            logger.warn("MobileDAO: failed to load mobile item markers for instance {}: {}", mob.getInstanceId(), e.getMessage());
        }

        return mob;
    }
}
