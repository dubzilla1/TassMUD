package com.example.tassmud.util;

import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.WeaponFamily;
import com.example.tassmud.persistence.ItemDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates random loot for mob corpses.
 * 
 * Item drop chance: 50% for 1 item, 25% for 2, 12.5% for 3, etc. (halving each time)
 * Item type distribution: 50% trash, 50% equipment (from 999xxx templates)
 * 
 * Equipment templates are IDs 999000-999999 (generic weapons, armor, shields).
 * All equipment in that range has equal probability of dropping.
 * 
 * Weapons scale with mob level:
 *   - Melee: base_die/multiplier scale at level/5, ability multiplier at level * 0.1
 *   - Ranged: base_die at level/4, multiplier at level/6, ability multiplier at level * 0.1
 *   - Magical: base_die at level/3, multiplier at level/8, ability multiplier at level * 0.1
 * 
 * Armor/Shields scale with mob level:
 *   - Each stat (AC/fort/ref/will) gets base + 1d(level)
 *   - Total capped at level*2 + base_AC
 * 
 * Magic effects: chance based on level
 *   - level% chance for 1 effect
 *   - level/2% chance for 2 effects
 *   - level/4% chance for 3 effects
 *   - level/8% chance for 4 effects
 * 
 * Naming: Quality prefix based on item score + base item name
 */
public class LootGenerator {
    private static final Logger logger = LoggerFactory.getLogger(LootGenerator.class);
    
    private static final Random RNG = new Random();
    // Toggle random loot generation (set to true to disable)
    private static final boolean LOOT_GENERATION_DISABLED = false;
    
    // Template ID range for generic loot items
    private static final int TEMPLATE_MIN_ID = 999000;
    private static final int TEMPLATE_MAX_ID = 999999;
    
    // Die progression for weapons (index used for upgrades)
    private static final int[] DIE_PROGRESSION = {1, 2, 4, 6, 8, 10, 12, 20, 30, 50, 100};
    
    // Quality prefixes ordered from worst to best (20 tiers)
    private static final String[] QUALITY_PREFIXES = {
        "Broken",      // 0
        "Rusty",       // 1
        "Shoddy",      // 2
        "Chipped",     // 3
        "Worn",        // 4
        "Used",        // 5
        "Decent",      // 6
        "Honed",       // 7
        "Fine",        // 8
        "Rare",        // 9
        "Gleaming",    // 10
        "Glorious",    // 11
        "Excellent",   // 12
        "Fantastic",   // 13
        "Incredible",  // 14
        "Spectacular", // 15
        "Outstanding", // 16
        "Epic",        // 17
        "Legendary",   // 18
        "Godly"        // 19
    };
    
    // Ranged weapon families
    private static final List<WeaponFamily> RANGED_FAMILIES = List.of(
        WeaponFamily.BOWS, WeaponFamily.CROSSBOWS, WeaponFamily.SLINGS
    );
    
    // Magical weapon families (staves use INT)
    private static final List<WeaponFamily> MAGICAL_FAMILIES = List.of(
        WeaponFamily.STAVES
    );
    
    // Magical effect IDs that can be applied to items
    private static final String[] MAGIC_EFFECT_IDS = {
        "202",  // Bless - hit bonus
        "500",  // Heroism
    };
    
    // Cache of valid template IDs in range
    private static List<Integer> cachedTemplateIds = null;
    
    /**
     * Result of loot generation for a single item.
     */
    public static class GeneratedItem {
        public final int templateId;
        public final String customName;
        public final String customDescription;
        public final int itemLevel;
        public final Integer baseDieOverride;
        public final Integer multiplierOverride;
        public final Double abilityMultOverride;
        public final Integer armorSaveOverride;
        public final Integer fortSaveOverride;
        public final Integer refSaveOverride;
        public final Integer willSaveOverride;
        public final String spellEffect1;
        public final String spellEffect2;
        public final String spellEffect3;
        public final String spellEffect4;
        public final Integer valueOverride;
        public final LootType lootType;
        
