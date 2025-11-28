package com.example.tassmud.persistence;

import com.example.tassmud.model.*;
import com.example.tassmud.model.MobileBehavior;
import com.example.tassmud.model.MobileTemplate;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.*;

public class DataLoader {

    // Simple CSV loader for initial data. Files are in classpath under /data/
    // - skills.csv: name,description
    // - spells.csv: name,description
    // - areas.csv: name,description
    // - rooms.csv: key,name,area,short_desc,long_desc,exit_n,exit_e,exit_s,exit_w,exit_u,exit_d
    // Room exits reference room `key` values (resolved after insertion)

    public static void loadDefaults(CharacterDAO dao) {
        loadSkills(dao);
        loadSpells(dao);
        Map<String,Integer> areaMap = loadAreas(dao);
        Map<String,Integer> roomKeyToId = loadRoomsFirstPass(dao, areaMap);
        loadRoomsSecondPass(dao, roomKeyToId);
        // Load item templates from YAML resource into item_template table
        try {
            ItemDAO itemDao = new ItemDAO();
            itemDao.loadTemplatesFromYamlResource("/data/items.yaml");
        } catch (Exception e) {
            System.err.println("Failed to load items.yaml: " + e.getMessage());
        }
        // Load character classes from YAML resource
        try {
            CharacterClassDAO classDao = new CharacterClassDAO();
            classDao.loadClassesFromYamlResource("/data/classes.yaml");
        } catch (Exception e) {
            System.err.println("Failed to load classes.yaml: " + e.getMessage());
        }
        // Load mobile templates from YAML resource
        try {
            loadMobileTemplates();
        } catch (Exception e) {
            System.err.println("Failed to load mobiles.yaml: " + e.getMessage());
        }
    }
    
    /**
     * Load mobile templates from YAML resource.
     */
    @SuppressWarnings("unchecked")
    private static void loadMobileTemplates() {
        try (InputStream in = DataLoader.class.getResourceAsStream("/data/mobiles.yaml")) {
            if (in == null) {
                System.out.println("No mobiles.yaml found");
                return;
            }
            
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            List<Map<String, Object>> mobileList = yaml.load(in);
            if (mobileList == null) return;
            
            MobileDAO mobileDao = new MobileDAO();
            int count = 0;
            
            for (Map<String, Object> mobData : mobileList) {
                int id = getInt(mobData, "id", -1);
                if (id < 0) continue;
                
                String key = getString(mobData, "key", "mob_" + id);
                String name = getString(mobData, "name", "Unknown");
                String shortDesc = getString(mobData, "short_desc", name + " is here.");
                String longDesc = getString(mobData, "long_desc", "You see nothing special.");
                
                // Parse keywords
                List<String> keywords = new ArrayList<>();
                Object keywordsObj = mobData.get("keywords");
                if (keywordsObj instanceof List) {
                    for (Object kw : (List<?>) keywordsObj) {
                        keywords.add(String.valueOf(kw));
                    }
                }
                
                int level = getInt(mobData, "level", 1);
                int hpMax = getInt(mobData, "hp_max", 10);
                int mpMax = getInt(mobData, "mp_max", 0);
                int mvMax = getInt(mobData, "mv_max", 100);
                
                int str = getInt(mobData, "str", 10);
                int dex = getInt(mobData, "dex", 10);
                int con = getInt(mobData, "con", 10);
                int intel = getInt(mobData, "intel", 10);
                int wis = getInt(mobData, "wis", 10);
                int cha = getInt(mobData, "cha", 10);
                
                int armor = getInt(mobData, "armor", 10);
                int fortitude = getInt(mobData, "fortitude", 0);
                int reflex = getInt(mobData, "reflex", 0);
                int will = getInt(mobData, "will", 0);
                
                int baseDamage = getInt(mobData, "base_damage", 4);
                int damageBonus = getInt(mobData, "damage_bonus", 0);
                int attackBonus = getInt(mobData, "attack_bonus", 0);
                
                // Parse behaviors list (can be single string or list of strings)
                List<MobileBehavior> behaviors = new ArrayList<>();
                Object behaviorsObj = mobData.get("behaviors");
                if (behaviorsObj instanceof List) {
                    for (Object b : (List<?>) behaviorsObj) {
                        MobileBehavior behavior = MobileBehavior.fromString(String.valueOf(b));
                        if (behavior != null) {
                            behaviors.add(behavior);
                        }
                    }
                } else if (behaviorsObj instanceof String) {
                    MobileBehavior behavior = MobileBehavior.fromString((String) behaviorsObj);
                    if (behavior != null) {
                        behaviors.add(behavior);
                    }
                }
                // Also check legacy "behavior" field for backwards compatibility
                if (behaviors.isEmpty()) {
                    String behaviorStr = getString(mobData, "behavior", "PASSIVE");
                    MobileBehavior behavior = MobileBehavior.fromString(behaviorStr);
                    behaviors.add(behavior != null ? behavior : MobileBehavior.PASSIVE);
                }
                
                int aggroRange = getInt(mobData, "aggro_range", 0);
                int experienceValue = getInt(mobData, "experience_value", 10);
                int goldMin = getInt(mobData, "gold_min", 0);
                int goldMax = getInt(mobData, "gold_max", 0);
                int respawnSeconds = getInt(mobData, "respawn_seconds", 300);
                
                MobileTemplate template = new MobileTemplate(
                    id, key, name, shortDesc, longDesc, keywords,
                    level, hpMax, mpMax, mvMax,
                    str, dex, con, intel, wis, cha,
                    armor, fortitude, reflex, will,
                    baseDamage, damageBonus, attackBonus,
                    behaviors, aggroRange,
                    experienceValue, goldMin, goldMax,
                    respawnSeconds, null
                );
                
                mobileDao.upsertTemplate(template);
                count++;
            }
            
            System.out.println("Loaded " + count + " mobile templates from mobiles.yaml");
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to load mobile templates: " + e.getMessage(), e);
        }
    }

