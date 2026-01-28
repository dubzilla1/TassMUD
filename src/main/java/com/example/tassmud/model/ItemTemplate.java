package com.example.tassmud.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

public class ItemTemplate {
    public final int id;
    public final String key;
    public final String name;
    public final String description;
    public final double weight;
    public final int value;
    public final java.util.List<String> traits;
    public final java.util.List<String> keywords;
    /** @deprecated Use {@link #types} instead. Kept for backwards compatibility. */
    @Deprecated
    public final String type;
    /** List of item types (e.g., ["container", "immobile"]). Items can have multiple types. */
    public final List<String> types;
    public final String subtype;
    public final String slot;
    public final int capacity;
    public final int handCount;
    public final boolean indestructable;
    public final boolean magical;
    public final int maxItems;
    public final int maxWeight;
    public final int armorSaveBonus;
    public final int fortSaveBonus;
    public final int refSaveBonus;
    public final int willSaveBonus;
    public final int baseDie;
    public final int multiplier;
    public final int hands;
    public final String abilityScore;
    public final double abilityMultiplier;
    public final String spellEffectId1;
    public final String spellEffectId2;
    public final String spellEffectId3;
    public final String spellEffectId4;
    public final String templateJson;
    public final WeaponCategory weaponCategory;
    public final WeaponFamily weaponFamily;
    public final ArmorCategory armorCategory;
    
    /** Minimum item level for instances (default 1). Used for level-scaled items like potions. */
    public final int minItemLevel;
    /** Maximum item level for instances (default 1). Used for level-scaled items like potions. */
    public final int maxItemLevel;
    
    /** List of spell IDs to cast when item is used. Empty list means item is not usable. */
    public final List<Integer> onUseSpellIds;
    /** Number of uses for on-use spells. -1 = unlimited, 0+ = limited charges. */
    public final int uses;
    /** List of effect IDs applied while item is equipped. Empty list means no equip effects. */
    public final List<String> onEquipEffectIds;
    
    /** Cached lowercase type set for efficient lookups */
    private final Set<String> typeSet;

    public ItemTemplate(
        int id,
        String key,
        String name,
        String description,
        double weight,
        int value,
        java.util.List<String> traits,
        java.util.List<String> keywords,
        String type,
        String subtype,
        String slot,
        int capacity,
        int handCount,
        boolean indestructable,
        boolean magical,
        int maxItems,
        int maxWeight,
        int armorSaveBonus,
        int fortSaveBonus,
        int refSaveBonus,
        int willSaveBonus,
        int baseDie,
        int multiplier,
        int hands,
        String abilityScore,
        double abilityMultiplier,
        String spellEffectId1,
        String spellEffectId2,
        String spellEffectId3,
        String spellEffectId4,
        String templateJson,
        WeaponCategory weaponCategory,
        WeaponFamily weaponFamily,
        ArmorCategory armorCategory
    ) {
        // Delegate to full constructor with default item levels
        // Convert single type to list inline for Java 17 compatibility
        this(id, key, name, description, weight, value, traits, keywords,
             (type == null || type.isBlank()) ? null : List.of(type), // convert type to list
             subtype, slot, capacity, handCount, indestructable, magical, maxItems, maxWeight,
             armorSaveBonus, fortSaveBonus, refSaveBonus, willSaveBonus,
             baseDie, multiplier, hands, abilityScore, abilityMultiplier,
             spellEffectId1, spellEffectId2, spellEffectId3, spellEffectId4,
             templateJson, weaponCategory, weaponFamily, armorCategory,
             1, 1, // default minItemLevel=1, maxItemLevel=1
             null, 0, null); // no on-use spells, no uses, no equip effects
    }
    
    /**
     * Full constructor with item level range.
     */
    public ItemTemplate(
        int id,
        String key,
        String name,
        String description,
        double weight,
        int value,
        java.util.List<String> traits,
        java.util.List<String> keywords,
        String type,
        String subtype,
        String slot,
        int capacity,
        int handCount,
        boolean indestructable,
        boolean magical,
        int maxItems,
        int maxWeight,
        int armorSaveBonus,
        int fortSaveBonus,
        int refSaveBonus,
        int willSaveBonus,
        int baseDie,
        int multiplier,
        int hands,
        String abilityScore,
        double abilityMultiplier,
        String spellEffectId1,
        String spellEffectId2,
        String spellEffectId3,
        String spellEffectId4,
        String templateJson,
        WeaponCategory weaponCategory,
        WeaponFamily weaponFamily,
        ArmorCategory armorCategory,
        int minItemLevel,
        int maxItemLevel
    ) {
        // Delegate to full constructor with default on-use/equip effects
        // Convert single type to list inline for Java 17 compatibility
        this(id, key, name, description, weight, value, traits, keywords,
             (type == null || type.isBlank()) ? null : List.of(type), // convert type to list
             subtype, slot, capacity, handCount, indestructable, magical, maxItems, maxWeight,
             armorSaveBonus, fortSaveBonus, refSaveBonus, willSaveBonus,
             baseDie, multiplier, hands, abilityScore, abilityMultiplier,
             spellEffectId1, spellEffectId2, spellEffectId3, spellEffectId4,
             templateJson, weaponCategory, weaponFamily, armorCategory,
             minItemLevel, maxItemLevel,
             null, 0, null); // no on-use spells, no uses, no equip effects
    }
    
