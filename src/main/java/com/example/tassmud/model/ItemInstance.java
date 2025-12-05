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

    public ItemInstance(long instanceId, int templateId, Integer locationRoomId, Integer ownerCharacterId, Long containerInstanceId, long createdAt) {
        this(instanceId, templateId, locationRoomId, ownerCharacterId, containerInstanceId, createdAt, null, null, 1);
    }

    public ItemInstance(long instanceId, int templateId, Integer locationRoomId, Integer ownerCharacterId, Long containerInstanceId, long createdAt, String customName, String customDescription) {
        this(instanceId, templateId, locationRoomId, ownerCharacterId, containerInstanceId, createdAt, customName, customDescription, 1);
    }
    
    public ItemInstance(long instanceId, int templateId, Integer locationRoomId, Integer ownerCharacterId, Long containerInstanceId, long createdAt, String customName, String customDescription, int itemLevel) {
        this.instanceId = instanceId;
        this.templateId = templateId;
        this.locationRoomId = locationRoomId;
        this.ownerCharacterId = ownerCharacterId;
        this.containerInstanceId = containerInstanceId;
        this.createdAt = createdAt;
        this.customName = customName;
        this.customDescription = customDescription;
        this.itemLevel = itemLevel > 0 ? itemLevel : 1;
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
}
