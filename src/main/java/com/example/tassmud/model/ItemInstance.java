package com.example.tassmud.model;

public class ItemInstance {
    public final long instanceId;
    public final int templateId;
    // Location union: one of these may be set (nullable)
    public final Integer locationRoomId;    // nullable: item is in a room
    public final Integer ownerCharacterId;  // nullable: item is owned/inventory of character
    public final Long containerInstanceId;  // nullable: item is inside a container item instance
    public final long createdAt;
    // Custom name/description for dynamically-named items (like corpses)
    public final String customName;         // nullable: overrides template name if set
    public final String customDescription;  // nullable: overrides template description if set
    // Runtime item level - rolled at spawn time from template's min/max range
    public final int itemLevel;             // default 1 for items without level range
    
    // === STAT OVERRIDES for dynamically generated loot ===
    // Weapon stat overrides (null = use template value)
    public final Integer baseDieOverride;       // die size: 2,4,6,8,10,12,20,30
    public final Integer multiplierOverride;    // weapon damage multiplier
    public final Double abilityMultOverride;    // ability score multiplier (1.0 - 10.0)
    
    // Armor/save stat overrides (null = use template value)
    public final Integer armorSaveOverride;     // armor save bonus
    public final Integer fortSaveOverride;      // fortitude save bonus
    public final Integer refSaveOverride;       // reflex save bonus
    public final Integer willSaveOverride;      // will save bonus
    
    // Magic effect overrides (empty = use template values)
    /** Spell effect overrides for generated items. Empty list means use template values. */
    public final java.util.List<String> spellEffectOverrides;
    
    // Calculated item value (for generated items)
    public final Integer valueOverride;         // gold value override
    
    // Generation flag - true if this item was dynamically generated
    public final boolean isGenerated;
    
    // Uses remaining for usable items (-1 = unlimited, null = use template default)
    public final Integer usesRemaining;

    /** Creates a new builder for constructing ItemInstance instances. */
    public static Builder builder() { return new Builder(); }