        public GeneratedItem(int templateId, String customName, String customDescription, int itemLevel,
                            Integer baseDieOverride, Integer multiplierOverride, Double abilityMultOverride,
                            Integer armorSaveOverride, Integer fortSaveOverride, Integer refSaveOverride, Integer willSaveOverride,
                            String spellEffect1, String spellEffect2, String spellEffect3, String spellEffect4,
                            Integer valueOverride, LootType lootType) {
            this.templateId = templateId;
            this.customName = customName;
            this.customDescription = customDescription;
            this.itemLevel = itemLevel;
            this.baseDieOverride = baseDieOverride;
            this.multiplierOverride = multiplierOverride;
            this.abilityMultOverride = abilityMultOverride;
            this.armorSaveOverride = armorSaveOverride;
            this.fortSaveOverride = fortSaveOverride;
            this.refSaveOverride = refSaveOverride;
            this.willSaveOverride = willSaveOverride;
            this.spellEffect1 = spellEffect1;
            this.spellEffect2 = spellEffect2;
            this.spellEffect3 = spellEffect3;
            this.spellEffect4 = spellEffect4;
            this.valueOverride = valueOverride;
            this.lootType = lootType;
        }
    }
    
    /**
     * Types of loot that can be generated.
     */
    public enum LootType {
        TRASH,
        WEAPON,
        ARMOR,
        SHIELD,
        OTHER
    }
    
    /**
     * Weapon type classification for scaling purposes.
     */
    private enum WeaponType {
        MELEE,
        RANGED,
        MAGICAL
    }
    
    /**
     * Generate loot for a mob corpse.
     * 
     * @param mobLevel The level of the defeated mob
     * @param corpseInstanceId The instance ID of the corpse container
     * @param itemDAO DAO to create item instances
     * @return List of generated items placed in the corpse
     */
    public static List<GeneratedItem> generateLoot(int mobLevel, long corpseInstanceId, ItemDAO itemDAO) {
        if (LOOT_GENERATION_DISABLED) {
            logger.info("LootGenerator: random loot generation is currently disabled.");
            return new ArrayList<>();
        }
        List<GeneratedItem> generatedItems = new ArrayList<>();
        
        // Determine how many items to generate
        int itemCount = determineItemCount();
        
        for (int i = 0; i < itemCount; i++) {
            GeneratedItem item = generateSingleItem(mobLevel, itemDAO);
            if (item != null) {
                // Create the item in the database inside the corpse
                long instanceId = itemDAO.createGeneratedInstance(
                    item.templateId, corpseInstanceId,
                    item.customName, item.customDescription, item.itemLevel,
                    item.baseDieOverride, item.multiplierOverride, item.abilityMultOverride,
                    item.armorSaveOverride, item.fortSaveOverride, item.refSaveOverride, item.willSaveOverride,
                    item.spellEffect1, item.spellEffect2, item.spellEffect3, item.spellEffect4,
                    item.valueOverride
                );
                if (instanceId > 0) {
                    generatedItems.add(item);
                }
            }
        }
        
        return generatedItems;
    }
    
    /**
     * Generate a single random item at the given level and place it in a room.
     */
    public static GeneratedItem generateItemInRoom(int level, int roomId, ItemDAO itemDAO) {
        if (LOOT_GENERATION_DISABLED) {
            logger.info("LootGenerator: generateItemInRoom is disabled.");
            return null;
        }
        GeneratedItem item = generateSingleItem(level, itemDAO);
        if (item == null) return null;

        long instanceId = itemDAO.createGeneratedInstanceInRoom(
            item.templateId, roomId,
            item.customName, item.customDescription, item.itemLevel,
            item.baseDieOverride, item.multiplierOverride, item.abilityMultOverride,
            item.armorSaveOverride, item.fortSaveOverride, item.refSaveOverride, item.willSaveOverride,
            item.spellEffect1, item.spellEffect2, item.spellEffect3, item.spellEffect4,
            item.valueOverride
        );

        return instanceId > 0 ? item : null;
    }
    
    /**
     * Generate a specific item with level-scaled stats and place it in a room.
     */
    public static long generateItemFromTemplateInRoom(int templateId, int level, int roomId, ItemDAO itemDAO) {
        if (LOOT_GENERATION_DISABLED) {
            logger.info("LootGenerator: generateItemFromTemplateInRoom disabled for templateId={}", templateId);
            return -1;
        }
        ItemTemplate template = itemDAO.getTemplateById(templateId);
        if (template == null) return -1;
        
        GeneratedItem item = generateFromTemplate(template, level);
        if (item == null) return -1;
        
        return itemDAO.createGeneratedInstanceInRoom(
            item.templateId, roomId,
            item.customName, item.customDescription, item.itemLevel,
            item.baseDieOverride, item.multiplierOverride, item.abilityMultOverride,
            item.armorSaveOverride, item.fortSaveOverride, item.refSaveOverride, item.willSaveOverride,
            item.spellEffect1, item.spellEffect2, item.spellEffect3, item.spellEffect4,
            item.valueOverride
        );
    }
    