    /**
     * Constructor that accepts multiple types.
     * Use this constructor when loading items from YAML with a types list.
     */
    public ItemTemplate(
        int id,
        String key,
        String name,
        String description,
        double weight,
        int value,
        java.util.List<String> traits,
        java.util.List<String> keywords,
        java.util.List<String> types,
        String subtype,
        String slot,
        int capacity,
        int handCount,
        boolean indestructable,
        boolean magical,
        int maxItems,
        int maxWeight,
        int armorSaveBonus,
        int fortSaveBonus,
        int refSaveBonus,
        int willSaveBonus,
        int baseDie,
        int multiplier,
        int hands,
        String abilityScore,
        double abilityMultiplier,
        String spellEffectId1,
        String spellEffectId2,
        String spellEffectId3,
        String spellEffectId4,
        String templateJson,
        WeaponCategory weaponCategory,
        WeaponFamily weaponFamily,
        ArmorCategory armorCategory
    ) {
        // Delegate to full constructor with default item levels and no use/equip effects
        this(id, key, name, description, weight, value, traits, keywords, types, subtype, slot,
             capacity, handCount, indestructable, magical, maxItems, maxWeight,
             armorSaveBonus, fortSaveBonus, refSaveBonus, willSaveBonus,
             baseDie, multiplier, hands, abilityScore, abilityMultiplier,
             spellEffectId1, spellEffectId2, spellEffectId3, spellEffectId4,
             templateJson, weaponCategory, weaponFamily, armorCategory,
             1, 1, // default minItemLevel=1, maxItemLevel=1
             null, 0, null); // no on-use spells, no uses, no equip effects
    }
    
    /**
     * Constructor that accepts multiple types with item level range.
     * Used by ItemDAO when loading items from database.
     */
    public ItemTemplate(
        int id,
        String key,
        String name,
        String description,
        double weight,
        int value,
        java.util.List<String> traits,
        java.util.List<String> keywords,
        java.util.List<String> types,
        String subtype,
        String slot,
        int capacity,
        int handCount,
        boolean indestructable,
        boolean magical,
        int maxItems,
        int maxWeight,
        int armorSaveBonus,
        int fortSaveBonus,
        int refSaveBonus,
        int willSaveBonus,
        int baseDie,
        int multiplier,
        int hands,
        String abilityScore,
        double abilityMultiplier,
        String spellEffectId1,
        String spellEffectId2,
        String spellEffectId3,
        String spellEffectId4,
        String templateJson,
        WeaponCategory weaponCategory,
        WeaponFamily weaponFamily,
        ArmorCategory armorCategory,
        int minItemLevel,
        int maxItemLevel
    ) {
        // Delegate to full constructor with no use/equip effects
        this(id, key, name, description, weight, value, traits, keywords, types, subtype, slot,
             capacity, handCount, indestructable, magical, maxItems, maxWeight,
             armorSaveBonus, fortSaveBonus, refSaveBonus, willSaveBonus,
             baseDie, multiplier, hands, abilityScore, abilityMultiplier,
             spellEffectId1, spellEffectId2, spellEffectId3, spellEffectId4,
             templateJson, weaponCategory, weaponFamily, armorCategory,
             minItemLevel, maxItemLevel,
             null, 0, null); // no on-use spells, no uses, no equip effects
    }
    
