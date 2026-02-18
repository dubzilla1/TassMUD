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
    /** Spell effects on this item (e.g., for potions, magical weapons). Up to 4 effect IDs. */
    public final List<String> spellEffectIds;
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

    /** Creates a new builder for constructing ItemTemplate instances. */
    public static Builder builder() { return new Builder(); }

    /**
     * Canonical constructor — prefer using {@link #builder()} instead of calling directly.
     */
    ItemTemplate(
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
        List<String> spellEffectIds,
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
            this.type = normalizedTypes.isEmpty() ? null : normalizedTypes.getFirst();
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
        this.spellEffectIds = spellEffectIds == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(spellEffectIds));
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

    /** Fluent builder for {@link ItemTemplate}. All fields default to 0/null/false/empty except minItemLevel and maxItemLevel (default 1). */
    public static class Builder {
        private int id;
        private String key;
        private String name;
        private String description;
        private double weight;
        private int value;
        private List<String> traits;
        private List<String> keywords;
        private List<String> types;
        private String subtype;
        private String slot;
        private int capacity;
        private int handCount;
        private boolean indestructable;
        private boolean magical;
        private int maxItems;
        private int maxWeight;
        private int armorSaveBonus;
        private int fortSaveBonus;
        private int refSaveBonus;
        private int willSaveBonus;
        private int baseDie;
        private int multiplier;
        private int hands;
        private String abilityScore;
        private double abilityMultiplier;
        private List<String> spellEffectIds;
        private String templateJson;
        private WeaponCategory weaponCategory;
        private WeaponFamily weaponFamily;
        private ArmorCategory armorCategory;
        private int minItemLevel = 1;
        private int maxItemLevel = 1;
        private List<Integer> onUseSpellIds;
        private int uses;
        private List<String> onEquipEffectIds;

        private Builder() {}

        public Builder id(int v) { this.id = v; return this; }
        public Builder key(String v) { this.key = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder weight(double v) { this.weight = v; return this; }
        public Builder value(int v) { this.value = v; return this; }
        public Builder traits(List<String> v) { this.traits = v; return this; }
        public Builder keywords(List<String> v) { this.keywords = v; return this; }
        public Builder types(List<String> v) { this.types = v; return this; }
        public Builder subtype(String v) { this.subtype = v; return this; }
        public Builder slot(String v) { this.slot = v; return this; }
        public Builder capacity(int v) { this.capacity = v; return this; }
        public Builder handCount(int v) { this.handCount = v; return this; }
        public Builder indestructable(boolean v) { this.indestructable = v; return this; }
        public Builder magical(boolean v) { this.magical = v; return this; }
        public Builder maxItems(int v) { this.maxItems = v; return this; }
        public Builder maxWeight(int v) { this.maxWeight = v; return this; }
        public Builder armorSaveBonus(int v) { this.armorSaveBonus = v; return this; }
        public Builder fortSaveBonus(int v) { this.fortSaveBonus = v; return this; }
        public Builder refSaveBonus(int v) { this.refSaveBonus = v; return this; }
        public Builder willSaveBonus(int v) { this.willSaveBonus = v; return this; }
        public Builder baseDie(int v) { this.baseDie = v; return this; }
        public Builder multiplier(int v) { this.multiplier = v; return this; }
        public Builder hands(int v) { this.hands = v; return this; }
        public Builder abilityScore(String v) { this.abilityScore = v; return this; }
        public Builder abilityMultiplier(double v) { this.abilityMultiplier = v; return this; }
        public Builder spellEffectIds(List<String> v) { this.spellEffectIds = v; return this; }
        public Builder templateJson(String v) { this.templateJson = v; return this; }
        public Builder weaponCategory(WeaponCategory v) { this.weaponCategory = v; return this; }
        public Builder weaponFamily(WeaponFamily v) { this.weaponFamily = v; return this; }
        public Builder armorCategory(ArmorCategory v) { this.armorCategory = v; return this; }
        public Builder minItemLevel(int v) { this.minItemLevel = v; return this; }
        public Builder maxItemLevel(int v) { this.maxItemLevel = v; return this; }
        public Builder onUseSpellIds(List<Integer> v) { this.onUseSpellIds = v; return this; }
        public Builder uses(int v) { this.uses = v; return this; }
        public Builder onEquipEffectIds(List<String> v) { this.onEquipEffectIds = v; return this; }

        public ItemTemplate build() {
            return new ItemTemplate(id, key, name, description, weight, value,
                traits, keywords, types, subtype, slot,
                capacity, handCount, indestructable, magical, maxItems, maxWeight,
                armorSaveBonus, fortSaveBonus, refSaveBonus, willSaveBonus,
                baseDie, multiplier, hands, abilityScore, abilityMultiplier,
                spellEffectIds, templateJson, weaponCategory, weaponFamily, armorCategory,
                minItemLevel, maxItemLevel, onUseSpellIds, uses, onEquipEffectIds);
        }
    }
}