    private static void loadSkills(CharacterDAO dao) {
        loadSkillsFromYaml(dao);
    }
    
    /**
     * Load skills from YAML resource. Falls back to CSV if YAML not found.
     */
    @SuppressWarnings("unchecked")
    private static void loadSkillsFromYaml(CharacterDAO dao) {
        try (InputStream in = DataLoader.class.getResourceAsStream("/data/skills.yaml")) {
            if (in == null) {
                // Fallback to old CSV
                loadSkillsFromCsv(dao);
                return;
            }
            
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            Map<String, Object> root = yaml.load(in);
            if (root == null) return;
            
            List<Map<String, Object>> skillList = (List<Map<String, Object>>) root.get("skills");
            if (skillList == null) return;
            
            int count = 0;
            for (Map<String, Object> skillData : skillList) {
                int id = getInt(skillData, "id", -1);
                String key = getString(skillData, "key", "");
                String name = getString(skillData, "name", "");
                String description = getString(skillData, "description", "");
                boolean isPassive = getBoolean(skillData, "is_passive", false);
                int maxLevel = getInt(skillData, "max_level", 100);
                String progressionStr = getString(skillData, "progression", "NORMAL");
                
                if (id < 0 || key.isEmpty() || name.isEmpty()) continue;
                
                com.example.tassmud.model.Skill.SkillProgression progression = 
                    com.example.tassmud.model.Skill.SkillProgression.fromString(progressionStr);
                
                if (dao.addSkillFull(id, key, name, description, isPassive, maxLevel, progression)) {
                    count++;
                }
            }
            System.out.println("[DataLoader] Loaded " + count + " skills from YAML");
        } catch (Exception e) {
            System.err.println("[DataLoader] Failed to load skills from YAML: " + e.getMessage());
            loadSkillsFromCsv(dao);
        }
    }
    