    /**
     * Full constructor that accepts multiple types and item level range.
     */
    public ItemTemplate(
        int id,
        String key,
        String name,
        String description,
        double weight,
        int value,
        java.util.List<String> traits,
        java.util.List<String> keywords,
        java.util.List<String> types,
        String subtype,
        String slot,
        int capacity,
        int handCount,
        boolean indestructable,
        boolean magical,
        int maxItems,
        int maxWeight,
        int armorSaveBonus,
        int fortSaveBonus,
        int refSaveBonus,
        int willSaveBonus,
        int baseDie,
        int multiplier,
        int hands,
        String abilityScore,
        double abilityMultiplier,
        String spellEffectId1,
        String spellEffectId2,
        String spellEffectId3,
        String spellEffectId4,
        String templateJson,
        WeaponCategory weaponCategory,
        WeaponFamily weaponFamily,
        ArmorCategory armorCategory,
        int minItemLevel,
        int maxItemLevel,
        List<Integer> onUseSpellIds,
        int uses,
        List<String> onEquipEffectIds
    ) {
        this.id = id;
        this.key = key;
        this.name = name;
        this.description = description;
        this.weight = weight;
        this.value = value;
        this.traits = traits == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(traits));
        this.keywords = keywords == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(keywords));
        // Build types list and set
        if (types == null || types.isEmpty()) {
            this.types = Collections.emptyList();
            this.typeSet = Collections.emptySet();
            this.type = null;
        } else {
            List<String> normalizedTypes = new ArrayList<>();
            Set<String> normalizedSet = new HashSet<>();
            for (String t : types) {
                if (t != null && !t.isBlank()) {
                    String normalized = t.trim().toLowerCase();
                    normalizedTypes.add(normalized);
                    normalizedSet.add(normalized);
                }
            }
            this.types = Collections.unmodifiableList(normalizedTypes);
            this.typeSet = Collections.unmodifiableSet(normalizedSet);
            // Set deprecated type field to first type for backwards compatibility
            this.type = normalizedTypes.isEmpty() ? null : normalizedTypes.get(0);
        }
        this.subtype = subtype;
        this.slot = slot;
        this.capacity = capacity;
        this.handCount = handCount;
        this.indestructable = indestructable;
        this.magical = magical;
        this.maxItems = maxItems;
        this.maxWeight = maxWeight;
        this.armorSaveBonus = armorSaveBonus;
        this.fortSaveBonus = fortSaveBonus;
        this.refSaveBonus = refSaveBonus;
        this.willSaveBonus = willSaveBonus;
        this.baseDie = baseDie;
        this.multiplier = multiplier;
        this.hands = hands;
        this.abilityScore = abilityScore;
        this.abilityMultiplier = abilityMultiplier;
        this.spellEffectId1 = spellEffectId1;
        this.spellEffectId2 = spellEffectId2;
        this.spellEffectId3 = spellEffectId3;
        this.spellEffectId4 = spellEffectId4;
        this.templateJson = templateJson;
        this.weaponCategory = weaponCategory;
        this.weaponFamily = weaponFamily;
        this.armorCategory = armorCategory;
        // Item level range - defaults to 1 if not specified
        this.minItemLevel = minItemLevel > 0 ? minItemLevel : 1;
        this.maxItemLevel = maxItemLevel > 0 ? maxItemLevel : 1;
        // On-use spell system
        this.onUseSpellIds = onUseSpellIds == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(onUseSpellIds));
        this.uses = uses;
        this.onEquipEffectIds = onEquipEffectIds == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(onEquipEffectIds));
    }
    
    /**
     * Check if this item can be used (has on-use spells).
     * @return true if the item has at least one on-use spell
     */
    public boolean isUsable() {
        return onUseSpellIds != null && !onUseSpellIds.isEmpty();
    }
    
    /**
     * Check if this item has equip effects.
     * @return true if the item has at least one on-equip effect
     */
    public boolean hasEquipEffects() {
        return onEquipEffectIds != null && !onEquipEffectIds.isEmpty();
    }
    
    /**
     * Check if this item has magical use (casts spells when used).
     * Items with this property are shown with "(MAGICAL)" suffix.
     * @return true if the item has at least one on-use spell
     */
    public boolean hasMagicalUse() {
        return onUseSpellIds != null && !onUseSpellIds.isEmpty();
    }
    
    /**
     * Check if this item has the given type (case-insensitive).
     * @param typeName The type to check for
     * @return true if this item has the given type
     */
    public boolean hasType(String typeName) {
        if (typeName == null) return false;
        return typeSet.contains(typeName.trim().toLowerCase());
    }
    
    /**
     * Check if this item is a weapon.
     */
    public boolean isWeapon() {
        return hasType("weapon");
    }
    
    /**
     * Get the weapon category, or null if not a weapon or not categorized.
     */
    public WeaponCategory getWeaponCategory() {
        return weaponCategory;
    }
    
    /**
     * Get the weapon family, or null if not a weapon or not categorized.
     */
    public WeaponFamily getWeaponFamily() {
        return weaponFamily;
    }
    
    /**
     * Check if this item is armor.
     */
    public boolean isArmor() {
        return hasType("armor");
    }
    
    /**
     * Check if this item is a shield.
     */
    public boolean isShield() {
        return hasType("shield");
    }
    
    /**
     * Check if this item is a container.
     */
    public boolean isContainer() {
        return hasType("container");
    }
    
    /**
     * Check if this item is immobile (cannot be picked up).
     */
    public boolean isImmobile() {
        return hasType("immobile");
    }
    
    /**
     * Check if this item is holdable (held type).
     */
    public boolean isHeld() {
        return hasType("held");
    }
    
    /**
     * Check if this item is inventory-only (cannot be equipped).
     */
    public boolean isInventory() {
        return hasType("inventory");
    }
    
    /**
     * Check if this item is trash (no value, can be discarded).
     */
    public boolean isTrash() {
        return hasType("trash");
    }
    
    /**
     * Check if this item is a potion (consumable via quaff command).
     */
    public boolean isPotion() {
        return hasType("potion");
    }
    
    /**
     * Get the armor category, or null if not armor or not categorized.
     */
    public ArmorCategory getArmorCategory() {
        return armorCategory;
    }
}