    /**
     * Determine how many items to generate.
     * 50% for 1, 25% for 2, 12.5% for 3, etc.
     * Capped at 5 items max.
     */
    private static int determineItemCount() {
        int count = 0;
        double chance = 0.5;
        
        while (count < 5 && RNG.nextDouble() < chance) {
            count++;
            chance *= 0.5;
        }
        
        return count;
    }
    
    /**
     * Generate a single random item.
     * 50% trash, 50% equipment from 999xxx range.
     */
    private static GeneratedItem generateSingleItem(int mobLevel, ItemDAO itemDAO) {
        if (RNG.nextInt(100) < 50) {
            return generateTrash(mobLevel);
        } else {
            return generateEquipment(mobLevel, itemDAO);
        }
    }
    
    /**
     * Public wrapper for tests/tools to sample a single generated item.
     */
    public static GeneratedItem sampleSingleItem(int mobLevel) {
        if (LOOT_GENERATION_DISABLED) return null;
        ItemDAO itemDAO = new ItemDAO();
        return generateSingleItem(mobLevel, itemDAO);
    }
    
    /**
     * Generate a comically absurd piece of trash using the TrashGenerator.
     */
    private static GeneratedItem generateTrash(int mobLevel) {
        TrashGenerator.GeneratedTrash trash = TrashGenerator.generate();
        
        return new GeneratedItem(
            1, // void_trash template ID
            trash.name, 
            trash.description, 
            mobLevel,
            null, null, null,
            null, null, null, null,
            null, null, null, null,
            0, // Value is always 0
            LootType.TRASH
        );
    }
    
    /**
     * Generate equipment from the 999xxx template range.
     */
    private static GeneratedItem generateEquipment(int mobLevel, ItemDAO itemDAO) {
        // Get list of valid template IDs in range
        List<Integer> templateIds = getTemplateIdsInRange(itemDAO);
        if (templateIds.isEmpty()) {
            logger.warn("LootGenerator: No templates found in range {}-{}", TEMPLATE_MIN_ID, TEMPLATE_MAX_ID);
            return generateTrash(mobLevel); // Fallback to trash
        }
        
        // Pick a random template
        int templateId = templateIds.get(RNG.nextInt(templateIds.size()));
        ItemTemplate template = itemDAO.getTemplateById(templateId);
        if (template == null) {
            return generateTrash(mobLevel);
        }
        
        return generateFromTemplate(template, mobLevel);
    }
    
    /**
     * Generate a scaled item from a specific template.
     */
    private static GeneratedItem generateFromTemplate(ItemTemplate template, int mobLevel) {
        boolean isWeapon = template.hasType("weapon");
        boolean isArmor = template.hasType("armor");
        boolean isShield = template.hasType("shield");
        
        if (isWeapon) {
            return generateWeaponFromTemplate(template, mobLevel);
        } else if (isArmor || isShield) {
            return generateArmorFromTemplate(template, mobLevel, isShield);
        } else {
            // Non-equipment item - just return with item level
            return new GeneratedItem(
                template.id, null, null, mobLevel,
                null, null, null,
                null, null, null, null,
                null, null, null, null,
                template.value,
                LootType.OTHER
            );
        }
    }
    
