package com.example.tassmud.effect;

import java.util.Map;
import java.util.UUID;

public class EffectInstance {
    private final UUID id;
    private final String defId;
    private final Integer casterId;
    private final Integer targetId;
    private final Map<String,String> params;
    private final long startAtMs;
    private final long expiresAtMs; // 0 means instant/no expiry
    private final int priority;

    public EffectInstance(UUID id, String defId, Integer casterId, Integer targetId,
                          Map<String,String> params, long startAtMs, long expiresAtMs, int priority) {
        this.id = id;
        this.defId = defId;
        this.casterId = casterId;
        this.targetId = targetId;
        this.params = params;
        this.startAtMs = startAtMs;
        this.expiresAtMs = expiresAtMs;
        this.priority = priority;
    }

    public UUID getId() { return id; }
    public String getDefId() { return defId; }
    public Integer getCasterId() { return casterId; }
    public Integer getTargetId() { return targetId; }
    public Map<String,String> getParams() { return params; }
    public long getStartAtMs() { return startAtMs; }
    public long getExpiresAtMs() { return expiresAtMs; }
    public int getPriority() { return priority; }

    public boolean isExpired(long nowMs) {
        return expiresAtMs > 0 && nowMs >= expiresAtMs;
    }
}