    /**
     * Canonical constructor — prefer using {@link #builder()} instead of calling directly.
     */
    ItemInstance(long instanceId, int templateId, Integer locationRoomId, Integer ownerCharacterId, 
                       Long containerInstanceId, long createdAt, String customName, String customDescription, int itemLevel,
                       Integer baseDieOverride, Integer multiplierOverride, Double abilityMultOverride,
                       Integer armorSaveOverride, Integer fortSaveOverride, Integer refSaveOverride, Integer willSaveOverride,
                       java.util.List<String> spellEffectOverrides,
                       Integer valueOverride, boolean isGenerated, Integer usesRemaining) {
        this.instanceId = instanceId;
        this.templateId = templateId;
        this.locationRoomId = locationRoomId;
        this.ownerCharacterId = ownerCharacterId;
        this.containerInstanceId = containerInstanceId;
        this.createdAt = createdAt;
        this.customName = customName;
        this.customDescription = customDescription;
        this.itemLevel = itemLevel > 0 ? itemLevel : 1;
        
        // Stat overrides
        this.baseDieOverride = baseDieOverride;
        this.multiplierOverride = multiplierOverride;
        this.abilityMultOverride = abilityMultOverride;
        this.armorSaveOverride = armorSaveOverride;
        this.fortSaveOverride = fortSaveOverride;
        this.refSaveOverride = refSaveOverride;
        this.willSaveOverride = willSaveOverride;
        this.spellEffectOverrides = spellEffectOverrides == null ? java.util.Collections.emptyList() : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(spellEffectOverrides));
        this.valueOverride = valueOverride;
        this.isGenerated = isGenerated;
        this.usesRemaining = usesRemaining;
    }
    
    // === Effective stat getters (use override if set, otherwise template) ===
    
    public int getEffectiveBaseDie(ItemTemplate template) {
        if (baseDieOverride != null) return baseDieOverride;
        return template != null ? template.baseDie : 0;
    }
    
    public int getEffectiveMultiplier(ItemTemplate template) {
        if (multiplierOverride != null) return multiplierOverride;
        return template != null ? template.multiplier : 1;
    }
    
    public double getEffectiveAbilityMultiplier(ItemTemplate template) {
        if (abilityMultOverride != null) return abilityMultOverride;
        return template != null ? template.abilityMultiplier : 1.0;
    }
    
    public int getEffectiveArmorSave(ItemTemplate template) {
        if (armorSaveOverride != null) return armorSaveOverride;
        return template != null ? template.armorSaveBonus : 0;
    }
    
    public int getEffectiveFortSave(ItemTemplate template) {
        if (fortSaveOverride != null) return fortSaveOverride;
        return template != null ? template.fortSaveBonus : 0;
    }
    
    public int getEffectiveRefSave(ItemTemplate template) {
        if (refSaveOverride != null) return refSaveOverride;
        return template != null ? template.refSaveBonus : 0;
    }
    
    public int getEffectiveWillSave(ItemTemplate template) {
        if (willSaveOverride != null) return willSaveOverride;
        return template != null ? template.willSaveBonus : 0;
    }
    
    /**
     * Get effective spell effects, merging overrides with template.
     * Returns overrides if present, otherwise template effects.
     */
    public java.util.List<String> getEffectiveSpellEffects(ItemTemplate template) {
        if (spellEffectOverrides != null && !spellEffectOverrides.isEmpty()) return spellEffectOverrides;
        return template != null ? template.spellEffectIds : java.util.Collections.emptyList();
    }
    
    public int getEffectiveValue(ItemTemplate template) {
        if (valueOverride != null) return valueOverride;
        return template != null ? template.value : 0;
    }

    /**
     * Get the effective name (custom if set, otherwise from template).
     * For level-scaled items, appends "[Lv.X]" to the name.
     */
    public String getEffectiveName(ItemTemplate template) {
        String baseName;
        if (customName != null && !customName.isEmpty()) {
            baseName = customName;
        } else {
            baseName = template != null ? template.name : "unknown item";
        }
        // For level-scaled items (max > min), show the level
        if (template != null && template.maxItemLevel > template.minItemLevel) {
            return baseName + " [Lv." + itemLevel + "]";
        }
        return baseName;
    }

    /**
     * Get the effective description (custom if set, otherwise from template).
     */
    public String getEffectiveDescription(ItemTemplate template) {
        if (customDescription != null && !customDescription.isEmpty()) {
            return customDescription;
        }
        return template != null ? template.description : "You see nothing special.";
    }
    
    /**
     * Get the effective uses remaining (instance override or template default).
     * Returns -1 for unlimited uses, 0+ for limited charges.
     * Returns 0 if item has no on-use spells.
     * 
     * @param template The item template (for default uses)
     * @return The number of uses remaining (-1 = unlimited, 0 = depleted/not usable)
     */
    public int getEffectiveUsesRemaining(ItemTemplate template) {
        // If instance has override, use it
        if (usesRemaining != null) {
            return usesRemaining;
        }
        // Otherwise use template default
        if (template != null && template.isUsable()) {
            return template.uses;
        }
        return 0;
    }
    
    /**
     * Check if this item can still be used (has uses remaining or unlimited).
     * 
     * @param template The item template
     * @return true if the item can be used
     */
    public boolean canUse(ItemTemplate template) {
        if (template == null || !template.isUsable()) {
            return false;
        }
        int uses = getEffectiveUsesRemaining(template);
        return uses == -1 || uses > 0; // -1 = unlimited, >0 = has charges
    }

    /** Fluent builder for {@link ItemInstance}. */
    public static class Builder {
        private long instanceId;
        private int templateId;
        private Integer locationRoomId;
        private Integer ownerCharacterId;
        private Long containerInstanceId;
        private long createdAt;
        private String customName;
        private String customDescription;
        private int itemLevel = 1;
        private Integer baseDieOverride;
        private Integer multiplierOverride;
        private Double abilityMultOverride;
        private Integer armorSaveOverride;
        private Integer fortSaveOverride;
        private Integer refSaveOverride;
        private Integer willSaveOverride;
        private java.util.List<String> spellEffectOverrides;
        private Integer valueOverride;
        private boolean isGenerated;
        private Integer usesRemaining;

        private Builder() {}

        public Builder instanceId(long v) { this.instanceId = v; return this; }
        public Builder templateId(int v) { this.templateId = v; return this; }
        public Builder locationRoomId(Integer v) { this.locationRoomId = v; return this; }
        public Builder ownerCharacterId(Integer v) { this.ownerCharacterId = v; return this; }
        public Builder containerInstanceId(Long v) { this.containerInstanceId = v; return this; }
        public Builder createdAt(long v) { this.createdAt = v; return this; }
        public Builder customName(String v) { this.customName = v; return this; }
        public Builder customDescription(String v) { this.customDescription = v; return this; }
        public Builder itemLevel(int v) { this.itemLevel = v; return this; }
        public Builder baseDieOverride(Integer v) { this.baseDieOverride = v; return this; }
        public Builder multiplierOverride(Integer v) { this.multiplierOverride = v; return this; }
        public Builder abilityMultOverride(Double v) { this.abilityMultOverride = v; return this; }
        public Builder armorSaveOverride(Integer v) { this.armorSaveOverride = v; return this; }
        public Builder fortSaveOverride(Integer v) { this.fortSaveOverride = v; return this; }
        public Builder refSaveOverride(Integer v) { this.refSaveOverride = v; return this; }
        public Builder willSaveOverride(Integer v) { this.willSaveOverride = v; return this; }
        public Builder spellEffectOverrides(java.util.List<String> v) { this.spellEffectOverrides = v; return this; }
        public Builder valueOverride(Integer v) { this.valueOverride = v; return this; }
        public Builder isGenerated(boolean v) { this.isGenerated = v; return this; }
        public Builder usesRemaining(Integer v) { this.usesRemaining = v; return this; }

        public ItemInstance build() {
            return new ItemInstance(instanceId, templateId, locationRoomId, ownerCharacterId,
                containerInstanceId, createdAt, customName, customDescription, itemLevel,
                baseDieOverride, multiplierOverride, abilityMultOverride,
                armorSaveOverride, fortSaveOverride, refSaveOverride, willSaveOverride,
                spellEffectOverrides, valueOverride, isGenerated, usesRemaining);
        }
    }
}
