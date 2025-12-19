package com.example.tassmud.model;

public class Door {
    public final int fromRoomId;
    public final String direction;
    public final Integer toRoomId;
    public final String state; // e.g., OPEN, CLOSED, LOCKED
    public final boolean locked;
    public final boolean hidden;
    public final boolean blocked;
    public final Integer keyItemId;

    public Door(int fromRoomId, String direction, Integer toRoomId, String state, boolean locked, boolean hidden, boolean blocked, Integer keyItemId) {
        this.fromRoomId = fromRoomId;
        this.direction = direction == null ? "" : direction.toLowerCase();
        this.toRoomId = toRoomId;
        this.state = state == null ? "OPEN" : state;
        this.locked = locked;
        this.hidden = hidden;
        this.blocked = blocked;
        this.keyItemId = keyItemId;
    }

    public boolean isOpen() {
        return "OPEN".equalsIgnoreCase(state);
    }

    public boolean isClosed() {
        return "CLOSED".equalsIgnoreCase(state);
    }

    public boolean isLocked() {
        return locked || "LOCKED".equalsIgnoreCase(state);
    }
}
