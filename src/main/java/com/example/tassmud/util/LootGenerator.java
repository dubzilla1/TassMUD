package com.example.tassmud.util;

import com.example.tassmud.model.EquipmentSlot;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.persistence.ItemDAO;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates random loot for mob corpses.
 * 
 * Item drop chance: 50% for 1 item, 25% for 2, 12.5% for 3, etc. (halving each time)
 * Item type distribution: 25% trash, 25% weapon, 25% armor, 25% other
 * 
 * Weapons: stats scale with mob level
 *   - Base die progression: d2, d4, d6, d8, d10, d12, d20, d30
 *   - Multiplier: 1 to level
 *   - Ability multiplier: 1.0 to 10.0
 * 
 * Armor: save bonuses scale with mob level
 *   - Armor/fort/ref/will save bonuses: 1 to 50
 * 
 * Magic effects: chance based on level
 *   - level% chance for 1 effect
 *   - level/2% chance for 2 effects  
 *   - level/4% chance for 3 effects
 *   - level/8% chance for 4 effects
 */
public class LootGenerator {
    
    private static final Random RNG = new Random();
    
    // Die progression for weapons
    private static final int[] DIE_PROGRESSION = {2, 4, 6, 8, 10, 12, 20, 30};
    
    // Equipment slots for armor generation
    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.NECK, EquipmentSlot.SHOULDERS, EquipmentSlot.BACK,
        EquipmentSlot.CHEST, EquipmentSlot.ARMS, EquipmentSlot.HANDS, EquipmentSlot.WAIST,
        EquipmentSlot.LEGS, EquipmentSlot.BOOTS
    };
    
    // Weapon names by quality tier
    private static final String[] WEAPON_PREFIXES = {
        "Rusty", "Worn", "Plain", "Sturdy", "Fine", "Superior", "Excellent", "Masterwork", "Legendary"
    };
    
    // Weapon base types
    private static final String[] WEAPON_TYPES = {
        "Sword", "Axe", "Mace", "Dagger", "Spear", "Hammer", "Club", "Staff"
    };
    
    // Armor prefixes by quality tier
    private static final String[] ARMOR_PREFIXES = {
        "Tattered", "Worn", "Plain", "Sturdy", "Reinforced", "Hardened", "Superior", "Masterwork", "Legendary"
    };
    
    // Material types for armor
    private static final String[] ARMOR_MATERIALS = {
        "Cloth", "Leather", "Hide", "Chain", "Scale", "Plate"
    };
    
    // Armor piece names by slot
    private static final String[][] ARMOR_PIECES = {
        {"Cap", "Hood", "Helm", "Coif"},           // HEAD
        {"Pendant", "Amulet", "Necklace"},         // NECK  
        {"Mantle", "Pauldrons", "Shoulderguards"}, // SHOULDERS
        {"Cloak", "Cape", "Shroud"},               // BACK
        {"Shirt", "Vest", "Cuirass", "Breastplate"}, // CHEST
        {"Bracers", "Armguards", "Vambraces"},     // ARMS
        {"Gloves", "Gauntlets", "Handwraps"},      // HANDS
        {"Belt", "Sash", "Girdle"},                // WAIST
        {"Leggings", "Pants", "Greaves"},          // LEGS
        {"Boots", "Shoes", "Sabatons"}             // BOOTS
    };
    
    // Magical effect IDs that can be applied to items
    // These should match effect IDs from effects.yaml
    private static final String[] MAGIC_EFFECT_IDS = {
        "202",  // Bless - hit bonus
        "500",  // Heroism
        // Add more effect IDs as they're defined
    };
    
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
        OTHER
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
        List<GeneratedItem> generatedItems = new ArrayList<>();
        
        // Determine how many items to generate
        // 50% chance for 1, 25% for 2, 12.5% for 3, etc.
        int itemCount = determineItemCount();
        
        for (int i = 0; i < itemCount; i++) {
            GeneratedItem item = generateSingleItem(mobLevel);
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
     * Used by GM spawn command to create level-appropriate random loot.
     * 
     * @param level The item level to scale stats to
     * @param roomId The room to place the item in
     * @param itemDAO DAO to create item instances
     * @return The generated item info, or null if creation failed
     */
    public static GeneratedItem generateItemInRoom(int level, int roomId, ItemDAO itemDAO) {
        GeneratedItem item = generateSingleItem(level);
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
     * Applies random stat overrides based on the item's type (weapon/armor).
     * 
     * @param templateId The item template ID to use
     * @param level The item level to scale stats to
     * @param roomId The room to place the item in
     * @param itemDAO DAO to create item instances
     * @return The instance ID of the created item, or -1 on failure
     */
    public static long generateItemFromTemplateInRoom(int templateId, int level, int roomId, ItemDAO itemDAO) {
        ItemTemplate template = itemDAO.getTemplateById(templateId);
        if (template == null) return -1;
        
        // Determine item type and generate appropriate stats
        boolean isWeapon = template.hasType("weapon");
        boolean isArmor = template.hasType("armor") || template.hasType("shield");
        
        Integer baseDieOverride = null;
        Integer multiplierOverride = null;
        Double abilityMultOverride = null;
        Integer armorSaveOverride = null;
        Integer fortSaveOverride = null;
        Integer refSaveOverride = null;
        Integer willSaveOverride = null;
        Integer valueOverride = null;
        
        if (isWeapon) {
            // Generate weapon stats scaled to level
            int dieIndex = Math.min(DIE_PROGRESSION.length - 1, level / 7);
            baseDieOverride = DIE_PROGRESSION[dieIndex];
            
            multiplierOverride = 1 + (level / 10);
            multiplierOverride = Math.max(1, Math.min(multiplierOverride, level));
            
            double abilityMult = 1.0 + (level - 1) * 0.18;
            abilityMult = Math.max(1.0, Math.min(10.0, abilityMult));
            abilityMultOverride = Math.round(abilityMult * 10.0) / 10.0;
            
            // Generate magic effects
            String[] effects = generateMagicEffects(level);
            
            valueOverride = calculateWeaponValue(baseDieOverride, multiplierOverride, abilityMultOverride, effects);
            
            return itemDAO.createGeneratedInstanceInRoom(
                templateId, roomId,
                null, null, level, // No custom name/description - use template's
                baseDieOverride, multiplierOverride, abilityMultOverride,
                null, null, null, null,
                effects[0], effects[1], effects[2], effects[3],
                valueOverride
            );
        } else if (isArmor) {
            // Generate armor stats scaled to level
            armorSaveOverride = 1 + (level * 49 / 50);
            armorSaveOverride = Math.max(1, Math.min(50, armorSaveOverride));
            
            fortSaveOverride = armorSaveOverride / 3 + RNG.nextInt(3);
            refSaveOverride = armorSaveOverride / 4 + RNG.nextInt(3);
            willSaveOverride = armorSaveOverride / 4 + RNG.nextInt(3);
            
            // Generate magic effects
            String[] effects = generateMagicEffects(level);
            
            valueOverride = calculateArmorValue(armorSaveOverride, fortSaveOverride, refSaveOverride, willSaveOverride, effects);
            
            return itemDAO.createGeneratedInstanceInRoom(
                templateId, roomId,
                null, null, level,
                null, null, null,
                armorSaveOverride, fortSaveOverride, refSaveOverride, willSaveOverride,
                effects[0], effects[1], effects[2], effects[3],
                valueOverride
            );
        } else {
            // Not a weapon or armor - just create with item level, no stat overrides
            return itemDAO.createGeneratedInstanceInRoom(
                templateId, roomId,
                null, null, level,
                null, null, null,
                null, null, null, null,
                null, null, null, null,
                null
            );
        }
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
            chance *= 0.5; // Halve the chance each time
        }
        
        return count;
    }
    
    /**
     * Generate a single random item.
     * Type distribution: 25% trash, 25% weapon, 25% armor, 25% other
     */
    private static GeneratedItem generateSingleItem(int mobLevel) {
        int roll = RNG.nextInt(100);
        
        if (roll < 25) {
            return generateTrash(mobLevel);
        } else if (roll < 50) {
            return generateWeapon(mobLevel);
        } else if (roll < 75) {
            return generateArmor(mobLevel);
        } else {
            // Other items - for now, just generate another weapon or armor
            return RNG.nextBoolean() ? generateWeapon(mobLevel) : generateArmor(mobLevel);
        }
    }
    
    /**
     * Generate a comically absurd piece of trash using the TrashGenerator.
     */
    private static GeneratedItem generateTrash(int mobLevel) {
        TrashGenerator.GeneratedTrash trash = TrashGenerator.generate();
        
        // Use void_trash template (ID 1) as base
        // Value is always 0 for trash
        return new GeneratedItem(
            1, // void_trash template ID
            trash.name, 
            trash.description, 
            mobLevel,
            null, null, null, // No weapon stats
            null, null, null, null, // No armor stats
            null, null, null, null, // No magic effects
            0, // Value is always 0
            LootType.TRASH
        );
    }
    
    /**
     * Generate a random weapon with stats scaled to mob level.
     */
    private static GeneratedItem generateWeapon(int mobLevel) {
        // Calculate quality tier (0-8 based on level)
        int qualityTier = Math.min(8, mobLevel / 6);
        
        // Select weapon type
        String weaponType = WEAPON_TYPES[RNG.nextInt(WEAPON_TYPES.length)];
        String prefix = WEAPON_PREFIXES[qualityTier];
        String name = prefix + " " + weaponType;
        
        // Calculate weapon stats
        // Die progression based on level (higher levels = bigger dice)
        int dieIndex = Math.min(DIE_PROGRESSION.length - 1, mobLevel / 7);
        int baseDie = DIE_PROGRESSION[dieIndex];
        
        // Multiplier: 1 at level 1, scales up to level at high levels
        int multiplier = 1 + (mobLevel / 10);
        multiplier = Math.max(1, Math.min(multiplier, mobLevel));
        
        // Ability multiplier: 1.0 at level 1, up to 10.0 at level 50
        double abilityMult = 1.0 + (mobLevel - 1) * 0.18; // ~10.0 at level 50
        abilityMult = Math.max(1.0, Math.min(10.0, abilityMult));
        // Round to 1 decimal place
        abilityMult = Math.round(abilityMult * 10.0) / 10.0;
        
        // Generate magic effects
        String[] effects = generateMagicEffects(mobLevel);
        
        // Calculate value
        int value = calculateWeaponValue(baseDie, multiplier, abilityMult, effects);
        
        // Build description
        String description = String.format("A %s that deals %dd%d damage.",
            name.toLowerCase(), multiplier, baseDie);
        if (effects[0] != null) {
            description += " It glows with magical energy.";
        }
        
        // Use iron sword template (ID 7) as base
        return new GeneratedItem(
            7, // iron_sword template ID
            name, description, mobLevel,
            baseDie, multiplier, abilityMult,
            null, null, null, null, // No armor stats
            effects[0], effects[1], effects[2], effects[3],
            value, LootType.WEAPON
        );
    }
    
    /**
     * Generate random armor with stats scaled to mob level.
     */
    private static GeneratedItem generateArmor(int mobLevel) {
        // Calculate quality tier (0-8 based on level)
        int qualityTier = Math.min(8, mobLevel / 6);
        
        // Select random slot
        int slotIndex = RNG.nextInt(ARMOR_SLOTS.length);
        EquipmentSlot slot = ARMOR_SLOTS[slotIndex];
        
        // Select material based on level
        int materialIndex = Math.min(ARMOR_MATERIALS.length - 1, mobLevel / 9);
        String material = ARMOR_MATERIALS[materialIndex];
        
        // Select piece name for slot
        String[] piecesForSlot = ARMOR_PIECES[slotIndex];
        String pieceName = piecesForSlot[RNG.nextInt(piecesForSlot.length)];
        
        String prefix = ARMOR_PREFIXES[qualityTier];
        String name = prefix + " " + material + " " + pieceName;
        
        // Calculate armor stats (1 to 50 based on level)
        // Armor save is primary, others are secondary
        int armorSave = 1 + (mobLevel * 49 / 50); // 1 at level 1, 50 at level 50
        armorSave = Math.max(1, Math.min(50, armorSave));
        
        // Secondary saves are lower
        int fortSave = armorSave / 3 + RNG.nextInt(3);
        int refSave = armorSave / 4 + RNG.nextInt(3);
        int willSave = armorSave / 4 + RNG.nextInt(3);
        
        // Generate magic effects
        String[] effects = generateMagicEffects(mobLevel);
        
        // Calculate value
        int value = calculateArmorValue(armorSave, fortSave, refSave, willSave, effects);
        
        // Build description
        String description = String.format("A piece of %s that provides moderate protection.", name.toLowerCase());
        if (effects[0] != null) {
            description += " It hums with magical power.";
        }
        
        // Find a suitable armor template based on slot
        int templateId = getArmorTemplateForSlot(slot);
        
        return new GeneratedItem(
            templateId,
            name, description, mobLevel,
            null, null, null, // No weapon stats
            armorSave, fortSave, refSave, willSave,
            effects[0], effects[1], effects[2], effects[3],
            value, LootType.ARMOR
        );
    }
    
    /**
     * Generate magic effects based on mob level.
     * level% chance for 1, level/2% for 2, level/4% for 3, level/8% for 4
     */
    private static String[] generateMagicEffects(int mobLevel) {
        String[] effects = new String[4];
        
        // No effects available yet? Return empty
        if (MAGIC_EFFECT_IDS.length == 0) {
            return effects;
        }
        
        // Check for each effect slot
        int effectCount = 0;
        
        // 1st effect: level% chance
        if (RNG.nextInt(100) < mobLevel) {
            effects[0] = MAGIC_EFFECT_IDS[RNG.nextInt(MAGIC_EFFECT_IDS.length)];
            effectCount++;
            
            // 2nd effect: level/2% chance
            if (RNG.nextInt(100) < mobLevel / 2) {
                effects[1] = pickDifferentEffect(effects, effectCount);
                effectCount++;
                
                // 3rd effect: level/4% chance
                if (RNG.nextInt(100) < mobLevel / 4) {
                    effects[2] = pickDifferentEffect(effects, effectCount);
                    effectCount++;
                    
                    // 4th effect: level/8% chance
                    if (RNG.nextInt(100) < mobLevel / 8) {
                        effects[3] = pickDifferentEffect(effects, effectCount);
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
            // Not enough unique effects, just pick random
            return MAGIC_EFFECT_IDS[RNG.nextInt(MAGIC_EFFECT_IDS.length)];
        }
        
        // Try to pick a different effect
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
        
        // Fallback: just return any effect
        return MAGIC_EFFECT_IDS[RNG.nextInt(MAGIC_EFFECT_IDS.length)];
    }
    
    /**
     * Calculate gold value of a weapon based on its stats.
     * Formula: (baseDie * multiplier * 10) + (abilityMult * 50) + (effectCount * 100)
     */
    private static int calculateWeaponValue(int baseDie, int multiplier, double abilityMult, String[] effects) {
        int baseValue = baseDie * multiplier * 10;
        int abilityBonus = (int) (abilityMult * 50);
        int magicBonus = countEffects(effects) * 100;
        return baseValue + abilityBonus + magicBonus;
    }
    
    /**
     * Calculate gold value of armor based on its stats.
     * Formula: (armorSave * 20) + (fortSave + refSave + willSave) * 10 + (effectCount * 100)
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
     * Get a suitable armor template ID for a given equipment slot.
     * These should match template IDs from items.yaml.
     */
    private static int getArmorTemplateForSlot(EquipmentSlot slot) {
        // Map slots to base armor template IDs from items.yaml
        // Using cloth armor set as base (IDs 100-109)
        switch (slot) {
            case HEAD: return 100;       // cloth_hood
            case SHOULDERS: return 101;  // cloth_mantle  
            case CHEST: return 102;      // cloth_robe
            case ARMS: return 103;       // cloth_sleeves
            case HANDS: return 104;      // cloth_gloves
            case WAIST: return 105;      // cloth_cord
            case LEGS: return 106;       // cloth_pants
            case BOOTS: return 107;      // cloth_sandals
            case NECK: return 5;         // leather_cap (temporary)
            case BACK: return 5;         // leather_cap (temporary)
            default: return 100;
        }
    }
}
