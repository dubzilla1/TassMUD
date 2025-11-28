package com.example.tassmud.persistence;

import com.example.tassmud.model.*;
import java.io.InputStream;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class ItemDAO {
    private static final String URL = "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
    private static final String USER = "sa";
    private static final String PASS = "";

    public ItemDAO() {
        ensureTables();
    }

    private void ensureTables() {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS item_template (" +
                        "id INT PRIMARY KEY, template_key VARCHAR(200) UNIQUE, name VARCHAR(500), description VARCHAR(4000), weight DOUBLE, template_value INT, type VARCHAR(50), subtype VARCHAR(50), slot VARCHAR(100), capacity INT, hand_count INT, indestructable BOOLEAN, magical BOOLEAN, max_items INT, max_weight INT, armor_save_bonus INT, fort_save_bonus INT, ref_save_bonus INT, will_save_bonus INT, base_die INT, multiplier INT, hands INT, ability_score VARCHAR(50), ability_multiplier DOUBLE, spell_effect_id_1 VARCHAR(50), spell_effect_id_2 VARCHAR(50), spell_effect_id_3 VARCHAR(50), spell_effect_id_4 VARCHAR(50), traits VARCHAR(500), keywords VARCHAR(500), template_json CLOB)");

                    s.execute("CREATE TABLE IF NOT EXISTS item_instance (" +
                    "instance_id BIGINT AUTO_INCREMENT PRIMARY KEY, template_id INT, location_room_id INT NULL, owner_character_id INT NULL, container_instance_id BIGINT NULL, created_at BIGINT, FOREIGN KEY(template_id) REFERENCES item_template(id))");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to ensure item tables: " + e.getMessage(), e);
        }

        // For existing DBs, ensure new columns exist (migrations)
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("ALTER TABLE item_template ADD COLUMN IF NOT EXISTS traits VARCHAR(500)");
            System.out.println("Migration: ensured column item_template.traits");
            s.execute("ALTER TABLE item_template ADD COLUMN IF NOT EXISTS keywords VARCHAR(500)");
            System.out.println("Migration: ensured column item_template.keywords");
            s.execute("ALTER TABLE item_template ADD COLUMN IF NOT EXISTS template_json CLOB");
            System.out.println("Migration: ensured column item_template.template_json");
            s.execute("ALTER TABLE item_instance ADD COLUMN IF NOT EXISTS container_instance_id BIGINT NULL");
            System.out.println("Migration: ensured column item_instance.container_instance_id");
        } catch (SQLException e) {
            // Best-effort migration; log but don't fail startup
            System.err.println("Warning: failed to run item table migrations: " + e.getMessage());
        }
    }

    public void loadTemplatesFromYamlResource(String resourcePath) throws Exception {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) return;
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(is);
            if (data == null || !data.containsKey("items")) return;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
            for (Map<String, Object> item : items) {
                int id = parseIntSafe(item.get("id"));
                String key = str(item.get("key"));
                String name = str(item.get("name"));
                String desc = str(item.get("description"));
                double weight = parseDoubleSafe(item.get("weight"));
                int value = parseIntSafe(item.get("value"));
                // traits and keywords may be lists in the YAML/JSON
                java.util.List<String> traits = new java.util.ArrayList<>();
                java.util.List<String> keywords = new java.util.ArrayList<>();
                Object tObj = item.get("traits");
                if (tObj instanceof java.util.List) {
                    for (Object o : (java.util.List<?>) tObj) if (o != null) traits.add(o.toString());
                } else if (tObj != null) traits.add(tObj.toString());
                Object kObj = item.get("keywords");
                if (kObj instanceof java.util.List) {
                    for (Object o : (java.util.List<?>) kObj) if (o != null) keywords.add(o.toString());
                } else if (kObj != null) keywords.add(kObj.toString());

                String type = str(item.get("type"));
                String subtype = str(item.get("subtype"));
                // slot may be expressed as equip_slot_id or slot key
                String slot = null;
                Object es = item.get("equip_slot_id");
                if (es != null) slot = es.toString();
                if (slot == null) slot = str(item.get("slot"));
                int capacity = parseIntSafe(item.get("capacity"));
                int handCount = parseIntSafe(item.get("hand_count"));
                boolean indestructable = parseBooleanSafe(item.get("indestructable"));
                boolean magical = parseBooleanSafe(item.get("magical"));
                int maxItems = parseIntSafe(item.get("max_items"));
                int maxWeight = parseIntSafe(item.get("max_weight"));
                int armorSaveBonus = parseIntSafe(item.get("armor_save_bonus"));
                int fortSaveBonus = parseIntSafe(item.get("fort_save_bonus"));
                int refSaveBonus = parseIntSafe(item.get("ref_save_bonus"));
                int willSaveBonus = parseIntSafe(item.get("will_save_bonus"));
                int baseDie = parseIntSafe(item.get("base_die"));
                int multiplier = parseIntSafe(item.get("multiplier"));
                int hands = parseIntSafe(item.get("hands"));
                String abilityScore = str(item.get("ability_score"));
                double abilityMultiplier = parseDoubleSafe(item.get("ability_multiplier"));
                String spellEffectId1 = str(item.get("spell_effect_id_1"));
                String spellEffectId2 = str(item.get("spell_effect_id_2"));
                String spellEffectId3 = str(item.get("spell_effect_id_3"));
                String spellEffectId4 = str(item.get("spell_effect_id_4"));

                // Serialize the original map into a compact YAML/JSON string for storage
                String templateJson = null;
                try {
                    Yaml dumper = new Yaml();
                    templateJson = dumper.dump(item);
                } catch (Exception e) { templateJson = null; }

                 try (Connection c = DriverManager.getConnection(URL, USER, PASS);
                     PreparedStatement ps = c.prepareStatement("MERGE INTO item_template (id,template_key,name,description,weight,template_value,type,subtype,slot,capacity,hand_count,indestructable,magical,max_items,max_weight,armor_save_bonus,fort_save_bonus,ref_save_bonus,will_save_bonus,base_die,multiplier,hands,ability_score,ability_multiplier,spell_effect_id_1,spell_effect_id_2,spell_effect_id_3,spell_effect_id_4,traits,keywords,template_json) KEY(id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setInt(1, id);
                    ps.setString(2, key);
                    ps.setString(3, name);
                    ps.setString(4, desc);
                    ps.setDouble(5, weight);
                    ps.setInt(6, value);
                    ps.setString(7, type);
                    ps.setString(8, subtype);
                    ps.setString(9, slot);
                    ps.setInt(10, capacity);
                    ps.setInt(11, handCount);
                    ps.setBoolean(12, indestructable);
                    ps.setBoolean(13, magical);
                    ps.setInt(14, maxItems);
                    ps.setInt(15, maxWeight);
                    ps.setInt(16, armorSaveBonus);
                    ps.setInt(17, fortSaveBonus);
                    ps.setInt(18, refSaveBonus);
                    ps.setInt(19, willSaveBonus);
                    ps.setInt(20, baseDie);
                    ps.setInt(21, multiplier);
                    ps.setInt(22, hands);
                    ps.setString(23, abilityScore);
                    ps.setDouble(24, abilityMultiplier);
                    ps.setString(25, spellEffectId1);
                    ps.setString(26, spellEffectId2);
                    ps.setString(27, spellEffectId3);
                    ps.setString(28, spellEffectId4);
                    ps.setString(29, String.join(",", traits));
                    ps.setString(30, String.join(",", keywords));
                    if (templateJson == null) ps.setNull(31, Types.CLOB); else ps.setString(31, templateJson);
                    ps.executeUpdate();
                }
            }
            // After loading all templates, log how many are present
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
                 PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) as cnt FROM item_template");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int cnt = rs.getInt("cnt");
                    System.out.println("Loaded item_template rows: " + cnt);
                }
            } catch (SQLException ignore) {}
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
    private static int parseIntSafe(Object o) {
        if (o == null) return 0;
        try { return Integer.parseInt(o.toString().trim()); } catch (Exception e) { return 0; }
    }
    private static double parseDoubleSafe(Object o) {
        if (o == null) return 0.0;
        try { return Double.parseDouble(o.toString().trim()); } catch (Exception e) { return 0.0; }
    }
    private static boolean parseBooleanSafe(Object o) {
        if (o == null) return false;
        String v = o.toString().trim().toLowerCase();
        return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("y");
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }
    private static double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0.0; }
    }
    private static boolean parseBooleanSafe(String s) {
        if (s == null) return false;
        String v = s.trim().toLowerCase();
        return v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("y");
    }

    public long createInstance(int templateId, Integer roomId, Integer characterId) {
        long now = System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement("INSERT INTO item_instance (template_id, location_room_id, owner_character_id, created_at) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, templateId);
            if (roomId == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, roomId);
            if (characterId == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, characterId);
            ps.setLong(4, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { if (rs.next()) return rs.getLong(1); }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create item instance: " + e.getMessage(), e);
        }
        return -1;
    }

    // New overload allowing creation inside a container
    public long createInstance(int templateId, Integer roomId, Integer characterId, Long containerInstanceId) {
        long now = System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement("INSERT INTO item_instance (template_id, location_room_id, owner_character_id, container_instance_id, created_at) VALUES (?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, templateId);
            if (roomId == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, roomId);
            if (characterId == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, characterId);
            if (containerInstanceId == null) ps.setNull(4, Types.BIGINT); else ps.setLong(4, containerInstanceId);
            ps.setLong(5, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { if (rs.next()) return rs.getLong(1); }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create item instance: " + e.getMessage(), e);
        }
        return -1;
    }

    public ItemInstance getInstance(long instanceId) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement("SELECT * FROM item_instance WHERE instance_id = ?")) {
            ps.setLong(1, instanceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Integer room = (Integer) rs.getObject("location_room_id");
                Integer owner = (Integer) rs.getObject("owner_character_id");
                Long container = rs.getObject("container_instance_id") == null ? null : rs.getLong("container_instance_id");
                return new ItemInstance(rs.getLong("instance_id"), rs.getInt("template_id"), room, owner, container, rs.getLong("created_at"));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // --- Equipment-related helpers ---
    public EquipmentSlot getTemplateEquipmentSlot(int templateId) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement("SELECT type, slot, hands FROM item_template WHERE id = ?")) {
            ps.setInt(1, templateId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String type = rs.getString("type");
                String slot = rs.getString("slot");
                int hands = rs.getInt("hands");
                if (type == null) return null;
                type = type.trim().toUpperCase();
                // Armor uses the slot field
                if ("ARMOR".equals(type)) {
                    return EquipmentSlot.fromKey(slot);
                }
                // Weapons go in hand slots based on hands value
                if ("WEAPON".equals(type)) {
                    // For now, all weapons equip to right hand
                    // TODO: 2-handed weapons could occupy both hand slots
                    return EquipmentSlot.RIGHT_HAND;
                }
                return null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    public boolean isTemplateEquipable(int templateId) {
        return getTemplateEquipmentSlot(templateId) != null;
    }

    public void moveInstanceToRoom(long instanceId, int roomId) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement("UPDATE item_instance SET location_room_id = ?, owner_character_id = NULL, container_instance_id = NULL WHERE instance_id = ?")) {
            ps.setInt(1, roomId);
            ps.setLong(2, instanceId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void moveInstanceToCharacter(long instanceId, int characterId) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement("UPDATE item_instance SET owner_character_id = ?, location_room_id = NULL, container_instance_id = NULL WHERE instance_id = ?")) {
            ps.setInt(1, characterId);
            ps.setLong(2, instanceId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public void moveInstanceToContainer(long instanceId, long containerInstanceId) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement("UPDATE item_instance SET container_instance_id = ?, owner_character_id = NULL, location_room_id = NULL WHERE instance_id = ?")) {
            ps.setLong(1, containerInstanceId);
            ps.setLong(2, instanceId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    // Retrieve a template by its numeric ID
    public ItemTemplate getTemplateById(int id) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement("SELECT * FROM item_template WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new ItemTemplate(
                    rs.getInt("id"),
                    rs.getString("template_key"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getDouble("weight"),
                    rs.getInt("template_value"),
                    null, // traits (not hydrated here)
                    null, // keywords (not hydrated here)
                    rs.getString("type"),
                    rs.getString("subtype"),
                    rs.getString("slot"),
                    rs.getInt("capacity"),
                    rs.getInt("hand_count"),
                    rs.getBoolean("indestructable"),
                    rs.getBoolean("magical"),
                    rs.getInt("max_items"),
                    rs.getInt("max_weight"),
                    rs.getInt("armor_save_bonus"),
                    rs.getInt("fort_save_bonus"),
                    rs.getInt("ref_save_bonus"),
                    rs.getInt("will_save_bonus"),
                    rs.getInt("base_die"),
                    rs.getInt("multiplier"),
                    rs.getInt("hands"),
                    rs.getString("ability_score"),
                    rs.getDouble("ability_multiplier"),
                    rs.getString("spell_effect_id_1"),
                    rs.getString("spell_effect_id_2"),
                    rs.getString("spell_effect_id_3"),
                    rs.getString("spell_effect_id_4"),
                    rs.getString("template_json")
                );
            }
        } catch (SQLException e) {
            return null;
        }
    }

    // Check if a template with given ID exists
    public boolean templateExists(int id) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM item_template WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    // A simple record pairing an item instance with its template for display/matching
    public static class RoomItem {
        public final ItemInstance instance;
        public final ItemTemplate template;
        public RoomItem(ItemInstance instance, ItemTemplate template) {
            this.instance = instance;
            this.template = template;
        }
    }

    // Get all item instances in a room, joined with their templates
    public List<RoomItem> getItemsInRoom(int roomId) {
        List<RoomItem> result = new ArrayList<>();
        String sql = "SELECT i.instance_id, i.template_id, i.location_room_id, i.owner_character_id, i.container_instance_id, i.created_at, " +
                     "t.id as tid, t.template_key, t.name, t.description, t.weight, t.template_value, t.type, t.subtype, t.slot, " +
                     "t.capacity, t.hand_count, t.indestructable, t.magical, t.max_items, t.max_weight, " +
                     "t.armor_save_bonus, t.fort_save_bonus, t.ref_save_bonus, t.will_save_bonus, " +
                     "t.base_die, t.multiplier, t.hands, t.ability_score, t.ability_multiplier, " +
                     "t.spell_effect_id_1, t.spell_effect_id_2, t.spell_effect_id_3, t.spell_effect_id_4, " +
                     "t.traits, t.keywords, t.template_json " +
                     "FROM item_instance i JOIN item_template t ON i.template_id = t.id " +
                     "WHERE i.location_room_id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Integer locRoom = (Integer) rs.getObject("location_room_id");
                    Integer owner = (Integer) rs.getObject("owner_character_id");
                    Long container = rs.getObject("container_instance_id") == null ? null : rs.getLong("container_instance_id");
                    ItemInstance inst = new ItemInstance(
                        rs.getLong("instance_id"),
                        rs.getInt("template_id"),
                        locRoom, owner, container,
                        rs.getLong("created_at")
                    );
                    // Parse traits and keywords from comma-separated strings
                    List<String> traits = new ArrayList<>();
                    List<String> keywords = new ArrayList<>();
                    String traitsStr = rs.getString("traits");
                    String keywordsStr = rs.getString("keywords");
                    if (traitsStr != null && !traitsStr.isEmpty()) {
                        for (String s : traitsStr.split(",")) traits.add(s.trim());
                    }
                    if (keywordsStr != null && !keywordsStr.isEmpty()) {
                        for (String s : keywordsStr.split(",")) keywords.add(s.trim());
                    }
                    ItemTemplate tmpl = new ItemTemplate(
                        rs.getInt("tid"),
                        rs.getString("template_key"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getDouble("weight"),
                        rs.getInt("template_value"),
                        traits,
                        keywords,
                        rs.getString("type"),
                        rs.getString("subtype"),
                        rs.getString("slot"),
                        rs.getInt("capacity"),
                        rs.getInt("hand_count"),
                        rs.getBoolean("indestructable"),
                        rs.getBoolean("magical"),
                        rs.getInt("max_items"),
                        rs.getInt("max_weight"),
                        rs.getInt("armor_save_bonus"),
                        rs.getInt("fort_save_bonus"),
                        rs.getInt("ref_save_bonus"),
                        rs.getInt("will_save_bonus"),
                        rs.getInt("base_die"),
                        rs.getInt("multiplier"),
                        rs.getInt("hands"),
                        rs.getString("ability_score"),
                        rs.getDouble("ability_multiplier"),
                        rs.getString("spell_effect_id_1"),
                        rs.getString("spell_effect_id_2"),
                        rs.getString("spell_effect_id_3"),
                        rs.getString("spell_effect_id_4"),
                        rs.getString("template_json")
                    );
                    result.add(new RoomItem(inst, tmpl));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get items in room: " + e.getMessage(), e);
        }
        return result;
    }

    // Get all item instances owned by a character (inventory), joined with their templates
    public List<RoomItem> getItemsByCharacter(int characterId) {
        List<RoomItem> result = new ArrayList<>();
        String sql = "SELECT i.instance_id, i.template_id, i.location_room_id, i.owner_character_id, i.container_instance_id, i.created_at, " +
                     "t.id as tid, t.template_key, t.name, t.description, t.weight, t.template_value, t.type, t.subtype, t.slot, " +
                     "t.capacity, t.hand_count, t.indestructable, t.magical, t.max_items, t.max_weight, " +
                     "t.armor_save_bonus, t.fort_save_bonus, t.ref_save_bonus, t.will_save_bonus, " +
                     "t.base_die, t.multiplier, t.hands, t.ability_score, t.ability_multiplier, " +
                     "t.spell_effect_id_1, t.spell_effect_id_2, t.spell_effect_id_3, t.spell_effect_id_4, " +
                     "t.traits, t.keywords, t.template_json " +
                     "FROM item_instance i JOIN item_template t ON i.template_id = t.id " +
                     "WHERE i.owner_character_id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Integer locRoom = (Integer) rs.getObject("location_room_id");
                    Integer owner = (Integer) rs.getObject("owner_character_id");
                    Long container = rs.getObject("container_instance_id") == null ? null : rs.getLong("container_instance_id");
                    ItemInstance inst = new ItemInstance(
                        rs.getLong("instance_id"),
                        rs.getInt("template_id"),
                        locRoom, owner, container,
                        rs.getLong("created_at")
                    );
                    // Parse traits and keywords from comma-separated strings
                    List<String> traits = new ArrayList<>();
                    List<String> keywords = new ArrayList<>();
                    String traitsStr = rs.getString("traits");
                    String keywordsStr = rs.getString("keywords");
                    if (traitsStr != null && !traitsStr.isEmpty()) {
                        for (String s : traitsStr.split(",")) traits.add(s.trim());
                    }
                    if (keywordsStr != null && !keywordsStr.isEmpty()) {
                        for (String s : keywordsStr.split(",")) keywords.add(s.trim());
                    }
                    ItemTemplate tmpl = new ItemTemplate(
                        rs.getInt("tid"),
                        rs.getString("template_key"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getDouble("weight"),
                        rs.getInt("template_value"),
                        traits,
                        keywords,
                        rs.getString("type"),
                        rs.getString("subtype"),
                        rs.getString("slot"),
                        rs.getInt("capacity"),
                        rs.getInt("hand_count"),
                        rs.getBoolean("indestructable"),
                        rs.getBoolean("magical"),
                        rs.getInt("max_items"),
                        rs.getInt("max_weight"),
                        rs.getInt("armor_save_bonus"),
                        rs.getInt("fort_save_bonus"),
                        rs.getInt("ref_save_bonus"),
                        rs.getInt("will_save_bonus"),
                        rs.getInt("base_die"),
                        rs.getInt("multiplier"),
                        rs.getInt("hands"),
                        rs.getString("ability_score"),
                        rs.getDouble("ability_multiplier"),
                        rs.getString("spell_effect_id_1"),
                        rs.getString("spell_effect_id_2"),
                        rs.getString("spell_effect_id_3"),
                        rs.getString("spell_effect_id_4"),
                        rs.getString("template_json")
                    );
                    result.add(new RoomItem(inst, tmpl));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get items for character: " + e.getMessage(), e);
        }
        return result;
    }
}
