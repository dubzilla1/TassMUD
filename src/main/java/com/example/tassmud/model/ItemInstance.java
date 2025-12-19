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
    
    // Magic effect overrides (null = use template value)
    public final String spellEffect1Override;   // spell effect 1 id
    public final String spellEffect2Override;   // spell effect 2 id
    public final String spellEffect3Override;   // spell effect 3 id
    public final String spellEffect4Override;   // spell effect 4 id
    
    // Calculated item value (for generated items)
    public final Integer valueOverride;         // gold value override
    
    // Generation flag - true if this item was dynamically generated
    public final boolean isGenerated;
    
    // Uses remaining for usable items (-1 = unlimited, null = use template default)
    public final Integer usesRemaining;

    public ItemInstance(long instanceId, int templateId, Integer locationRoomId, Integer ownerCharacterId, Long containerInstanceId, long createdAt) {
        this(instanceId, templateId, locationRoomId, ownerCharacterId, containerInstanceId, createdAt, null, null, 1);
    }

    public ItemInstance(long instanceId, int templateId, Integer locationRoomId, Integer ownerCharacterId, Long containerInstanceId, long createdAt, String customName, String customDescription) {
        this(instanceId, templateId, locationRoomId, ownerCharacterId, containerInstanceId, createdAt, customName, customDescription, 1);
    }
    
    public ItemInstance(long instanceId, int templateId, Integer locationRoomId, Integer ownerCharacterId, Long containerInstanceId, long createdAt, String customName, String customDescription, int itemLevel) {
        this(instanceId, templateId, locationRoomId, ownerCharacterId, containerInstanceId, createdAt, customName, customDescription, itemLevel,
             null, null, null, null, null, null, null, null, null, null, null, null, false, null);
    }
    
    /**
     * Full constructor with all stat overrides for dynamically generated items.
     */
    public ItemInstance(long instanceId, int templateId, Integer locationRoomId, Integer ownerCharacterId, 
                       Long containerInstanceId, long createdAt, String customName, String customDescription, int itemLevel,
                       Integer baseDieOverride, Integer multiplierOverride, Double abilityMultOverride,
                       Integer armorSaveOverride, Integer fortSaveOverride, Integer refSaveOverride, Integer willSaveOverride,
                       String spellEffect1Override, String spellEffect2Override, String spellEffect3Override, String spellEffect4Override,
                       Integer valueOverride, boolean isGenerated) {
        this(instanceId, templateId, locationRoomId, ownerCharacterId, containerInstanceId, createdAt, 
             customName, customDescription, itemLevel,
             baseDieOverride, multiplierOverride, abilityMultOverride,
             armorSaveOverride, fortSaveOverride, refSaveOverride, willSaveOverride,
             spellEffect1Override, spellEffect2Override, spellEffect3Override, spellEffect4Override,
             valueOverride, isGenerated, null);
    }
    
    /**
     * Full constructor with all stat overrides and uses remaining.
     */
    public ItemInstance(long instanceId, int templateId, Integer locationRoomId, Integer ownerCharacterId, 
                       Long containerInstanceId, long createdAt, String customName, String customDescription, int itemLevel,
                       Integer baseDieOverride, Integer multiplierOverride, Double abilityMultOverride,
                       Integer armorSaveOverride, Integer fortSaveOverride, Integer refSaveOverride, Integer willSaveOverride,
                       String spellEffect1Override, String spellEffect2Override, String spellEffect3Override, String spellEffect4Override,
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
        this.spellEffect1Override = spellEffect1Override;
        this.spellEffect2Override = spellEffect2Override;
        this.spellEffect3Override = spellEffect3Override;
        this.spellEffect4Override = spellEffect4Override;
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
    
    public String getEffectiveSpellEffect1(ItemTemplate template) {
        if (spellEffect1Override != null) return spellEffect1Override;
        return template != null ? template.spellEffectId1 : null;
    }
    
    public String getEffectiveSpellEffect2(ItemTemplate template) {
        if (spellEffect2Override != null) return spellEffect2Override;
        return template != null ? template.spellEffectId2 : null;
    }
    
    public String getEffectiveSpellEffect3(ItemTemplate template) {
        if (spellEffect3Override != null) return spellEffect3Override;
        return template != null ? template.spellEffectId3 : null;
    }
    
    public String getEffectiveSpellEffect4(ItemTemplate template) {
        if (spellEffect4Override != null) return spellEffect4Override;
        return template != null ? template.spellEffectId4 : null;
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
}
