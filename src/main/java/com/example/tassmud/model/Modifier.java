package com.example.tassmud.model;

import java.time.Instant;
import java.util.UUID;

public record Modifier(
    UUID id,
    String source,
    Stat stat,
    Op op,
    double value,
    long expiresAtMillis,
    int priority
) {
    public Modifier(String source, Stat stat, Op op, double value, long expiresAtMillis, int priority) {
        this(UUID.randomUUID(), source, stat, op, value, expiresAtMillis, priority);
    }

    public boolean isExpired() {
        return expiresAtMillis > 0 && Instant.now().toEpochMilli() >= expiresAtMillis;
    }

    public enum Op { ADD, MULTIPLY, OVERRIDE }
}
