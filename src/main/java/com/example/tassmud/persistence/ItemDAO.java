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
    
    /**
     * Parse comma-separated type string from DB into a list of types.
     * Handles null/empty strings gracefully.
     */
    private static List<String> parseTypesFromDb(String typeStr) {
        List<String> types = new ArrayList<>();
        if (typeStr != null && !typeStr.isBlank()) {
            for (String t : typeStr.split(",")) {
                String trimmed = t.trim().toLowerCase();
                if (!trimmed.isEmpty()) {
                    types.add(trimmed);
                }
            }
        }
        return types;
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
            s.execute("ALTER TABLE item_template ADD COLUMN IF NOT EXISTS weapon_category VARCHAR(50)");
            System.out.println("Migration: ensured column item_template.weapon_category");
            s.execute("ALTER TABLE item_template ADD COLUMN IF NOT EXISTS weapon_family VARCHAR(50)");
            System.out.println("Migration: ensured column item_template.weapon_family");
            s.execute("ALTER TABLE item_template ADD COLUMN IF NOT EXISTS armor_category VARCHAR(50)");
            System.out.println("Migration: ensured column item_template.armor_category");
            s.execute("ALTER TABLE item_instance ADD COLUMN IF NOT EXISTS custom_name VARCHAR(500)");
            System.out.println("Migration: ensured column item_instance.custom_name");
            s.execute("ALTER TABLE item_instance ADD COLUMN IF NOT EXISTS custom_description VARCHAR(4000)");
            System.out.println("Migration: ensured column item_instance.custom_description");
            // Item level system migrations
            s.execute("ALTER TABLE item_template ADD COLUMN IF NOT EXISTS min_item_level INT DEFAULT 1");
            System.out.println("Migration: ensured column item_template.min_item_level");
            s.execute("ALTER TABLE item_template ADD COLUMN IF NOT EXISTS max_item_level INT DEFAULT 1");
            System.out.println("Migration: ensured column item_template.max_item_level");
            s.execute("ALTER TABLE item_instance ADD COLUMN IF NOT EXISTS item_level INT DEFAULT 1");
            System.out.println("Migration: ensured column item_instance.item_level");
            // Gold stored in containers (e.g., corpses)
            s.execute("ALTER TABLE item_instance ADD COLUMN IF NOT EXISTS gold_contents BIGINT DEFAULT 0");
            System.out.println("Migration: ensured column item_instance.gold_contents");
            
            // === Stat override columns for dynamically generated loot ===
            // Weapon stat overrides
            s.execute("ALTER TABLE item_instance ADD COLUMN IF NOT EXISTS base_die_override INT");
            System.out.println("Migration: ensured column item_instance.base_die_override");
            s.execute("ALTER TABLE item_instance ADD COLUMN IF NOT EXISTS multiplier_override INT");
            System.out.println("Migration: ensured column item_instance.multiplier_override");
            s.execute("ALTER TABLE item_instance ADD COLUMN IF NOT EXISTS ability_mult_override DOUBLE");
            System.out.println("Migration: ensured column item_instance.ability_mult_override");
            // Armor/save stat overrides
            s.execute("ALTER TABLE item_instance ADD COLUMN IF NOT EXISTS armor_save_override INT");
            System.out.println("Migration: ensured column item_instance.armor_save_override");
            s.execute("ALTER TABLE item_instance ADD COLUMN IF NOT EXISTS fort_save_override INT");
            System.out.println("Migration: ensured column item_instance.fort_save_override");
            s.execute("ALTER TABLE item_instance ADD COLUMN IF NOT EXISTS ref_save_override INT");
            System.out.println("Migration: ensured column item_instance.ref_save_override");
            s.execute("ALTER TABLE item_instance ADD COLUMN IF NOT EXISTS will_save_override INT");
            System.out.println("Migration: ensured column item_instance.will_save_override");
            // Magic effect overrides
            s.execute("ALTER TABLE item_instance ADD COLUMN IF NOT EXISTS spell_effect_1_override VARCHAR(50)");
            System.out.println("Migration: ensured column item_instance.spell_effect_1_override");
            s.execute("ALTER TABLE item_instance ADD COLUMN IF NOT EXISTS spell_effect_2_override VARCHAR(50)");
            System.out.println("Migration: ensured column item_instance.spell_effect_2_override");
            s.execute("ALTER TABLE item_instance ADD COLUMN IF NOT EXISTS spell_effect_3_override VARCHAR(50)");
            System.out.println("Migration: ensured column item_instance.spell_effect_3_override");
            s.execute("ALTER TABLE item_instance ADD COLUMN IF NOT EXISTS spell_effect_4_override VARCHAR(50)");
            System.out.println("Migration: ensured column item_instance.spell_effect_4_override");
            // Value override and generation flag
            s.execute("ALTER TABLE item_instance ADD COLUMN IF NOT EXISTS value_override INT");
            System.out.println("Migration: ensured column item_instance.value_override");
            s.execute("ALTER TABLE item_instance ADD COLUMN IF NOT EXISTS is_generated BOOLEAN DEFAULT FALSE");
            System.out.println("Migration: ensured column item_instance.is_generated");
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

                // Support both "type" (single) and "types" (list)
                // "types" list takes precedence if present
                java.util.List<String> types = new java.util.ArrayList<>();
                Object typesObj = item.get("types");
                if (typesObj instanceof java.util.List) {
                    for (Object o : (java.util.List<?>) typesObj) {
                        if (o != null && !o.toString().isBlank()) {
                            types.add(o.toString().trim().toLowerCase());
                        }
                    }
                }
                // Fall back to single "type" if "types" not provided
                if (types.isEmpty()) {
                    String singleType = str(item.get("type"));
                    if (singleType != null && !singleType.isBlank()) {
                        types.add(singleType.trim().toLowerCase());
                    }
                }
                // For DB storage, join types with comma (backwards compat with "type" column)
                String type = types.isEmpty() ? null : String.join(",", types);
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
                
                // Weapon categorization
                String weaponCategoryStr = str(item.get("weapon_category"));
                String weaponFamilyStr = str(item.get("weapon_family"));
                
                // Armor categorization
                String armorCategoryStr = str(item.get("armor_category"));

                // Item level range (for potions, scrolls, etc.)
                int minItemLevel = parseIntSafe(item.get("min_item_level"));
                int maxItemLevel = parseIntSafe(item.get("max_item_level"));
                // Default to 1 if not specified
                if (minItemLevel <= 0) minItemLevel = 1;
                if (maxItemLevel <= 0) maxItemLevel = minItemLevel;
                // Ensure max >= min
                if (maxItemLevel < minItemLevel) maxItemLevel = minItemLevel;

                // Serialize the original map into a compact YAML/JSON string for storage
                String templateJson = null;
                try {
                    Yaml dumper = new Yaml();
                    templateJson = dumper.dump(item);
                } catch (Exception e) { templateJson = null; }

                 try (Connection c = DriverManager.getConnection(URL, USER, PASS);
                     PreparedStatement ps = c.prepareStatement("MERGE INTO item_template (id,template_key,name,description,weight,template_value,type,subtype,slot,capacity,hand_count,indestructable,magical,max_items,max_weight,armor_save_bonus,fort_save_bonus,ref_save_bonus,will_save_bonus,base_die,multiplier,hands,ability_score,ability_multiplier,spell_effect_id_1,spell_effect_id_2,spell_effect_id_3,spell_effect_id_4,traits,keywords,template_json,weapon_category,weapon_family,armor_category,min_item_level,max_item_level) KEY(id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
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
                    ps.setString(32, weaponCategoryStr);
                    ps.setString(33, weaponFamilyStr);
                    ps.setString(34, armorCategoryStr);
                    ps.setInt(35, minItemLevel);
                    ps.setInt(36, maxItemLevel);
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



    public long createInstance(int templateId, Integer roomId, Integer characterId) {
        // Get template to determine item level range
        ItemTemplate template = getTemplateById(templateId);
        int itemLevel = 1;
        if (template != null) {
            int min = template.minItemLevel;
            int max = template.maxItemLevel;
            if (min > 0 && max >= min) {
                itemLevel = min + (int)(Math.random() * (max - min + 1));
            }
        }
        
        long now = System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement("INSERT INTO item_instance (template_id, location_room_id, owner_character_id, created_at, item_level) VALUES (?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, templateId);
            if (roomId == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, roomId);
            if (characterId == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, characterId);
            ps.setLong(4, now);
            ps.setInt(5, itemLevel);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { if (rs.next()) return rs.getLong(1); }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create item instance: " + e.getMessage(), e);
        }
        return -1;
    }

    // New overload allowing creation inside a container
    public long createInstance(int templateId, Integer roomId, Integer characterId, Long containerInstanceId) {
        // Get template to determine item level range
        ItemTemplate template = getTemplateById(templateId);
        int itemLevel = 1;
        if (template != null) {
            int min = template.minItemLevel;
            int max = template.maxItemLevel;
            if (min > 0 && max >= min) {
                itemLevel = min + (int)(Math.random() * (max - min + 1));
            }
        }
        
        long now = System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement("INSERT INTO item_instance (template_id, location_room_id, owner_character_id, container_instance_id, created_at, item_level) VALUES (?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, templateId);
            if (roomId == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, roomId);
            if (characterId == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, characterId);
            if (containerInstanceId == null) ps.setNull(4, Types.BIGINT); else ps.setLong(4, containerInstanceId);
            ps.setLong(5, now);
            ps.setInt(6, itemLevel);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { if (rs.next()) return rs.getLong(1); }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create item instance: " + e.getMessage(), e);
        }
        return -1;
    }

    /**
     * Create a dynamically generated item instance with stat overrides.
     * Used by LootGenerator for mob drops with randomized stats.
     * 
     * @param templateId Base item template ID
     * @param containerInstanceId Container to place item in (e.g., corpse)
     * @param customName Custom name for the item (null to use template name)
     * @param customDescription Custom description (null to use template)
     * @param itemLevel Item level for scaling
     * @param baseDieOverride Weapon base die override (null for template value)
     * @param multiplierOverride Weapon multiplier override (null for template value)
     * @param abilityMultOverride Ability multiplier override (null for template value)
     * @param armorSaveOverride Armor save bonus override (null for template value)
     * @param fortSaveOverride Fort save bonus override (null for template value)
     * @param refSaveOverride Ref save bonus override (null for template value)
     * @param willSaveOverride Will save bonus override (null for template value)
     * @param spellEffect1 Spell effect 1 ID override (null for template value)
     * @param spellEffect2 Spell effect 2 ID override (null for template value)
     * @param spellEffect3 Spell effect 3 ID override (null for template value)
     * @param spellEffect4 Spell effect 4 ID override (null for template value)
     * @param valueOverride Gold value override (null for template value)
     * @return The created instance ID, or -1 on failure
     */
    public long createGeneratedInstance(
            int templateId, Long containerInstanceId,
            String customName, String customDescription, int itemLevel,
            Integer baseDieOverride, Integer multiplierOverride, Double abilityMultOverride,
            Integer armorSaveOverride, Integer fortSaveOverride, Integer refSaveOverride, Integer willSaveOverride,
            String spellEffect1, String spellEffect2, String spellEffect3, String spellEffect4,
            Integer valueOverride) {
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO item_instance (" +
            "template_id, container_instance_id, created_at, custom_name, custom_description, item_level, " +
            "base_die_override, multiplier_override, ability_mult_override, " +
            "armor_save_override, fort_save_override, ref_save_override, will_save_override, " +
            "spell_effect_1_override, spell_effect_2_override, spell_effect_3_override, spell_effect_4_override, " +
            "value_override, is_generated) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,TRUE)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int idx = 1;
            ps.setInt(idx++, templateId);
            if (containerInstanceId == null) ps.setNull(idx++, Types.BIGINT); else ps.setLong(idx++, containerInstanceId);
            ps.setLong(idx++, now);
            if (customName == null) ps.setNull(idx++, Types.VARCHAR); else ps.setString(idx++, customName);
            if (customDescription == null) ps.setNull(idx++, Types.VARCHAR); else ps.setString(idx++, customDescription);
            ps.setInt(idx++, itemLevel);
            if (baseDieOverride == null) ps.setNull(idx++, Types.INTEGER); else ps.setInt(idx++, baseDieOverride);
            if (multiplierOverride == null) ps.setNull(idx++, Types.INTEGER); else ps.setInt(idx++, multiplierOverride);
            if (abilityMultOverride == null) ps.setNull(idx++, Types.DOUBLE); else ps.setDouble(idx++, abilityMultOverride);
            if (armorSaveOverride == null) ps.setNull(idx++, Types.INTEGER); else ps.setInt(idx++, armorSaveOverride);
            if (fortSaveOverride == null) ps.setNull(idx++, Types.INTEGER); else ps.setInt(idx++, fortSaveOverride);
            if (refSaveOverride == null) ps.setNull(idx++, Types.INTEGER); else ps.setInt(idx++, refSaveOverride);
            if (willSaveOverride == null) ps.setNull(idx++, Types.INTEGER); else ps.setInt(idx++, willSaveOverride);
            if (spellEffect1 == null) ps.setNull(idx++, Types.VARCHAR); else ps.setString(idx++, spellEffect1);
            if (spellEffect2 == null) ps.setNull(idx++, Types.VARCHAR); else ps.setString(idx++, spellEffect2);
            if (spellEffect3 == null) ps.setNull(idx++, Types.VARCHAR); else ps.setString(idx++, spellEffect3);
            if (spellEffect4 == null) ps.setNull(idx++, Types.VARCHAR); else ps.setString(idx++, spellEffect4);
            if (valueOverride == null) ps.setNull(idx++, Types.INTEGER); else ps.setInt(idx++, valueOverride);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { if (rs.next()) return rs.getLong(1); }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create generated item instance: " + e.getMessage(), e);
        }
        return -1;
    }
    
    /**
     * Create a dynamically generated item instance in a room.
     * Used by GM spawn command with loot generation logic.
     * 
     * @param templateId Base item template ID
     * @param roomId Room to place item in
     * @param customName Custom name for the item (null to use template name)
     * @param customDescription Custom description (null to use template)
     * @param itemLevel Item level for scaling
     * @param baseDieOverride Weapon base die override (null for template value)
     * @param multiplierOverride Weapon multiplier override (null for template value)
     * @param abilityMultOverride Ability multiplier override (null for template value)
     * @param armorSaveOverride Armor save bonus override (null for template value)
     * @param fortSaveOverride Fort save bonus override (null for template value)
     * @param refSaveOverride Ref save bonus override (null for template value)
     * @param willSaveOverride Will save bonus override (null for template value)
     * @param spellEffect1 Spell effect 1 ID override (null for template value)
     * @param spellEffect2 Spell effect 2 ID override (null for template value)
     * @param spellEffect3 Spell effect 3 ID override (null for template value)
     * @param spellEffect4 Spell effect 4 ID override (null for template value)
     * @param valueOverride Gold value override (null for template value)
     * @return The created instance ID, or -1 on failure
     */
    public long createGeneratedInstanceInRoom(
            int templateId, int roomId,
            String customName, String customDescription, int itemLevel,
            Integer baseDieOverride, Integer multiplierOverride, Double abilityMultOverride,
            Integer armorSaveOverride, Integer fortSaveOverride, Integer refSaveOverride, Integer willSaveOverride,
            String spellEffect1, String spellEffect2, String spellEffect3, String spellEffect4,
            Integer valueOverride) {
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO item_instance (" +
            "template_id, location_room_id, created_at, custom_name, custom_description, item_level, " +
            "base_die_override, multiplier_override, ability_mult_override, " +
            "armor_save_override, fort_save_override, ref_save_override, will_save_override, " +
            "spell_effect_1_override, spell_effect_2_override, spell_effect_3_override, spell_effect_4_override, " +
            "value_override, is_generated) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,TRUE)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int idx = 1;
            ps.setInt(idx++, templateId);
            ps.setInt(idx++, roomId);
            ps.setLong(idx++, now);
            if (customName == null) ps.setNull(idx++, Types.VARCHAR); else ps.setString(idx++, customName);
            if (customDescription == null) ps.setNull(idx++, Types.VARCHAR); else ps.setString(idx++, customDescription);
            ps.setInt(idx++, itemLevel);
            if (baseDieOverride == null) ps.setNull(idx++, Types.INTEGER); else ps.setInt(idx++, baseDieOverride);
            if (multiplierOverride == null) ps.setNull(idx++, Types.INTEGER); else ps.setInt(idx++, multiplierOverride);
            if (abilityMultOverride == null) ps.setNull(idx++, Types.DOUBLE); else ps.setDouble(idx++, abilityMultOverride);
            if (armorSaveOverride == null) ps.setNull(idx++, Types.INTEGER); else ps.setInt(idx++, armorSaveOverride);
            if (fortSaveOverride == null) ps.setNull(idx++, Types.INTEGER); else ps.setInt(idx++, fortSaveOverride);
            if (refSaveOverride == null) ps.setNull(idx++, Types.INTEGER); else ps.setInt(idx++, refSaveOverride);
            if (willSaveOverride == null) ps.setNull(idx++, Types.INTEGER); else ps.setInt(idx++, willSaveOverride);
            if (spellEffect1 == null) ps.setNull(idx++, Types.VARCHAR); else ps.setString(idx++, spellEffect1);
            if (spellEffect2 == null) ps.setNull(idx++, Types.VARCHAR); else ps.setString(idx++, spellEffect2);
            if (spellEffect3 == null) ps.setNull(idx++, Types.VARCHAR); else ps.setString(idx++, spellEffect3);
            if (spellEffect4 == null) ps.setNull(idx++, Types.VARCHAR); else ps.setString(idx++, spellEffect4);
            if (valueOverride == null) ps.setNull(idx++, Types.INTEGER); else ps.setInt(idx++, valueOverride);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { if (rs.next()) return rs.getLong(1); }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create generated item instance in room: " + e.getMessage(), e);
        }
        return -1;
    }

    public ItemInstance getInstance(long instanceId) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement("SELECT * FROM item_instance WHERE instance_id = ?")) {
            ps.setLong(1, instanceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return extractItemInstance(rs);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
    
    /**
     * Helper to extract ItemInstance from a ResultSet row.
     * Handles all columns including stat overrides for generated items.
     */
    private ItemInstance extractItemInstance(ResultSet rs) throws SQLException {
        Integer room = (Integer) rs.getObject("location_room_id");
        Integer owner = (Integer) rs.getObject("owner_character_id");
        Long container = rs.getObject("container_instance_id") == null ? null : rs.getLong("container_instance_id");
        String customName = rs.getString("custom_name");
        String customDesc = rs.getString("custom_description");
        int itemLevel = rs.getInt("item_level");
        if (itemLevel <= 0) itemLevel = 1;
        
        // Stat overrides (null if not set)
        Integer baseDieOverride = (Integer) rs.getObject("base_die_override");
        Integer multiplierOverride = (Integer) rs.getObject("multiplier_override");
        Double abilityMultOverride = (Double) rs.getObject("ability_mult_override");
        Integer armorSaveOverride = (Integer) rs.getObject("armor_save_override");
        Integer fortSaveOverride = (Integer) rs.getObject("fort_save_override");
        Integer refSaveOverride = (Integer) rs.getObject("ref_save_override");
        Integer willSaveOverride = (Integer) rs.getObject("will_save_override");
        String spellEffect1Override = rs.getString("spell_effect_1_override");
        String spellEffect2Override = rs.getString("spell_effect_2_override");
        String spellEffect3Override = rs.getString("spell_effect_3_override");
        String spellEffect4Override = rs.getString("spell_effect_4_override");
        Integer valueOverride = (Integer) rs.getObject("value_override");
        boolean isGenerated = rs.getBoolean("is_generated");
        
        return new ItemInstance(
            rs.getLong("instance_id"), rs.getInt("template_id"), room, owner, container, 
            rs.getLong("created_at"), customName, customDesc, itemLevel,
            baseDieOverride, multiplierOverride, abilityMultOverride,
            armorSaveOverride, fortSaveOverride, refSaveOverride, willSaveOverride,
            spellEffect1Override, spellEffect2Override, spellEffect3Override, spellEffect4Override,
            valueOverride, isGenerated
        );
    }

    // --- Corpse-related methods ---
    
    /** The template ID for corpse items (defined in items.yaml) */
    public static final int CORPSE_TEMPLATE_ID = 999;
    
    /**
     * Create a corpse item in a room with custom name and description.
     * The corpse acts as a container for loot.
     * @param roomId The room where the corpse spawns
     * @param mobName The name of the mob that died (used for name/description)
     * @return The instance ID of the created corpse
     */
    public long createCorpse(int roomId, String mobName) {
        return createCorpse(roomId, mobName, 0);
    }
    
    /**
     * Create a corpse item in a room with custom name, description, and gold.
     * The corpse acts as a container for loot.
     * @param roomId The room where the corpse spawns
     * @param mobName The name of the mob that died (used for name/description)
     * @param gold The amount of gold to put in the corpse
     * @return The instance ID of the created corpse
     */
    public long createCorpse(int roomId, String mobName, long gold) {
        String customName = "The corpse of " + mobName;
        String customDesc = "The corpse of " + mobName + " lies here.";
        
        long now = System.currentTimeMillis();
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO item_instance (template_id, location_room_id, owner_character_id, container_instance_id, created_at, custom_name, custom_description, gold_contents) VALUES (?,?,?,?,?,?,?,?)",
                 Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, CORPSE_TEMPLATE_ID);
            ps.setInt(2, roomId);
            ps.setNull(3, Types.INTEGER);
            ps.setNull(4, Types.BIGINT);
            ps.setLong(5, now);
            ps.setString(6, customName);
            ps.setString(7, customDesc);
            ps.setLong(8, gold);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create corpse: " + e.getMessage(), e);
        }
        return -1;
    }
    
    /**
     * Get the gold contents of an item instance (e.g., a corpse).
     * @param instanceId The instance ID to check
     * @return The amount of gold in the container
     */
    public long getGoldContents(long instanceId) {
        String sql = "SELECT gold_contents FROM item_instance WHERE instance_id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, instanceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("gold_contents");
                }
            }
        } catch (SQLException e) {
            System.err.println("[ItemDAO] Failed to get gold contents: " + e.getMessage());
        }
        return 0;
    }
    
    /**
     * Take gold from an item instance (e.g., looting a corpse).
     * @param instanceId The instance ID to loot
     * @return The amount of gold taken (0 if none or error)
     */
    public long takeGoldContents(long instanceId) {
        long gold = getGoldContents(instanceId);
        if (gold > 0) {
            String sql = "UPDATE item_instance SET gold_contents = 0 WHERE instance_id = ?";
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, instanceId);
                ps.executeUpdate();
                return gold;
            } catch (SQLException e) {
                System.err.println("[ItemDAO] Failed to take gold: " + e.getMessage());
            }
        }
        return 0;
    }

    /**
     * Delete an item instance from the game world.
     * @param instanceId The instance ID to delete
     * @return true if deleted, false otherwise
     */
    public boolean deleteInstance(long instanceId) {
        String sql = "DELETE FROM item_instance WHERE instance_id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, instanceId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.err.println("[ItemDAO] Failed to delete instance " + instanceId + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Delete all empty corpses (corpses with no items inside) from a specific room.
     * @param roomId The room to clean up
     * @return Number of corpses deleted
     */
    public int deleteEmptyCorpsesInRoom(int roomId) {
        // Find corpses that have no items inside
        String sql = "DELETE FROM item_instance WHERE template_id = ? AND location_room_id = ? " +
                     "AND instance_id NOT IN (SELECT DISTINCT container_instance_id FROM item_instance WHERE container_instance_id IS NOT NULL)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, CORPSE_TEMPLATE_ID);
            ps.setInt(2, roomId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ItemDAO] Failed to delete empty corpses: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * Delete all empty corpses from the game world.
     * @return Number of corpses deleted
     */
    public int deleteAllEmptyCorpses() {
        String sql = "DELETE FROM item_instance WHERE template_id = ? " +
                     "AND instance_id NOT IN (SELECT DISTINCT container_instance_id FROM item_instance WHERE container_instance_id IS NOT NULL)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, CORPSE_TEMPLATE_ID);
            return ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[ItemDAO] Failed to delete all empty corpses: " + e.getMessage());
            return 0;
        }
    }

    // --- Equipment-related helpers ---
    public EquipmentSlot getTemplateEquipmentSlot(int templateId) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement("SELECT type, slot FROM item_template WHERE id = ?")) {
            ps.setInt(1, templateId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String type = rs.getString("type");
                String slot = rs.getString("slot");
                if (type == null) return null;
                type = type.trim().toUpperCase();
                // Armor uses the slot field
                if ("ARMOR".equals(type)) {
                    return EquipmentSlot.fromKey(slot);
                }
                // Shields go in off hand
                if ("SHIELD".equals(type)) {
                    return EquipmentSlot.OFF_HAND;
                }
                // Weapons go in main hand (two-handers handled separately in ClientHandler)
                if ("WEAPON".equals(type)) {
                    return EquipmentSlot.MAIN_HAND;
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

    /**
     * Check if a weapon template requires two hands.
     * Returns true for weapons with hands=2.
     */
    public boolean isTemplateTwoHanded(int templateId) {
        String sql = "SELECT type, hands FROM item_template WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, templateId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                String type = rs.getString("type");
                int hands = rs.getInt("hands");
                if (type == null) return false;
                type = type.trim().toUpperCase();
                return ("WEAPON".equals(type) || "HELD".equals(type)) && hands >= 2;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Check if a template is a shield type item.
     */
    public boolean isTemplateShield(int templateId) {
        String sql = "SELECT type FROM item_template WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, templateId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                String type = rs.getString("type");
                if (type == null) return false;
                return "SHIELD".equals(type.trim().toUpperCase());
            }
        } catch (SQLException e) {
            return false;
        }
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
                    parseTypesFromDb(rs.getString("type")), // Parse comma-separated types
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
                    rs.getString("template_json"),
                    WeaponCategory.fromString(rs.getString("weapon_category")),
                    WeaponFamily.fromString(rs.getString("weapon_family")),
                    ArmorCategory.fromString(rs.getString("armor_category")),
                    rs.getInt("min_item_level"),
                    rs.getInt("max_item_level")
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
        String sql = "SELECT i.*, " +
                     "t.id as tid, t.template_key, t.name, t.description, t.weight, t.template_value, t.type, t.subtype, t.slot, " +
                     "t.capacity, t.hand_count, t.indestructable, t.magical, t.max_items, t.max_weight, " +
                     "t.armor_save_bonus, t.fort_save_bonus, t.ref_save_bonus, t.will_save_bonus, " +
                     "t.base_die, t.multiplier, t.hands, t.ability_score, t.ability_multiplier, " +
                     "t.spell_effect_id_1, t.spell_effect_id_2, t.spell_effect_id_3, t.spell_effect_id_4, " +
                     "t.traits, t.keywords, t.template_json, t.weapon_category, t.weapon_family, t.armor_category, t.min_item_level, t.max_item_level " +
                     "FROM item_instance i JOIN item_template t ON i.template_id = t.id " +
                     "WHERE i.location_room_id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ItemInstance inst = extractItemInstance(rs);
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
                        parseTypesFromDb(rs.getString("type")), // Parse comma-separated types
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
                        rs.getString("template_json"),
                        WeaponCategory.fromString(rs.getString("weapon_category")),
                        WeaponFamily.fromString(rs.getString("weapon_family")),
                        ArmorCategory.fromString(rs.getString("armor_category")),
                        rs.getInt("min_item_level"),
                        rs.getInt("max_item_level")
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
        String sql = "SELECT i.*, " +
                     "t.id as tid, t.template_key, t.name, t.description, t.weight, t.template_value, t.type, t.subtype, t.slot, " +
                     "t.capacity, t.hand_count, t.indestructable, t.magical, t.max_items, t.max_weight, " +
                     "t.armor_save_bonus, t.fort_save_bonus, t.ref_save_bonus, t.will_save_bonus, " +
                     "t.base_die, t.multiplier, t.hands, t.ability_score, t.ability_multiplier, " +
                     "t.spell_effect_id_1, t.spell_effect_id_2, t.spell_effect_id_3, t.spell_effect_id_4, " +
                     "t.traits, t.keywords, t.template_json, t.weapon_category, t.weapon_family, t.armor_category, t.min_item_level, t.max_item_level " +
                     "FROM item_instance i JOIN item_template t ON i.template_id = t.id " +
                     "WHERE i.owner_character_id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ItemInstance inst = extractItemInstance(rs);
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
                        parseTypesFromDb(rs.getString("type")), // Parse comma-separated types
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
                        rs.getString("template_json"),
                        WeaponCategory.fromString(rs.getString("weapon_category")),
                        WeaponFamily.fromString(rs.getString("weapon_family")),
                        ArmorCategory.fromString(rs.getString("armor_category")),
                        rs.getInt("min_item_level"),
                        rs.getInt("max_item_level")
                    );
                    result.add(new RoomItem(inst, tmpl));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get items for character: " + e.getMessage(), e);
        }
        return result;
    }

    // Get all item instances inside a container, joined with their templates
    public List<RoomItem> getItemsInContainer(long containerInstanceId) {
        List<RoomItem> result = new ArrayList<>();
        String sql = "SELECT i.*, " +
                     "t.id as tid, t.template_key, t.name, t.description, t.weight, t.template_value, t.type, t.subtype, t.slot, " +
                     "t.capacity, t.hand_count, t.indestructable, t.magical, t.max_items, t.max_weight, " +
                     "t.armor_save_bonus, t.fort_save_bonus, t.ref_save_bonus, t.will_save_bonus, " +
                     "t.base_die, t.multiplier, t.hands, t.ability_score, t.ability_multiplier, " +
                     "t.spell_effect_id_1, t.spell_effect_id_2, t.spell_effect_id_3, t.spell_effect_id_4, " +
                     "t.traits, t.keywords, t.template_json, t.weapon_category, t.weapon_family, t.armor_category, t.min_item_level, t.max_item_level " +
                     "FROM item_instance i JOIN item_template t ON i.template_id = t.id " +
                     "WHERE i.container_instance_id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, containerInstanceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ItemInstance inst = extractItemInstance(rs);
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
                        parseTypesFromDb(rs.getString("type")), // Parse comma-separated types
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
                        rs.getString("template_json"),
                        WeaponCategory.fromString(rs.getString("weapon_category")),
                        WeaponFamily.fromString(rs.getString("weapon_family")),
                        ArmorCategory.fromString(rs.getString("armor_category")),
                        rs.getInt("min_item_level"),
                        rs.getInt("max_item_level")
                    );
                    result.add(new RoomItem(inst, tmpl));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get items in container: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * Search item templates by name (case-insensitive, starts-with or contains match).
     * Returns a list of matching ItemTemplate objects.
     */
    public java.util.List<ItemTemplate> searchItemTemplates(String searchStr) {
        java.util.List<ItemTemplate> results = new java.util.ArrayList<>();
        if (searchStr == null || searchStr.trim().isEmpty()) return results;
        String pattern = "%" + searchStr.trim().toLowerCase() + "%";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(
                 "SELECT * FROM item_template WHERE LOWER(name) LIKE ? ORDER BY id")) {
            ps.setString(1, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new ItemTemplate(
                        rs.getInt("id"),
                        rs.getString("template_key"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getDouble("weight"),
                        rs.getInt("template_value"),
                        null, // traits
                        null, // keywords
                        parseTypesFromDb(rs.getString("type")), // Parse comma-separated types
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
                        rs.getString("template_json"),
                        WeaponCategory.fromString(rs.getString("weapon_category")),
                        WeaponFamily.fromString(rs.getString("weapon_family")),
                        ArmorCategory.fromString(rs.getString("armor_category")),
                        rs.getInt("min_item_level"),
                        rs.getInt("max_item_level")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to search item templates: " + e.getMessage(), e);
        }
        return results;
    }

    /**
     * Find all item instances with a given template ID.
     * Returns a list of ItemInstance objects.
     */
    public java.util.List<ItemInstance> findInstancesByTemplateId(int templateId) {
        java.util.List<ItemInstance> results = new java.util.ArrayList<>();
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(
                 "SELECT * FROM item_instance WHERE template_id = ? ORDER BY instance_id")) {
            ps.setInt(1, templateId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(extractItemInstance(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to find item instances: " + e.getMessage(), e);
        }
        return results;
    }

    /**
     * Get an item instance by its instance ID.
     */
    public ItemInstance getInstanceById(long instanceId) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement("SELECT * FROM item_instance WHERE instance_id = ?")) {
            ps.setLong(1, instanceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return extractItemInstance(rs);
                }
            }
        } catch (SQLException e) {
            // ignore
        }
        return null;
    }
}