    /**
     * Generate a weapon with level-scaled stats.
     */
    private static GeneratedItem generateWeaponFromTemplate(ItemTemplate template, int mobLevel) {
        // Determine weapon type (melee/ranged/magical)
        WeaponType weaponType = classifyWeapon(template);
        
        // Get base values from template
        int baseBaseDie = template.baseDie > 0 ? template.baseDie : 2;
        int baseMultiplier = template.multiplier > 0 ? template.multiplier : 1;
        double baseAbilityMult = template.abilityMultiplier > 0 ? template.abilityMultiplier : 1.0;
        
        // Calculate upgrade chances based on weapon type and level
        int dieUpgradeChances;
        int multUpgradeChances;
        
        switch (weaponType) {
            case RANGED:
                dieUpgradeChances = (int) Math.ceil(mobLevel / 4.0);
                multUpgradeChances = (int) Math.ceil(mobLevel / 6.0);
                break;
            case MAGICAL:
                dieUpgradeChances = (int) Math.ceil(mobLevel / 3.0);
                multUpgradeChances = (int) Math.ceil(mobLevel / 8.0);
                break;
            case MELEE:
            default:
                dieUpgradeChances = (int) Math.ceil(mobLevel / 5.0);
                multUpgradeChances = (int) Math.floor(mobLevel / 5.0);
                break;
        }
        
        // Roll for die upgrades (50% chance each)
        int baseDie = baseBaseDie;
        int currentDieIndex = getDieIndex(baseDie);
        for (int i = 0; i < dieUpgradeChances; i++) {
            if (RNG.nextBoolean() && currentDieIndex < DIE_PROGRESSION.length - 1) {
                currentDieIndex++;
                baseDie = DIE_PROGRESSION[currentDieIndex];
            }
        }
        
        // Roll for multiplier upgrades (50% chance each)
        int multiplier = baseMultiplier;
        for (int i = 0; i < multUpgradeChances; i++) {
            if (RNG.nextBoolean()) {
                multiplier++;
            }
        }
        
        // Roll for ability multiplier upgrades (50% chance for +0.1 per level)
        double abilityMult = baseAbilityMult;
        for (int i = 0; i < mobLevel; i++) {
            if (RNG.nextBoolean()) {
                abilityMult += 0.1;
            }
        }
        abilityMult = Math.round(abilityMult * 10.0) / 10.0; // Round to 1 decimal
        
        // Generate magic effects
        String[] effects = generateMagicEffects(mobLevel);
        
        // Calculate weapon score and quality name
        double weaponScore = calculateWeaponScore(multiplier, baseDie);
        String qualityPrefix = getWeaponQualityPrefix(weaponScore);
        String customName = qualityPrefix + " " + template.name;
        
        // Calculate value based on stats
        int value = calculateWeaponValue(baseDie, multiplier, abilityMult, effects);
        
        return new GeneratedItem(
            template.id,
            customName, null, mobLevel,
            baseDie, multiplier, abilityMult,
            null, null, null, null,
            effects[0], effects[1], effects[2], effects[3],
            value, LootType.WEAPON
        );
    }
    
    /**
     * Generate armor or shield with level-scaled stats.
     */
    private static GeneratedItem generateArmorFromTemplate(ItemTemplate template, int mobLevel, boolean isShield) {
        // Get base values from template
        int baseAC = template.armorSaveBonus;
        int baseFort = template.fortSaveBonus;
        int baseRef = template.refSaveBonus;
        int baseWill = template.willSaveBonus;
        
        // Roll bonuses: each stat gets base + 1d(level)
        int ac = baseAC + (mobLevel > 0 ? RNG.nextInt(mobLevel) + 1 : 0);
        int fort = baseFort + (mobLevel > 0 ? RNG.nextInt(mobLevel) + 1 : 0);
        int ref = baseRef + (mobLevel > 0 ? RNG.nextInt(mobLevel) + 1 : 0);
        int will = baseWill + (mobLevel > 0 ? RNG.nextInt(mobLevel) + 1 : 0);
        
        // Calculate cap: level*2 + base_AC + 10
        int cap = (mobLevel * 2) + baseAC + 10;
        int total = ac + fort + ref + will;
        
        // Reduce stats if over cap, cycling through highest to lowest
        while (total > cap) {
            // Find highest stat and reduce it
            if (ac >= fort && ac >= ref && ac >= will && ac > 0) {
                ac--;
            } else if (fort >= ac && fort >= ref && fort >= will && fort > 0) {
                fort--;
            } else if (will >= ac && will >= fort && will >= ref && will > 0) {
                will--;
            } else if (ref > 0) {
                ref--;
            } else {
                break; // All stats are 0, can't reduce further
            }
            total = ac + fort + ref + will;
        }
        
        // Generate magic effects
        String[] effects = generateMagicEffects(mobLevel);
        
        // Calculate armor score and quality name
        double armorScore = ac + fort + ref + will;
        String qualityPrefix = getArmorQualityPrefix(armorScore);
        String customName = qualityPrefix + " " + template.name;
        
        // Calculate value
        int value = calculateArmorValue(ac, fort, ref, will, effects);
        
        return new GeneratedItem(
            template.id,
            customName, null, mobLevel,
            null, null, null,
            ac, fort, ref, will,
            effects[0], effects[1], effects[2], effects[3],
            value, isShield ? LootType.SHIELD : LootType.ARMOR
        );
    }
    
