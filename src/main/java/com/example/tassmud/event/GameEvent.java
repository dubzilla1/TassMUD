package com.example.tassmud.event;

/**
 * Interface for events that can be scheduled and executed.
 */
@FunctionalInterface
public interface GameEvent {
    /**
     * Execute the event.
     */
    void execute();
}
