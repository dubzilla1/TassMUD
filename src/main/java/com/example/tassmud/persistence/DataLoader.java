package com.example.tassmud.persistence;

import com.example.tassmud.model.*;
import com.example.tassmud.effect.EffectDefinition;
import com.example.tassmud.model.MobileBehavior;
import com.example.tassmud.model.MobileTemplate;
import com.example.tassmud.event.SpawnConfig;
import com.example.tassmud.event.SpawnConfig.SpawnType;
import com.example.tassmud.event.SpawnManager;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataLoader {

    private static final Logger logger = LoggerFactory.getLogger(DataLoader.class);

    // Mapping from MERC-specified area id -> persisted DB area id when importing MERC dirs
    private static final Map<Integer,Integer> mercAreaIdMap = new HashMap<>();
    // Simple CSV loader for initial data. Files are in classpath under /data/
    // - skills.csv: name,description
    // - spells.csv: name,description
    // - areas.csv: name,description
    // - rooms.csv: key,name,area,short_desc,long_desc,exit_n,exit_e,exit_s,exit_w,exit_u,exit_d
    // Room exits reference room `key` values (resolved after insertion)

    public static void loadDefaults(CharacterDAO dao) {
        loadSkills(dao);
        loadSpells(dao);
        // Load effects definitions (custom effect engine)
        try {
            loadEffects();
        } catch (Exception e) {
            logger.warn("Failed to load effects.yaml: {}", e.getMessage());
        }
        Map<String,Integer> areaMap = loadAreas(dao);
        Map<String,Integer> roomKeyToId = loadRoomsFirstPass(dao, areaMap);
        loadRoomsSecondPass(dao, roomKeyToId);
        // Load item templates from YAML resource into item_template table
        ItemDAO itemDao = null;
        try {
            itemDao = new ItemDAO();
            itemDao.loadTemplatesFromYamlResource("/data/items.yaml");
            // Also load any MERC-area-specific item template files under /data/MERC/*/items.yaml
            List<String> mercDirs = listMercAreaDirs();
            for (String dir : mercDirs) {
                String mercItemsPath = "/data/MERC/" + dir + "/items.yaml";
                try (InputStream in = DataLoader.class.getResourceAsStream(mercItemsPath)) {
                    if (in == null) continue;
                    itemDao.loadTemplatesFromYamlResource(mercItemsPath);
                    logger.info("[DataLoader] Loaded MERC items from {}", mercItemsPath);
                } catch (Exception e) {
                    logger.warn("[DataLoader] Failed to load MERC items from {}: {}", mercItemsPath, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to load items.yaml: {}", e.getMessage());
        }
        // Load character classes from YAML resource
        try {
            CharacterClassDAO classDao = new CharacterClassDAO();
            classDao.loadClassesFromYamlResource("/data/classes.yaml");
        } catch (Exception e) {
            logger.warn("Failed to load classes.yaml: {}", e.getMessage());
        }
        // Load mobile templates from YAML resource
        try {
            loadMobileTemplates();
        } catch (Exception e) {
            logger.warn("Failed to load mobiles.yaml: {}", e.getMessage());
        }
        // Load shop menus from YAML resource
        try {
            ShopDAO.loadFromYamlResource("/data/shops.yaml");
        } catch (Exception e) {
            logger.warn("Failed to load shops.yaml: {}", e.getMessage());
        }
        // Spawn permanent room items (e.g., tutorial containers)
        if (itemDao != null) {
            spawnPermanentRoomItems(itemDao);
        }
    }

    /**
     * Sanitize YAML text by converting Python-style `template_json: { ... }` flow maps
     * into YAML block scalars so SnakeYAML treats them as strings.
     */
    private static String sanitizeTemplateJsonFlowMaps(String text) {
        StringBuilder out = new StringBuilder();
        int idx = 0;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(^[ \\t]*template_json\\s*:\\s*)\\{", java.util.regex.Pattern.MULTILINE);
        java.util.regex.Matcher m = p.matcher(text);
        while (m.find(idx)) {
            int matchStart = m.start();
            int braceStart = m.end() - 1; // position of '{'
            out.append(text, idx, matchStart);
            // find matching brace
            int depth = 0;
            int i = braceStart;
            for (; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '{') depth++;
                else if (ch == '}') {
                    depth--;
                    if (depth == 0) break;
                }
            }
            if (i >= text.length()) {
                // unmatched brace; abort
                return text;
            }
            String prefix = m.group(1);
            String block = text.substring(braceStart, i + 1);
            // determine indentation for the line
            int lineStart = text.lastIndexOf('\n', matchStart);
            String indent = lineStart >= 0 ? text.substring(lineStart + 1, matchStart) : "";
            String[] lines = block.split("\\r?\\n", -1);
            StringBuilder indented = new StringBuilder();
            for (String ln : lines) {
                indented.append(indent).append("  ").append(ln).append('\n');
            }
            out.append(prefix).append("|\n").append(indented.toString());
            idx = i + 1;
        }
        out.append(text.substring(idx));
        return out.toString();
    }

    /**
     * Seed item and mobile templates (including MERC files) into the currently-configured DB
     * without modifying character or runtime instance tables. After loading templates this
     * will trigger an initial spawn pass so registered spawns can populate the world.
     */
    public static void loadTemplatesOnly() {
        logger.info("[DataLoader] Seeding templates only into current DB");
        // Load item templates
        try {
            ItemDAO itemDao = new ItemDAO();
            itemDao.loadTemplatesFromYamlResource("/data/items.yaml");
            List<String> mercDirs = listMercAreaDirs();
            for (String dir : mercDirs) {
                String mercItemsPath = "/data/MERC/" + dir + "/items.yaml";
                try (InputStream in = DataLoader.class.getResourceAsStream(mercItemsPath)) {
                    if (in == null) continue;
                    itemDao.loadTemplatesFromYamlResource(mercItemsPath);
                    logger.info("[DataLoader] Loaded MERC items from {}", mercItemsPath);
                } catch (Exception e) {
                    logger.warn("[DataLoader] Failed to load MERC items from {}: {}", mercItemsPath, e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("[DataLoader] Failed to load item templates: {}", e.getMessage());
        }

        // Load mobile templates
        try {
            loadMobileTemplates();
        } catch (Exception e) {
            logger.warn("[DataLoader] Failed to load mobile templates: {}", e.getMessage());
        }

        // Trigger initial spawns if any spawns were registered earlier
        try {
            com.example.tassmud.event.SpawnManager.getInstance().triggerInitialSpawns();
        } catch (Exception e) {
            logger.warn("[DataLoader] Failed to trigger initial spawns: {}", e.getMessage());
        }
        logger.info("[DataLoader] Template seeding complete");
    }
    
    /**
     * Spawn permanent items that should always exist in specific rooms.
     * This is idempotent - only creates if no instances exist for the template in that room.
     */
    private static void spawnPermanentRoomItems(ItemDAO itemDao) {
        // Tutorial area permanent spawns: [templateId, roomId]
        int[][] permanentSpawns = {
            {267, 1005},  // Broken Weapons Chest in Waystone Rest
        };
        
        for (int[] spawn : permanentSpawns) {
            int templateId = spawn[0];
            int roomId = spawn[1];
            
            // Check if any instance already exists in the room
            java.util.List<ItemDAO.RoomItem> existingItems = itemDao.getItemsInRoom(roomId);
            boolean alreadyExists = existingItems.stream()
                .anyMatch(ri -> ri.template.id == templateId);
            
            if (!alreadyExists) {
                try {
                    long instanceId = itemDao.createInstance(templateId, roomId, null);
                    ItemTemplate tmpl = itemDao.getTemplateById(templateId);
                    String name = tmpl != null ? tmpl.name : "item #" + templateId;
                    logger.info("[DataLoader] Spawned permanent item '{}' (instance #{}) in room {}", name, instanceId, roomId);
                } catch (Exception e) {
                    logger.warn("[DataLoader] Failed to spawn permanent item {} in room {}: {}", templateId, roomId, e.getMessage());
                }
            }
        }
    }
    
    /**
     * Load mobile templates from YAML resource.
     */
    private static void loadMobileTemplates() {
        MobileDAO mobileDao = new MobileDAO();
        java.util.concurrent.atomic.AtomicInteger totalLoaded = new java.util.concurrent.atomic.AtomicInteger(0);
        // Helper to load a list from an InputStream
        java.util.function.Consumer<InputStream> loader = (InputStream in) -> {
            if (in == null) return;
            try {
                String content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> mobileList = null;
                try {
                    mobileList = (List<Map<String, Object>>) yaml.load(content);
                } catch (Exception parseEx) {
                    logger.warn("[DataLoader] YAML parse failed, attempting sanitizer: {}", parseEx.getMessage(), parseEx);
                    // Attempt to sanitize Python-style flow dicts for template_json into block scalars
                    String sanitized = sanitizeTemplateJsonFlowMaps(content);
                    try {
                        mobileList = (List<Map<String, Object>>) yaml.load(sanitized);
                        logger.info("[DataLoader] YAML parse succeeded after sanitizing template_json flow maps");
                    } catch (Exception parseEx2) {
                        logger.warn("[DataLoader] Sanitized parse also failed: {}", parseEx2.getMessage(), parseEx2);
                        throw new RuntimeException(parseEx2);
                    }
                }

                if (mobileList == null) return;
                for (Map<String,Object> mobData : mobileList) {
                        int id = getInt(mobData, "id", -1);
                        if (id < 0) continue;
                        String name = getString(mobData, "name", "Unknown");
                        String rawKey = getString(mobData, "key", "");
                        String keyBase = (rawKey == null || rawKey.isBlank()) ? name : rawKey;
                        String key = makeTemplateKey(keyBase, id);
                    String shortDesc = getString(mobData, "short_desc", name + " is here.");
                    String longDesc = getString(mobData, "long_desc", "You see nothing special.");
                    List<String> keywords = new ArrayList<>();
                    Object keywordsObj = mobData.get("keywords");
                    if (keywordsObj instanceof List) {
                        for (Object kw : (List<?>) keywordsObj) keywords.add(String.valueOf(kw));
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
                    List<MobileBehavior> behaviors = new ArrayList<>();
                    Object behaviorsObj = mobData.get("behaviors");
                    if (behaviorsObj instanceof List) {
                        for (Object b : (List<?>) behaviorsObj) {
                            MobileBehavior behavior = MobileBehavior.fromString(String.valueOf(b));
                            if (behavior != null) behaviors.add(behavior);
                        }
                    } else if (behaviorsObj instanceof String) {
                        MobileBehavior behavior = MobileBehavior.fromString((String) behaviorsObj);
                        if (behavior != null) behaviors.add(behavior);
                    }
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
                    int autoflee = getInt(mobData, "autoflee", 0);
                    MobileTemplate template = new MobileTemplate(
                        id, key, name, shortDesc, longDesc, keywords,
                        level, hpMax, mpMax, mvMax,
                        str, dex, con, intel, wis, cha,
                        armor, fortitude, reflex, will,
                        baseDamage, damageBonus, attackBonus,
                        behaviors, aggroRange,
                        experienceValue, goldMin, goldMax,
                        respawnSeconds, autoflee, null
                    );
                    mobileDao.upsertTemplate(template);
                    totalLoaded.incrementAndGet();
                    // Diagnostic logging for specific templates or when debug enabled
                    if (template.getId() == 3011 || "true".equals(System.getProperty("tassmud.debug.templates", "false"))) {
                        logger.debug("[DataLoader] upserted mobile template id={} name='{}'", template.getId(), template.getName());
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to parse mobile list: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        };

        // Load the primary mobiles.yaml
        try (InputStream in = DataLoader.class.getResourceAsStream("/data/mobiles.yaml")) {
            if (in != null) loader.accept(in);
            else logger.info("No mobiles.yaml found");
        } catch (Exception e) {
            logger.warn("[DataLoader] Failed to load /data/mobiles.yaml: {}", e.getMessage(), e);
        }

        // Also load any MERC mobiles.yaml under /data/MERC/*/mobiles.yaml
        List<String> mercDirs = listMercAreaDirs();
        for (String dir : mercDirs) {
            String path = "/data/MERC/" + dir + "/mobiles.yaml";
            try (InputStream in = DataLoader.class.getResourceAsStream(path)) {
                if (in != null) {
                    loader.accept(in);
                    logger.info("[DataLoader] Loaded MERC mobiles from {}", path);
                }
            } catch (Exception e) {
                logger.warn("[DataLoader] Failed to load MERC mobiles from {}: {}", path, e.getMessage(), e);
            }
        }

        logger.info("Loaded {} mobile templates (including MERC)", totalLoaded.get());
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
                double cooldown = getDouble(skillData, "cooldown", 0);
                double duration = getDouble(skillData, "duration", 0);
                
                // Parse traits list
                List<SkillTrait> traits = new ArrayList<>();
                Object traitsObj = skillData.get("traits");
                if (traitsObj instanceof List) {
                    for (Object t : (List<?>) traitsObj) {
                        SkillTrait trait = SkillTrait.fromString(String.valueOf(t));
                        if (trait != null) traits.add(trait);
                    }
                }
                
                // Parse effect IDs list
                List<String> effectIds = new ArrayList<>();
                Object effectIdsObj = skillData.get("effectIds");
                if (effectIdsObj instanceof List) {
                    for (Object e : (List<?>) effectIdsObj) {
                        effectIds.add(String.valueOf(e));
                    }
                }
                
                if (id < 0 || key.isEmpty() || name.isEmpty()) continue;
                
                com.example.tassmud.model.Skill.SkillProgression progression = 
                    com.example.tassmud.model.Skill.SkillProgression.fromString(progressionStr);
                
                if (dao.addSkillFull(id, key, name, description, isPassive, maxLevel, progression, traits, cooldown, duration, effectIds)) {
                    count++;
                }
            }
            logger.info("[DataLoader] Loaded {} skills from YAML", count);
        } catch (Exception e) {
            logger.warn("[DataLoader] Failed to load skills from YAML: {}", e.getMessage(), e);
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
                double cooldown = getDouble(spellData, "cooldown", 0);
                double duration = getDouble(spellData, "duration", 0);
                // Parse traits list
                List<SpellTrait> traits = new ArrayList<>();
                Object traitsObj = spellData.get("traits");
                if (traitsObj instanceof List) {
                    for (Object t : (List<?>) traitsObj) {
                        SpellTrait trait = SpellTrait.fromString(String.valueOf(t));
                        if (trait != null) traits.add(trait);
                    }
                }
                
                Spell spell = new Spell(id, name, description, school, level, 
                                        castingTime, target, effectIds, progression, traits, cooldown,duration);
                
                // Store in DAO
                boolean added = dao.addSpellFull(spell);
                if (added) count++;
            }
            logger.info("Loaded {} spells from spells.yaml", count);
            
            // Initialize spell handlers (triggers static initializers to register with SpellRegistry)
            initializeSpellHandlers();
            
        } catch (Exception e) {
            logger.warn("Failed to load spells.yaml: {}", e.getMessage(), e);
            // Fallback to old CSV
            loadSpellsFromCsv(dao);
        }
    }
    
    /**
     * Initialize spell handler classes to trigger their static initializers.
     * This registers spell handlers with SpellRegistry.
     */
    private static void initializeSpellHandlers() {
        try {
            // Load handler classes to trigger static initializers
            Class.forName("com.example.tassmud.spell.ArcaneSpellHandler");
            Class.forName("com.example.tassmud.spell.DivineSpellHandler");
            Class.forName("com.example.tassmud.spell.PrimalSpellHandler");
            Class.forName("com.example.tassmud.spell.OccultSpellHandler");
            logger.info("Initialized spell handlers (Arcane, Divine, Primal, Occult)");
        } catch (ClassNotFoundException e) {
            logger.warn("Failed to initialize spell handlers: {}", e.getMessage());
        }
    }

    /**
     * Load effect definitions from /data/effects.yaml and register handlers.
     */
    @SuppressWarnings("unchecked")
    private static void loadEffects() {
        try (InputStream in = DataLoader.class.getResourceAsStream("/data/effects.yaml")) {
            if (in == null) {
                logger.info("No effects.yaml found");
                return;
            }
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            Map<String, Object> root = yaml.load(in);
            if (root == null) return;

            java.util.List<Map<String,Object>> list = (java.util.List<Map<String,Object>>) root.get("effects");
            if (list == null) return;

            int count = 0;
            for (Map<String,Object> item : list) {
                String id = getString(item, "id", null);
                String name = getString(item, "name", id == null ? "effect" : "effect_" + id);
                String typeStr = getString(item, "type", "MODIFIER");
                double duration = getDouble(item, "duration", 0);
                double cooldown = getDouble(item, "cooldown", 0);
                String diceMultRaw = getString(item, "dice_multiplier", "");
                int levelMult = getInt(item, "level_multiplier", 0);
                // Support both spellings: profficiency_impact (typo) and proficiency_impact
                Object profImpactObj = item.get("profficiency_impact");
                if (profImpactObj == null) profImpactObj = item.get("proficiency_impact");
                java.util.Set<com.example.tassmud.effect.EffectDefinition.ProficiencyImpact> profImpactSet = new java.util.HashSet<>();
                if (profImpactObj != null) {
                    if (profImpactObj instanceof java.util.List) {
                        for (Object o : (java.util.List<?>) profImpactObj) {
                            String s = String.valueOf(o).toUpperCase().trim();
                            try { profImpactSet.add(com.example.tassmud.effect.EffectDefinition.ProficiencyImpact.valueOf(s)); } catch (Exception ignored) {}
                        }
                    } else if (profImpactObj instanceof String) {
                        String s = ((String) profImpactObj).trim();
                        // Comma/space separated
                        for (String tok : s.split("[,\s]+")) {
                            try { profImpactSet.add(com.example.tassmud.effect.EffectDefinition.ProficiencyImpact.valueOf(tok.toUpperCase())); } catch (Exception ignored) {}
                        }
                    }
                }
                String stackStr = getString(item, "stackPolicy", "REFRESH");
                boolean persistent = getBoolean(item, "persistent", true);
                int priority = getInt(item, "priority", 0);

                java.util.Map<String,String> params = new java.util.HashMap<>();
                Object paramsObj = item.get("params");
                if (paramsObj instanceof Map) {
                    for (Map.Entry<?,?> e : ((Map<?,?>)paramsObj).entrySet()) {
                        params.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                }

                EffectDefinition.Type type;
                try { type = EffectDefinition.Type.valueOf(typeStr); } catch (Exception e) { type = EffectDefinition.Type.MODIFIER; }
                EffectDefinition.StackPolicy sp;
                try { sp = EffectDefinition.StackPolicy.valueOf(stackStr); } catch (Exception e) { sp = EffectDefinition.StackPolicy.REFRESH; }

                com.example.tassmud.effect.EffectDefinition def = new com.example.tassmud.effect.EffectDefinition(
                    id == null ? String.valueOf(count) : id,
                    name,
                    type,
                    params,
                    duration,
                    cooldown,
                    diceMultRaw,
                    levelMult,
                    profImpactSet,
                    sp,
                    persistent,
                    priority
                );
                com.example.tassmud.effect.EffectRegistry.registerDefinition(def);
                count++;
            }

            // Register built-in handlers (ModifierEffect for MODIFIER type)
            com.example.tassmud.effect.EffectRegistry.registerHandler("MODIFIER", new com.example.tassmud.effect.ModifierEffect());
            // Register instant damage handler for INSTANT_DAMAGE and CUSTOM effects (e.g., fireball-like effects)
            com.example.tassmud.effect.EffectRegistry.registerHandler("INSTANT_DAMAGE", new com.example.tassmud.effect.InstantDamageEffect());
            com.example.tassmud.effect.EffectRegistry.registerHandler("CUSTOM", new com.example.tassmud.effect.InstantDamageEffect());
            // Register instant heal handler for INSTANT_HEAL effects (cure spells)
            com.example.tassmud.effect.EffectRegistry.registerHandler("INSTANT_HEAL", new com.example.tassmud.effect.InstantHealEffect());
            // Register weapon infusion handler for WEAPON_INFUSION effects (arcane infusion, etc.)
            com.example.tassmud.effect.EffectRegistry.registerHandler("WEAPON_INFUSION", new com.example.tassmud.effect.WeaponInfusionEffect());
            // Register damage-over-time handler for DOT effects (acid blast, poison, etc.)
            com.example.tassmud.effect.EffectRegistry.registerHandler("DOT", new com.example.tassmud.effect.DotEffect());
            // Register debuff handler for DEBUFF effects (blindness, etc.)
            com.example.tassmud.effect.EffectRegistry.registerHandler("DEBUFF", new com.example.tassmud.effect.BlindEffect());
            // Register burning hands handler for BURNING_HANDS effects (spreading fire DOT)
            com.example.tassmud.effect.EffectRegistry.registerHandler("BURNING_HANDS", new com.example.tassmud.effect.BurningHandsEffect());
            // Register call lightning handler for CALL_LIGHTNING effects (weather-based instant damage)
            com.example.tassmud.effect.EffectRegistry.registerHandler("CALL_LIGHTNING", new com.example.tassmud.effect.CallLightningEffect());
            // Register cause wounds handler for CAUSE_WOUNDS effects (negative energy damage/healing)
            com.example.tassmud.effect.EffectRegistry.registerHandler("CAUSE_WOUNDS", new com.example.tassmud.effect.CauseWoundsEffect());
            // Register slow handler for SLOW effects (limits attacks per round)
            com.example.tassmud.effect.EffectRegistry.registerHandler("SLOW", new com.example.tassmud.effect.SlowEffect());
            // Register confused handler for CONFUSED effects (random target selection)
            com.example.tassmud.effect.EffectRegistry.registerHandler("CONFUSED", new com.example.tassmud.effect.ConfusedEffect());
            // Register paralyzed handler for PARALYZED effects (blocks all actions)
            com.example.tassmud.effect.EffectRegistry.registerHandler("PARALYZED", new com.example.tassmud.effect.ParalyzedEffect());
            // Register cursed handler for CURSED effects (skills/spells may fail)
            com.example.tassmud.effect.EffectRegistry.registerHandler("CURSED", new com.example.tassmud.effect.CursedEffect());
            // Register flying handler for FLYING effects (free movement, sector access)
            com.example.tassmud.effect.EffectRegistry.registerHandler("FLYING", new com.example.tassmud.effect.FlyingEffect());
            // UNDEAD is a flag effect - no handler needed, just presence check

            logger.info("Loaded {} effects from effects.yaml", count);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load effects.yaml: " + e.getMessage(), e);
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

    private static String makeTemplateKey(String name, int id) {
        if (name == null) name = "tmpl";
        String s = name.trim().toLowerCase();
        // replace non-alphanumeric with underscores
        s = s.replaceAll("[^a-z0-9]+", "_");
        // collapse multiple underscores
        s = s.replaceAll("_+", "_");
        // trim underscores
        s = s.replaceAll("^_+|_+$", "");
        if (s.isEmpty()) s = "tmpl";
        String key = s + "_" + id;
        // enforce reasonable length (DB column for mobile template_key was increased to 200)
        if (key.length() > 190) return key.substring(0, 190);
        return key;
    }

    private static Map<String,Integer> loadAreas(CharacterDAO dao) {
        // Prefer MERC-format areas under /data/MERC/*/areas.yaml, then regular YAML, then CSV
        Map<String,Integer> map = loadAreasFromMercDirs(dao);
        if (!map.isEmpty()) return map;

        map = loadAreasFromYaml(dao);
        if (map.isEmpty()) {
            map = loadAreasFromCsv(dao);
        }
        return map;
    }

    /**
     * Discover MERC subdirectories under /data/MERC and attempt to load any areas.yaml files there.
     * Supports running from the filesystem during development and from a shaded JAR at runtime.
     */
    @SuppressWarnings("unchecked")
    private static Map<String,Integer> loadAreasFromMercDirs(CharacterDAO dao) {
        Map<String,Integer> map = new HashMap<>();
        List<String> dirs = listMercAreaDirs();
        if (dirs.isEmpty()) return map;

        for (String dir : dirs) {
            String resourcePath = "/data/MERC/" + dir + "/areas.yaml";
            try (InputStream in = DataLoader.class.getResourceAsStream(resourcePath)) {
                if (in == null) continue;
                org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
                Map<String, Object> root = yaml.load(in);
                if (root == null) continue;
                List<Map<String, Object>> areaList = (List<Map<String, Object>>) root.get("areas");
                if (areaList == null) continue;
                for (Map<String, Object> areaData : areaList) {
                    int id = getInt(areaData, "id", -1);
                    String name = getString(areaData, "name", "");
                    String desc = getString(areaData, "description", "");
                    String sectorStr = getString(areaData, "sector_type", "FIELD");
                    SectorType sectorType = SectorType.fromString(sectorStr);
                    if (id < 0 || name.isEmpty()) continue;
                    int used = dao.addAreaWithId(id, name, desc, sectorType);
                    if (used > 0) {
                        map.put(name, used);
                        // record mapping from MERC area id -> actual persisted id (may differ if name conflict)
                        mercAreaIdMap.put(id, used);
                    }
                }
            } catch (Exception e) {
                logger.warn("[DataLoader] Failed to load MERC areas from {}: {}", resourcePath, e.getMessage(), e);
            }
        }
        if (!map.isEmpty()) logger.info("[DataLoader] Loaded {} MERC areas", map.size());
        return map;
    }

    /**
     * List subdirectories directly under /data/MERC/ by inspecting the resource URL.
     * Works when resources are files on disk or packaged inside a JAR.
     */
    private static List<String> listMercAreaDirs() {
        List<String> out = new ArrayList<>();
        try {
            URL url = DataLoader.class.getResource("/data/MERC/");
            if (url == null) url = DataLoader.class.getResource("/data/MERC");
            if (url == null) return out;
            String protocol = url.getProtocol();
            try { logger.debug("[DataLoader.debug] /data/MERC URL={} protocol={}", url, protocol); } catch (Exception ignored) {}
            if ("file".equals(protocol)) {
                Path p = Paths.get(url.toURI());
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                    for (Path child : ds) {
                        if (Files.isDirectory(child)) out.add(child.getFileName().toString());
                    }
                }
            } else if ("jar".equals(protocol)) {
                // url looks like: jar:file:/path/to/jar.jar!/data/MERC/
                String s = url.toString();
                int bang = s.indexOf('!');
                String jarPart;
                int fileIdx = s.indexOf("file:");
                if (fileIdx >= 0) jarPart = s.substring(fileIdx + "file:".length(), bang);
                else jarPart = s.substring("jar:".length(), bang);
                String jarPath = URLDecoder.decode(jarPart, "UTF-8");
                try (JarFile jf = new JarFile(jarPath)) {
                    java.util.Enumeration<JarEntry> entries = jf.entries();
                    java.util.Set<String> dirs = new java.util.HashSet<>();
                    while (entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (name.startsWith("data/MERC/")) {
                            String rest = name.substring("data/MERC/".length());
                            int idx = rest.indexOf('/');
                            if (idx > 0) {
                                String dir = rest.substring(0, idx);
                                dirs.add(dir);
                            }
                        }
                    }
                    out.addAll(dirs);
                    try { logger.debug("[DataLoader.debug] found MERC dirs: {} jarPath={}", dirs, jarPath); } catch (Exception ignored) {}
                } catch (Exception e) {
                    logger.warn("[DataLoader] failed to read JAR file for /data/MERC/ listing jarPath={}: {}", jarPath, e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return out;
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
                String sectorStr = getString(areaData, "sector_type", "FIELD");
                SectorType sectorType = SectorType.fromString(sectorStr);
                
                if (id < 0 || name.isEmpty()) continue;
                
                int used = dao.addAreaWithId(id, name, desc, sectorType);
                if (used > 0) {
                    map.put(name, used);
                    count++;
                }
            }
            logger.info("[DataLoader] Loaded {} areas from YAML", count);
        } catch (Exception e) {
            logger.warn("[DataLoader] Failed to load areas from YAML: {}", e.getMessage(), e);
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
        List<SpawnConfig> spawns = new ArrayList<>();
        List<String> flags = new ArrayList<>();  // Room flags (dark, no_mob, safe, etc.)
    }

    private static Map<String,Integer> loadRoomsFirstPass(CharacterDAO dao, Map<String,Integer> areaMap) {
        // Prefer MERC-format room templates under /data/MERC/*/rooms.yaml, then regular YAML, then CSV
        List<RoomTemplate> templates = loadRoomTemplatesFromMercDirs(areaMap);
        if (templates.isEmpty()) {
            templates = loadRoomTemplatesFromYaml(areaMap);
        }
        if (templates.isEmpty()) {
            templates = loadRoomTemplatesFromCsv(areaMap, dao);
        }
        return insertRoomTemplates(dao, templates);
    }

    @SuppressWarnings("unchecked")
    private static List<RoomTemplate> loadRoomTemplatesFromMercDirs(Map<String,Integer> areaMap) {
        List<RoomTemplate> templates = new ArrayList<>();
        List<String> dirs = listMercAreaDirs();
        if (dirs.isEmpty()) return templates;

        for (String dir : dirs) {
            String resourcePath = "/data/MERC/" + dir + "/rooms.yaml";
            try (InputStream in = DataLoader.class.getResourceAsStream(resourcePath)) {
                if (in == null) continue;
                org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
                Map<String, Object> root = yaml.load(in);
                if (root == null) continue;
                List<Map<String, Object>> roomList = (List<Map<String, Object>>) root.get("rooms");
                if (roomList == null) continue;
                for (Map<String, Object> roomData : roomList) {
                    RoomTemplate t = new RoomTemplate();
                    t.explicitId = getInt(roomData, "id", -1);
                    t.key = getString(roomData, "key", "");
                    t.name = getString(roomData, "name", "");
                    t.areaId = getInt(roomData, "area_id", 0);
                    // If this MERC room referenced a MERC area id, remap to the persisted DB area id
                    if (t.areaId > 0 && mercAreaIdMap.containsKey(t.areaId)) {
                        int orig = t.areaId;
                        t.areaId = mercAreaIdMap.get(orig);
                    }
                    t.shortDesc = getString(roomData, "short_desc", "");
                    t.longDesc = getString(roomData, "long_desc", "");
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

                    Object spawnsObj = roomData.get("spawns");
                    if (spawnsObj instanceof List) {
                        List<Map<String, Object>> spawnsList = (List<Map<String, Object>>) spawnsObj;
                        for (Map<String, Object> spawnData : spawnsList) {
                            SpawnConfig spawn = parseSpawnConfig(spawnData, t.explicitId);
                            if (spawn != null) t.spawns.add(spawn);
                        }
                    }

                    // Parse room flags (dark, no_mob, safe, etc.)
                    Object flagsObj = roomData.get("flags");
                    if (flagsObj instanceof List) {
                        List<?> flagsList = (List<?>) flagsObj;
                        for (Object flag : flagsList) {
                            if (flag != null) {
                                t.flags.add(flag.toString().toLowerCase().trim());
                            }
                        }
                    }

                    if (t.key.isEmpty() || t.name.isEmpty()) continue;
                    templates.add(t);
                }
            } catch (Exception e) {
                logger.warn("[DataLoader] Failed to load MERC rooms from {}: {}", resourcePath, e.getMessage(), e);
            }
        }
        if (!templates.isEmpty()) logger.info("[DataLoader] Loaded {} MERC room templates", templates.size());
        return templates;
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
                
                // Parse spawns
                Object spawnsObj = roomData.get("spawns");
                if (spawnsObj instanceof List) {
                    List<Map<String, Object>> spawnsList = (List<Map<String, Object>>) spawnsObj;
                    for (Map<String, Object> spawnData : spawnsList) {
                        SpawnConfig spawn = parseSpawnConfig(spawnData, t.explicitId);
                        if (spawn != null) {
                            t.spawns.add(spawn);
                        }
                    }
                }
                
                // Parse room flags
                Object flagsObj = roomData.get("flags");
                if (flagsObj instanceof List) {
                    for (Object flag : (List<?>) flagsObj) {
                        if (flag != null) {
                            t.flags.add(flag.toString().toLowerCase().trim());
                        }
                    }
                }
                
                if (t.key.isEmpty() || t.name.isEmpty()) continue;
                templates.add(t);
            }
            logger.info("[DataLoader] Loaded {} room templates from YAML", templates.size());
        } catch (Exception e) {
            logger.warn("[DataLoader] Failed to load rooms from YAML: {}", e.getMessage(), e);
        }
        return templates;
    }
    
    /**
     * Parse a spawn configuration from YAML data.
     */
    private static SpawnConfig parseSpawnConfig(Map<String, Object> data, int roomId) {
        try {
            String typeStr = getString(data, "type", "").toUpperCase();
            SpawnType type = SpawnType.valueOf(typeStr);
            int templateId = getInt(data, "id", -1);
            int quantity = getInt(data, "quantity", 1);
            int frequency = getInt(data, "frequency", 1);
            int containerId = getInt(data, "container", 0);
            // Optional equipment list (for mob spawns migrated from MERC resets)
            java.util.List<java.util.Map<String,Object>> equipment = null;
            Object equipObj = data.get("equipment");
            if (equipObj instanceof java.util.List) {
                equipment = new java.util.ArrayList<>();
                for (Object o : (java.util.List<?>) equipObj) {
                    if (o instanceof java.util.Map) {
                        equipment.add((java.util.Map<String,Object>) o);
                    }
                }
            }
            // Optional inventory list (for MERC 'G' resets)
            java.util.List<java.util.Map<String,Object>> inventory = null;
            Object invObj = data.get("inventory");
            if (invObj instanceof java.util.List) {
                inventory = new java.util.ArrayList<>();
                for (Object o : (java.util.List<?>) invObj) {
                    if (o instanceof java.util.Map) {
                        inventory.add((java.util.Map<String,Object>) o);
                    }
                }
            }

            if (templateId < 0) {
                logger.warn("[DataLoader] Spawn missing template id in room {}", roomId);
                return null;
            }

            return new SpawnConfig(type, templateId, quantity, frequency, roomId, containerId, equipment, inventory);
        } catch (IllegalArgumentException e) {
            logger.warn("[DataLoader] Invalid spawn type in room {}: {}", roomId, e.getMessage(), e);
            return null;
        }
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
        SpawnManager spawnManager = SpawnManager.getInstance();
        com.example.tassmud.persistence.MobileDAO mobileDao = new com.example.tassmud.persistence.MobileDAO();
        int totalSpawns = 0;
        
        for (RoomTemplate t : templates) {
            int roomId;
            if (t.explicitId >= 0) {
                // Ensure the referenced area exists; if not, attempt to create it so FK won't fail
                if (t.areaId > 0) {
                    try {
                        com.example.tassmud.model.Area a = dao.getAreaById(t.areaId);
                        if (a == null) {
                            // create a minimal MERC area placeholder with the explicit id
                            int res = dao.addAreaWithId(t.areaId, "Imported MERC area " + t.areaId, "Imported from MERC");
                            if (res <= 0) {
                                logger.warn("[DataLoader] addAreaWithId placeholder failed for areaId={}", t.areaId);
                            } else {
                                logger.info("[DataLoader] Created placeholder area id={}", t.areaId);
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("[DataLoader] Exception while ensuring area {}: {}", t.areaId, e.getMessage(), e);
                    }
                }
                roomId = dao.addRoomWithId(t.explicitId, t.areaId, t.name, t.shortDesc, t.longDesc, 
                    t.exitN, t.exitE, t.exitS, t.exitW, t.exitU, t.exitD);
                if (roomId <= 0) {
                    logger.warn("[DataLoader] Failed to insert room with explicit id {} key={} name={}", t.explicitId, t.key, t.name);
                }
            } else {
                int nextLocal = areaCounters.getOrDefault(t.areaId, 0);
                if (nextLocal > 999) {
                    roomId = dao.addRoom(t.areaId, t.name, t.shortDesc, t.longDesc, null, null, null, null, null, null);
                } else {
                    int computed = t.areaId * 1000 + nextLocal;
                    roomId = dao.addRoomWithId(computed, t.areaId, t.name, t.shortDesc, t.longDesc,
                        t.exitN, t.exitE, t.exitS, t.exitW, t.exitU, t.exitD);
                    if (roomId <= 0) {
                        logger.warn("[DataLoader] Failed to insert room with computed id {} key={} name={}", computed, t.key, t.name);
                    }
                    if (roomId > 0) areaCounters.put(t.areaId, nextLocal + 1);
                }
            }
            if (roomId > 0) {
                keyToId.put(t.key, roomId);
                
                // Insert room flags into the room_flag table
                if (t.flags != null && !t.flags.isEmpty()) {
                    for (String flagKey : t.flags) {
                        dao.addRoomFlag(roomId, flagKey);
                    }
                    logger.debug("[DataLoader] Added {} flags to room {}: {}", t.flags.size(), roomId, t.flags);
                }
                
                // Register spawns with the SpawnManager and seed spawn mappings for mobs
                for (SpawnConfig spawn : t.spawns) {
                    // Determine how many mapping UUIDs to seed. Do NOT mutate the SpawnConfig object
                    // since its fields are final; only limit mapping quantity to 1 for MOBs and Objects in room.
                    int mappingQty = spawn.quantity;
                    if ((spawn.type == SpawnConfig.SpawnType.MOB || spawn.type == SpawnConfig.SpawnType.ITEM) && spawn.quantity > 1) {
                        logger.info("[DataLoader] Limiting spawn mappings to 1 for room {} template {} (configured {})", roomId, spawn.templateId, spawn.quantity);
                        mappingQty = 1;
                    }

                    spawnManager.registerSpawn(t.areaId, spawn);
                    // If this is a mob spawn, ensure mapping UUIDs exist for the configured (limited) quantity
                    if (spawn.type == SpawnConfig.SpawnType.MOB) {
                        mobileDao.ensureSpawnMappings(roomId, spawn.templateId, mappingQty);
                    }
                    totalSpawns++;
                }
            }
        }
        
        if (totalSpawns > 0) {
            logger.info("[DataLoader] Registered {} spawns with SpawnManager", totalSpawns);
        }
        
        return keyToId;
    }

    private static void loadRoomsSecondPass(CharacterDAO dao, Map<String,Integer> keyToId) {
        // Second pass: process MERC-specific rooms.yaml files under /data/MERC/* first,
        // then fall back to the global /data/rooms.yaml if present.
        List<String> mercDirs = listMercAreaDirs();
        if (!mercDirs.isEmpty()) {
            for (String dir : mercDirs) {
                String resourcePath = "/data/MERC/" + dir + "/rooms.yaml";
                try (InputStream in = DataLoader.class.getResourceAsStream(resourcePath)) {
                    if (in == null) continue;
                    try {
                        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
                        Map<String, Object> root = yaml.load(in);
                        if (root == null) continue;
                        List<Map<String, Object>> roomList = (List<Map<String, Object>>) root.get("rooms");
                        if (roomList == null) continue;
                        for (Map<String, Object> roomData : roomList) {
                            String key = getString(roomData, "key", "");
                            if (key.isEmpty()) continue;
                            Integer roomId = keyToId.get(key);
                            if (roomId == null) continue;

                            // Exits
                            Object exitsObj = roomData.get("exits");
                            if (exitsObj instanceof Map) {
                                Map<String, Object> exits = (Map<String, Object>) exitsObj;
                                Integer exitN = resolveExitTokenFromYaml(exits.get("north"), keyToId);
                                Integer exitE = resolveExitTokenFromYaml(exits.get("east"), keyToId);
                                Integer exitS = resolveExitTokenFromYaml(exits.get("south"), keyToId);
                                Integer exitW = resolveExitTokenFromYaml(exits.get("west"), keyToId);
                                Integer exitU = resolveExitTokenFromYaml(exits.get("up"), keyToId);
                                Integer exitD = resolveExitTokenFromYaml(exits.get("down"), keyToId);
                                dao.updateRoomExits(roomId, exitN, exitE, exitS, exitW, exitU, exitD);
                            }

                            // Doors
                            Object doorsObj = roomData.get("doors");
                            if (doorsObj instanceof Map) {
                                Map<String, Object> doors = (Map<String, Object>) doorsObj;
                                for (Map.Entry<String, Object> e : doors.entrySet()) {
                                    String doorDir = e.getKey();
                                    Object v = e.getValue();
                                    Integer toId = null;
                                    String state = "OPEN";
                                    boolean locked = false, hidden = false, blocked = false;
                                    Integer keyItem = null;

                                    if (v instanceof Map) {
                                        Map<String, Object> props = (Map<String, Object>) v;
                                        Object toToken = props.get("to");
                                        toId = resolveExitTokenFromYaml(toToken, keyToId);
                                        if (toId == null) {
                                            Object exitsObj2 = roomData.get("exits");
                                            if (exitsObj2 instanceof Map) {
                                                Map<String, Object> exits2 = (Map<String, Object>) exitsObj2;
                                                toId = resolveExitTokenFromYaml(exits2.get(doorDir), keyToId);
                                            }
                                        }
                                        state = getString(props, "state", state).toUpperCase();
                                        locked = getBoolean(props, "locked", false);
                                        hidden = getBoolean(props, "hidden", false);
                                        blocked = getBoolean(props, "blocked", false);
                                        keyItem = getInt(props, "key", 0);
                                        String doorDesc = getString(props, "description", "");
                                        if (keyItem != null && keyItem == 0) keyItem = null;
                                        dao.upsertDoor(roomId, doorDir, toId, state, locked, hidden, blocked, keyItem, doorDesc);
                                    } else {
                                        Object exitsObj2 = roomData.get("exits");
                                        if (exitsObj2 instanceof Map) {
                                            Map<String, Object> exits2 = (Map<String, Object>) exitsObj2;
                                            toId = resolveExitTokenFromYaml(exits2.get(doorDir), keyToId);
                                        }
                                    }
                                    if (!(v instanceof Map)) {
                                        dao.upsertDoor(roomId, doorDir, toId, state, locked, hidden, blocked, keyItem, null);
                                    }
                                }
                            }

                            // Extras
                            Object extrasObj = roomData.get("extras");
                            if (extrasObj instanceof Map) {
                                Map<String, Object> extras = (Map<String, Object>) extrasObj;
                                for (Map.Entry<String, Object> ex : extras.entrySet()) {
                                    String exKey = ex.getKey();
                                    Object exVal = ex.getValue();
                                    String exDesc = exVal == null ? "" : exVal.toString();
                                    dao.upsertRoomExtra(roomId, exKey, exDesc);
                                }
                            }
                        }
                    
                    } catch (Exception e) {
                        logger.warn("Failed to process doors from {}: {}", resourcePath, e.getMessage(), e);
                    }
                }
            
            catch (Exception e) {
                logger.warn("[DataLoader] Failed to load MERC rooms from {}: {}", resourcePath, e.getMessage(), e);
            }
            }
        } 
    }
    
    private static Integer resolveExitTokenFromYaml(Object tokenObj, Map<String,Integer> keyToId) {
        if (tokenObj == null) return null;
        if (tokenObj instanceof Number) return ((Number) tokenObj).intValue();
        if (tokenObj instanceof String) {
            String s = ((String) tokenObj).trim();
            if (s.isEmpty()) return null;
            Integer byKey = keyToId.get(s);
            if (byKey != null) return byKey;
            try { return Integer.parseInt(s); } catch (Exception e) { return null; }
        }
        return null;
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