    /**
     * Classify a weapon as melee, ranged, or magical based on its family.
     */
    private static WeaponType classifyWeapon(ItemTemplate template) {
        WeaponFamily family = template.getWeaponFamily();
        
        if (family != null) {
            if (RANGED_FAMILIES.contains(family)) {
                return WeaponType.RANGED;
            }
            if (MAGICAL_FAMILIES.contains(family)) {
                return WeaponType.MAGICAL;
            }
        }
        
        // Also check ability score - INT-based weapons are magical
        String ability = template.abilityScore;
        if (ability != null && ability.toLowerCase().contains("int")) {
            return WeaponType.MAGICAL;
        }
        
        return WeaponType.MELEE;
    }
    
    /**
     * Get the index of a die value in the progression array.
     */
    private static int getDieIndex(int dieValue) {
        for (int i = 0; i < DIE_PROGRESSION.length; i++) {
            if (DIE_PROGRESSION[i] >= dieValue) {
                return i;
            }
        }
        return DIE_PROGRESSION.length - 1;
    }
    
    /**
     * Calculate weapon score as min + avg + max damage (without ability modifier).
     * min = multiplier * 1
     * max = multiplier * baseDie
     * avg = multiplier * (baseDie + 1) / 2.0
     */
    private static double calculateWeaponScore(int multiplier, int baseDie) {
        double min = multiplier;
        double max = multiplier * baseDie;
        double avg = multiplier * ((baseDie + 1) / 2.0);
        return min + avg + max;
    }
    
    /**
     * Get quality prefix for weapon based on score.
     * Score range: ~4.5 (1d2) to ~1666.5 (11d100)
     * 
     * Thresholds:
     * 0-5: Broken, 5-10: Rusty, 10-15: Shoddy, 15-25: Chipped, 25-40: Worn,
     * 40-60: Used, 60-90: Decent, 90-130: Honed, 130-180: Fine, 180-250: Rare,
     * 250-350: Gleaming, 350-450: Glorious, 450-550: Excellent, 550-650: Fantastic,
     * 650-750: Incredible, 750-850: Spectacular, 850-1000: Outstanding,
     * 1000-1200: Epic, 1200-1500: Legendary, 1500+: Godly
     */
    private static String getWeaponQualityPrefix(double score) {
        if (score >= 1500) return QUALITY_PREFIXES[19]; // Godly
        if (score >= 1200) return QUALITY_PREFIXES[18]; // Legendary
        if (score >= 1000) return QUALITY_PREFIXES[17]; // Epic
        if (score >= 850) return QUALITY_PREFIXES[16];  // Outstanding
        if (score >= 750) return QUALITY_PREFIXES[15];  // Spectacular
        if (score >= 650) return QUALITY_PREFIXES[14];  // Incredible
        if (score >= 550) return QUALITY_PREFIXES[13];  // Fantastic
        if (score >= 450) return QUALITY_PREFIXES[12];  // Excellent
        if (score >= 350) return QUALITY_PREFIXES[11];  // Glorious
        if (score >= 250) return QUALITY_PREFIXES[10];  // Gleaming
        if (score >= 180) return QUALITY_PREFIXES[9];   // Rare
        if (score >= 130) return QUALITY_PREFIXES[8];   // Fine
        if (score >= 90) return QUALITY_PREFIXES[7];    // Honed
        if (score >= 60) return QUALITY_PREFIXES[6];    // Decent
        if (score >= 40) return QUALITY_PREFIXES[5];    // Used
        if (score >= 25) return QUALITY_PREFIXES[4];    // Worn
        if (score >= 15) return QUALITY_PREFIXES[3];    // Chipped
        if (score >= 10) return QUALITY_PREFIXES[2];    // Shoddy
        if (score >= 5) return QUALITY_PREFIXES[1];     // Rusty
        return QUALITY_PREFIXES[0];                      // Broken
    }
    
