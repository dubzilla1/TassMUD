package com.example.tassmud.persistence;

import com.example.tassmud.model.*;
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
    }

    private static void loadSkills(CharacterDAO dao) {
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
                    // naive idempotency: skip if skill exists
                    // We don't have getSkillByName, but addSkill returns false on duplicate.
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

    private static Map<String,Integer> loadAreas(CharacterDAO dao) {
        Map<String,Integer> map = new HashMap<>();
        try (InputStream in = DataLoader.class.getResourceAsStream("/data/areas.csv")) {
            if (in == null) return map;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    // support optional leading numeric id: id,name,description
                    String[] parts = line.split(",", 3);
                    int id = -1;
                    String name;
                    String desc;
                    try {
                        id = Integer.parseInt(parts[0].trim());
                        // id present
                        name = parts.length > 1 ? parts[1].trim() : "";
                        desc = parts.length > 2 ? parts[2].trim() : "";
                        int used = dao.addAreaWithId(id, name, desc);
                        if (used > 0) map.put(name, used);
                    } catch (NumberFormatException nfe) {
                        // no id provided, fallback to old format
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
        String key;
        int areaId;
        String name;
        String shortDesc;
        String longDesc;
        String exitN, exitE, exitS, exitW, exitU, exitD;
    }

    private static Map<String,Integer> loadRoomsFirstPass(CharacterDAO dao, Map<String,Integer> areaMap) {
        Map<String,Integer> keyToId = new HashMap<>();
        List<RoomTemplate> templates = new ArrayList<>();
        try (InputStream in = DataLoader.class.getResourceAsStream("/data/rooms.csv")) {
            if (in == null) return keyToId;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    // support optional leading numeric id: id,key,name,area,short_desc,long_desc,exit_n,...
                    String[] p = line.split(",", 12);
                    RoomTemplate t = new RoomTemplate();
                    Integer explicitId = null;
                    int idx = 0;
                    try {
                        explicitId = Integer.parseInt(p[0].trim());
                        idx = 1; // fields start at p[1]
                    } catch (NumberFormatException nfe) {
                        idx = 0; // fields start at p[0]
                    }
                    // key,name,area,short_desc,long_desc,exit_n,exit_e,exit_s,exit_w,exit_u,exit_d
                    t.key = p[idx + 0].trim();
                    t.name = p[idx + 1].trim();
                    String areaName = p[idx + 2].trim();
                    Integer areaId = areaMap.get(areaName);
                    if (areaId == null) areaId = dao.addArea(areaName, "");
                    t.areaId = areaId;
                    t.shortDesc = p[idx + 3].trim();
                    t.longDesc = p[idx + 4].trim();
                    t.exitN = (p.length > idx + 5) ? emptyToNull(p[idx + 5]) : null;
                    t.exitE = (p.length > idx + 6) ? emptyToNull(p[idx + 6]) : null;
                    t.exitS = (p.length > idx + 7) ? emptyToNull(p[idx + 7]) : null;
                    t.exitW = (p.length > idx + 8) ? emptyToNull(p[idx + 8]) : null;
                    t.exitU = (p.length > idx + 9) ? emptyToNull(p[idx + 9]) : null;
                    t.exitD = (p.length > idx + 10) ? emptyToNull(p[idx + 10]) : null;
                    // store explicitId in shortDesc temporarily if present (we'll use DAO insertion accordingly below)
                    if (explicitId != null) {
                        // mark template by prefixing shortDesc with special token to carry id
                        t.shortDesc = "__ID:" + explicitId + "__" + t.shortDesc;
                    }
                    templates.add(t);
                }
            }
        } catch (Exception ignored) {}
        // Insert rooms with null exits and remember ids
        // We'll assign area-scoped numeric ids for rooms without explicit ids.
        // Each area gets up to 1000 room slots: areaId*1000 .. areaId*1000+999
        Map<Integer,Integer> areaCounters = new HashMap<>();
        for (RoomTemplate t : templates) {
            Integer explicitId = null;
            String shortDesc = t.shortDesc;
            if (shortDesc != null && shortDesc.startsWith("__ID:")) {
                int end = shortDesc.indexOf("__", 5);
                if (end > 0) {
                    String idStr = shortDesc.substring(5, end);
                    try { explicitId = Integer.parseInt(idStr); } catch (Exception ignored) {}
                    // strip marker
                    shortDesc = shortDesc.substring(end+2);
                }
            }
            int roomId;
            if (explicitId != null) {
                roomId = dao.addRoomWithId(explicitId, t.areaId, t.name, shortDesc, t.longDesc, null, null, null, null, null, null);
            } else {
                int nextLocal = areaCounters.getOrDefault(t.areaId, 0);
                if (nextLocal > 999) {
                    // out of space for this area; fall back to generated id
                    roomId = dao.addRoom(t.areaId, t.name, shortDesc, t.longDesc, null, null, null, null, null, null);
                } else {
                    int computed = t.areaId * 1000 + nextLocal;
                    roomId = dao.addRoomWithId(computed, t.areaId, t.name, shortDesc, t.longDesc, null, null, null, null, null, null);
                    // increment counter only if we successfully reserved the id
                    if (roomId > 0) areaCounters.put(t.areaId, nextLocal + 1);
                }
            }
            if (roomId > 0) keyToId.put(t.key, roomId);
        }
        // Save templates to thread-local-like structure for second pass via mapping
        // We'll call second pass loader with the templates resolved from resources again (simpler than storing globally)
        return keyToId;
    }

    private static void loadRoomsSecondPass(CharacterDAO dao, Map<String,Integer> keyToId) {
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
                        idx = 1; // id present
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