    /**
     * Legacy CSV skill loader (fallback).
     */
    private static void loadSkillsFromCsv(CharacterDAO dao) {
        try (InputStream in = DataLoader.class.getResourceAsStream("/data/skills.csv")) {
            if (in == null) return;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split(",", 2);
                    String name = parts[0].trim();
                    String desc = parts.length > 1 ? parts[1].trim() : "";
                    dao.addSkill(name, desc);
                }
            }
        } catch (Exception ignored) {}
    }

    private static void loadSpells(CharacterDAO dao) {
        loadSpellsFromYaml(dao);
    }
    
    /**
     * Load spells from YAML resource. Falls back to CSV if YAML not found.
     */
    @SuppressWarnings("unchecked")
    private static void loadSpellsFromYaml(CharacterDAO dao) {
        try (InputStream in = DataLoader.class.getResourceAsStream("/data/spells.yaml")) {
            if (in == null) {
                // Fallback to old CSV
                loadSpellsFromCsv(dao);
                return;
            }
            
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            Map<String, Object> root = yaml.load(in);
            if (root == null) return;
            
            List<Map<String, Object>> spellList = (List<Map<String, Object>>) root.get("spells");
            if (spellList == null) return;
            
            int count = 0;
            for (Map<String, Object> spellData : spellList) {
                int id = getInt(spellData, "id", -1);
                String name = getString(spellData, "name", "");
                String description = getString(spellData, "description", "");
                String schoolStr = getString(spellData, "school", "ARCANE");
                int level = getInt(spellData, "level", 1);
                double castingTime = getDouble(spellData, "castingTime", 1.0);
                String targetStr = getString(spellData, "target", "SELF");
                String progressionStr = getString(spellData, "progression", "NORMAL");
                
                // Parse effect IDs (could be ints or strings in YAML)
                List<String> effectIds = new ArrayList<>();
                Object effectsObj = spellData.get("effectIds");
                if (effectsObj instanceof List) {
                    for (Object e : (List<?>) effectsObj) {
                        effectIds.add(String.valueOf(e));
                    }
                }
                
                Spell.SpellSchool school = Spell.SpellSchool.fromString(schoolStr);
                Spell.SpellTarget target = Spell.SpellTarget.fromString(targetStr);
                Skill.SkillProgression progression = Skill.SkillProgression.fromString(progressionStr);
                
                Spell spell = new Spell(id, name, description, school, level, 
                                        castingTime, target, effectIds, progression);
                
                // Store in DAO
                boolean added = dao.addSpellFull(spell);
                if (added) count++;
            }
            System.out.println("Loaded " + count + " spells from spells.yaml");
            
        } catch (Exception e) {
            System.err.println("Failed to load spells.yaml: " + e.getMessage());
            // Fallback to old CSV
            loadSpellsFromCsv(dao);
        }
    }
    
    private static void loadSpellsFromCsv(CharacterDAO dao) {
        try (InputStream in = DataLoader.class.getResourceAsStream("/data/spells.csv")) {
            if (in == null) return;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split(",", 2);
                    String name = parts[0].trim();
                    String desc = parts.length > 1 ? parts[1].trim() : "";
                    dao.addSpell(name, desc);
                }
            }
        } catch (Exception ignored) {}
    }
    
    // YAML helper methods
    private static String getString(Map<String, Object> map, String key, String defaultVal) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultVal;
    }
    
    private static int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (Exception e) { return defaultVal; }
        }
        return defaultVal;
    }
    
    private static double getDouble(Map<String, Object> map, String key, double defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val instanceof String) {
            try { return Double.parseDouble((String) val); } catch (Exception e) { return defaultVal; }
        }
        return defaultVal;
    }
    
    private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultVal) {
        Object val = map.get(key);
        if (val instanceof Boolean) return (Boolean) val;
        if (val instanceof String) return Boolean.parseBoolean((String) val);
        return defaultVal;
    }

    private static Map<String,Integer> loadAreas(CharacterDAO dao) {
        // Try YAML first, fall back to CSV
        Map<String,Integer> map = loadAreasFromYaml(dao);
        if (map.isEmpty()) {
            map = loadAreasFromCsv(dao);
        }
        return map;
    }
    
    @SuppressWarnings("unchecked")
    private static Map<String,Integer> loadAreasFromYaml(CharacterDAO dao) {
        Map<String,Integer> map = new HashMap<>();
        try (InputStream in = DataLoader.class.getResourceAsStream("/data/areas.yaml")) {
            if (in == null) return map;
            
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            Map<String, Object> root = yaml.load(in);
            if (root == null) return map;
            
            List<Map<String, Object>> areaList = (List<Map<String, Object>>) root.get("areas");
            if (areaList == null) return map;
            
            int count = 0;
            for (Map<String, Object> areaData : areaList) {
                int id = getInt(areaData, "id", -1);
                String name = getString(areaData, "name", "");
                String desc = getString(areaData, "description", "");
                
                if (id < 0 || name.isEmpty()) continue;
                
                int used = dao.addAreaWithId(id, name, desc);
                if (used > 0) {
                    map.put(name, used);
                    count++;
                }
            }
            System.out.println("[DataLoader] Loaded " + count + " areas from YAML");
        } catch (Exception e) {
            System.err.println("[DataLoader] Failed to load areas from YAML: " + e.getMessage());
        }
        return map;
    }
    
    private static Map<String,Integer> loadAreasFromCsv(CharacterDAO dao) {
        Map<String,Integer> map = new HashMap<>();
        try (InputStream in = DataLoader.class.getResourceAsStream("/data/areas.csv")) {
            if (in == null) return map;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] parts = line.split(",", 3);
                    int id = -1;
                    String name;
                    String desc;
                    try {
                        id = Integer.parseInt(parts[0].trim());
                        name = parts.length > 1 ? parts[1].trim() : "";
                        desc = parts.length > 2 ? parts[2].trim() : "";
                        int used = dao.addAreaWithId(id, name, desc);
                        if (used > 0) map.put(name, used);
                    } catch (NumberFormatException nfe) {
                        String[] parts2 = line.split(",", 2);
                        name = parts2[0].trim();
                        desc = parts2.length > 1 ? parts2[1].trim() : "";
                        int used = dao.addArea(name, desc);
                        if (used > 0) map.put(name, used);
                    }
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    private static class RoomTemplate {
        int explicitId = -1;
        String key;
        int areaId;
        String name;
        String shortDesc;
        String longDesc;
        Integer exitN, exitE, exitS, exitW, exitU, exitD;
    }

    private static Map<String,Integer> loadRoomsFirstPass(CharacterDAO dao, Map<String,Integer> areaMap) {
        // Try YAML first, fall back to CSV
        List<RoomTemplate> templates = loadRoomTemplatesFromYaml(areaMap);
        if (templates.isEmpty()) {
            templates = loadRoomTemplatesFromCsv(areaMap, dao);
        }
        return insertRoomTemplates(dao, templates);
    }
    
    @SuppressWarnings("unchecked")
    private static List<RoomTemplate> loadRoomTemplatesFromYaml(Map<String,Integer> areaMap) {
        List<RoomTemplate> templates = new ArrayList<>();
        try (InputStream in = DataLoader.class.getResourceAsStream("/data/rooms.yaml")) {
            if (in == null) return templates;
            
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            Map<String, Object> root = yaml.load(in);
            if (root == null) return templates;
            
            List<Map<String, Object>> roomList = (List<Map<String, Object>>) root.get("rooms");
            if (roomList == null) return templates;
            
            for (Map<String, Object> roomData : roomList) {
                RoomTemplate t = new RoomTemplate();
                t.explicitId = getInt(roomData, "id", -1);
                t.key = getString(roomData, "key", "");
                t.name = getString(roomData, "name", "");
                t.areaId = getInt(roomData, "area_id", 0);
                t.shortDesc = getString(roomData, "short_desc", "");
                t.longDesc = getString(roomData, "long_desc", "");
                
                // Parse exits
                Object exitsObj = roomData.get("exits");
                if (exitsObj instanceof Map) {
                    Map<String, Object> exits = (Map<String, Object>) exitsObj;
                    t.exitN = getExitId(exits, "north");
                    t.exitE = getExitId(exits, "east");
                    t.exitS = getExitId(exits, "south");
                    t.exitW = getExitId(exits, "west");
                    t.exitU = getExitId(exits, "up");
                    t.exitD = getExitId(exits, "down");
                }
                
                if (t.key.isEmpty() || t.name.isEmpty()) continue;
                templates.add(t);
            }
            System.out.println("[DataLoader] Loaded " + templates.size() + " room templates from YAML");
        } catch (Exception e) {
            System.err.println("[DataLoader] Failed to load rooms from YAML: " + e.getMessage());
        }
        return templates;
    }
    
    private static Integer getExitId(Map<String, Object> exits, String direction) {
        Object val = exits.get(direction);
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            String s = ((String) val).trim();
            if (s.isEmpty()) return null;
            try { return Integer.parseInt(s); } catch (Exception e) { return null; }
        }
        return null;
    }
    
    private static List<RoomTemplate> loadRoomTemplatesFromCsv(Map<String,Integer> areaMap, CharacterDAO dao) {
        List<RoomTemplate> templates = new ArrayList<>();
        try (InputStream in = DataLoader.class.getResourceAsStream("/data/rooms.csv")) {
            if (in == null) return templates;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] p = line.split(",", 12);
                    RoomTemplate t = new RoomTemplate();
                    int idx = 0;
                    try {
                        t.explicitId = Integer.parseInt(p[0].trim());
                        idx = 1;
                    } catch (NumberFormatException nfe) {
                        idx = 0;
                    }
                    t.key = p[idx + 0].trim();
                    t.name = p[idx + 1].trim();
                    String areaName = p[idx + 2].trim();
                    Integer areaId = areaMap.get(areaName);
                    if (areaId == null) areaId = dao.addArea(areaName, "");
                    t.areaId = areaId;
                    t.shortDesc = p[idx + 3].trim();
                    t.longDesc = p[idx + 4].trim();
                    // CSV exits stored as strings, will be resolved in second pass
                    templates.add(t);
                }
            }
        } catch (Exception ignored) {}
        return templates;
    }
    
    private static Map<String,Integer> insertRoomTemplates(CharacterDAO dao, List<RoomTemplate> templates) {
        Map<String,Integer> keyToId = new HashMap<>();
        Map<Integer,Integer> areaCounters = new HashMap<>();
        
        for (RoomTemplate t : templates) {
            int roomId;
            if (t.explicitId >= 0) {
                roomId = dao.addRoomWithId(t.explicitId, t.areaId, t.name, t.shortDesc, t.longDesc, 
                    t.exitN, t.exitE, t.exitS, t.exitW, t.exitU, t.exitD);
            } else {
                int nextLocal = areaCounters.getOrDefault(t.areaId, 0);
                if (nextLocal > 999) {
                    roomId = dao.addRoom(t.areaId, t.name, t.shortDesc, t.longDesc, null, null, null, null, null, null);
                } else {
                    int computed = t.areaId * 1000 + nextLocal;
                    roomId = dao.addRoomWithId(computed, t.areaId, t.name, t.shortDesc, t.longDesc,
                        t.exitN, t.exitE, t.exitS, t.exitW, t.exitU, t.exitD);
                    if (roomId > 0) areaCounters.put(t.areaId, nextLocal + 1);
                }
            }
            if (roomId > 0) keyToId.put(t.key, roomId);
        }
        return keyToId;
    }

    private static void loadRoomsSecondPass(CharacterDAO dao, Map<String,Integer> keyToId) {
        // Second pass only needed for CSV format where exits are stored as strings
        // YAML already has numeric IDs resolved in first pass
        try (InputStream in = DataLoader.class.getResourceAsStream("/data/rooms.csv")) {
            if (in == null) return;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] p = line.split(",", 12);
                    int idx = 0;
                    try {
                        Integer.parseInt(p[0].trim());
                        idx = 1;
                    } catch (Exception ignored) { idx = 0; }
                    String key = p[idx + 0].trim();
                    Integer roomId = keyToId.get(key);
                    if (roomId == null) continue;
                    String en = p.length > idx + 5 ? emptyToNull(p[idx + 5]) : null;
                    String ee = p.length > idx + 6 ? emptyToNull(p[idx + 6]) : null;
                    String es = p.length > idx + 7 ? emptyToNull(p[idx + 7]) : null;
                    String ew = p.length > idx + 8 ? emptyToNull(p[idx + 8]) : null;
                    String eu = p.length > idx + 9 ? emptyToNull(p[idx + 9]) : null;
                    String ed = p.length > idx + 10 ? emptyToNull(p[idx + 10]) : null;
                    Integer exitN = resolveExitToken(en, keyToId);
                    Integer exitE = resolveExitToken(ee, keyToId);
                    Integer exitS = resolveExitToken(es, keyToId);
                    Integer exitW = resolveExitToken(ew, keyToId);
                    Integer exitU = resolveExitToken(eu, keyToId);
                    Integer exitD = resolveExitToken(ed, keyToId);
                    dao.updateRoomExits(roomId, exitN, exitE, exitS, exitW, exitU, exitD);
                }
            }
        } catch (Exception ignored) {}
    }

    // Resolve an exit token that may be either a room key or a numeric id.
    private static Integer resolveExitToken(String token, Map<String,Integer> keyToId) {
        if (token == null || token.isEmpty()) return null;
        // First, try as a key
        Integer byKey = keyToId.get(token);
        if (byKey != null) return byKey;
        // Next, try parsing as a numeric id
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String emptyToNull(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }
}
