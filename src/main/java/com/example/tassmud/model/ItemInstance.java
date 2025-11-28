package com.example.tassmud.model;

public class ItemInstance {
    public final long instanceId;
    public final int templateId;
    // Location union: one of these may be set (nullable)
    public final Integer locationRoomId;    // nullable: item is in a room
    public final Integer ownerCharacterId;  // nullable: item is owned/inventory of character
    public final Long containerInstanceId;  // nullable: item is inside a container item instance
    public final long createdAt;

    public ItemInstance(long instanceId, int templateId, Integer locationRoomId, Integer ownerCharacterId, Long containerInstanceId, long createdAt) {
        this.instanceId = instanceId;
        this.templateId = templateId;
        this.locationRoomId = locationRoomId;
        this.ownerCharacterId = ownerCharacterId;
        this.containerInstanceId = containerInstanceId;
        this.createdAt = createdAt;
    }
}
