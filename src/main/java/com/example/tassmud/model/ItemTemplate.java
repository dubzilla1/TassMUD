package com.example.tassmud.model;

public class ItemTemplate {
    public final int id;
    public final String key;
    public final String name;
    public final String description;
    public final double weight;
    public final int value;
    public final java.util.List<String> traits;
    public final java.util.List<String> keywords;
    public final String type;
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
        WeaponFamily weaponFamily
    ) {
        this.id = id;
        this.key = key;
        this.name = name;
        this.description = description;
        this.weight = weight;
        this.value = value;
        this.traits = traits == null ? java.util.Collections.emptyList() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(traits));
        this.keywords = keywords == null ? java.util.Collections.emptyList() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(keywords));
        this.type = type;
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
    }
    
    /**
     * Check if this item is a weapon.
     */
    public boolean isWeapon() {
        return "weapon".equalsIgnoreCase(type);
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
}