    /**
     * Get quality prefix for armor based on total stat score.
     * Score range: ~10 (weakest) to ~120 (level 50, base AC 20)
     * 
     * Linear scale with 20 tiers over 110 points = ~5.5 points per tier
     */
    private static String getArmorQualityPrefix(double score) {
        // Map score 0-120 to tier 0-19
        int tier = (int) Math.min(19, Math.max(0, (score - 5) / 6.0));
        return QUALITY_PREFIXES[tier];
    }
    
    /**
     * Generate magic effects based on mob level.
     * level% chance for 1, level/2% for 2, level/4% for 3, level/8% for 4
     */
    private static String[] generateMagicEffects(int mobLevel) {
        String[] effects = new String[4];
        
        if (MAGIC_EFFECT_IDS.length == 0) {
            return effects;
        }
        
        // 1st effect: level% chance
        if (RNG.nextInt(100) < mobLevel) {
            effects[0] = MAGIC_EFFECT_IDS[RNG.nextInt(MAGIC_EFFECT_IDS.length)];
            
            // 2nd effect: level/2% chance
            if (RNG.nextInt(100) < mobLevel / 2) {
                effects[1] = pickDifferentEffect(effects, 1);
                
                // 3rd effect: level/4% chance
                if (RNG.nextInt(100) < mobLevel / 4) {
                    effects[2] = pickDifferentEffect(effects, 2);
                    
                    // 4th effect: level/8% chance
                    if (RNG.nextInt(100) < mobLevel / 8) {
                        effects[3] = pickDifferentEffect(effects, 3);
                    }
                }
            }
        }
        
        return effects;
    }
    
    /**
     * Pick a magic effect different from already selected ones.
     */
    private static String pickDifferentEffect(String[] currentEffects, int count) {
        if (MAGIC_EFFECT_IDS.length <= count) {
            return MAGIC_EFFECT_IDS[RNG.nextInt(MAGIC_EFFECT_IDS.length)];
        }
        
        for (int attempts = 0; attempts < 10; attempts++) {
            String effect = MAGIC_EFFECT_IDS[RNG.nextInt(MAGIC_EFFECT_IDS.length)];
            boolean found = false;
            for (String e : currentEffects) {
                if (effect.equals(e)) {
                    found = true;
                    break;
                }
            }
            if (!found) return effect;
        }
        
        return MAGIC_EFFECT_IDS[RNG.nextInt(MAGIC_EFFECT_IDS.length)];
    }
    
    /**
     * Calculate gold value of a weapon based on its stats.
     */
    private static int calculateWeaponValue(int baseDie, int multiplier, double abilityMult, String[] effects) {
        int baseValue = baseDie * multiplier * 10;
        int abilityBonus = (int) (abilityMult * 50);
        int magicBonus = countEffects(effects) * 100;
        return baseValue + abilityBonus + magicBonus;
    }
    
    /**
     * Calculate gold value of armor based on its stats.
     */
    private static int calculateArmorValue(int armorSave, int fortSave, int refSave, int willSave, String[] effects) {
        int baseValue = armorSave * 20;
        int saveBonus = (fortSave + refSave + willSave) * 10;
        int magicBonus = countEffects(effects) * 100;
        return baseValue + saveBonus + magicBonus;
    }
    
    /**
     * Count non-null effects in array.
     */
    private static int countEffects(String[] effects) {
        int count = 0;
        for (String e : effects) {
            if (e != null) count++;
        }
        return count;
    }
    
    /**
     * Get all template IDs in the 999xxx range.
     * Results are cached for performance.
     * Uses ItemDAO's connection logic for consistent DB access.
     */
    private static List<Integer> getTemplateIdsInRange(ItemDAO itemDAO) {
        if (cachedTemplateIds != null) {
            return cachedTemplateIds;
        }
        
        List<Integer> ids = itemDAO.getTemplateIdsInRange(TEMPLATE_MIN_ID, TEMPLATE_MAX_ID);
        
        if (!ids.isEmpty()) {
            cachedTemplateIds = ids;
            logger.info("LootGenerator: Cached {} template IDs in range {}-{}", ids.size(), TEMPLATE_MIN_ID, TEMPLATE_MAX_ID);
        } else {
            logger.warn("LootGenerator: No templates found in range {}-{}", TEMPLATE_MIN_ID, TEMPLATE_MAX_ID);
        }
        
        return ids;
    }
    
    /**
     * Clear the cached template IDs (call if templates are reloaded).
     */
    public static void clearTemplateCache() {
        cachedTemplateIds = null;
    }
}
