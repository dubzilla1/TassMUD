package com.example.tassmud.event;

import java.util.concurrent.*;
import java.util.*;

/**
 * Event scheduler that manages timed events without overloading the tick system.
 * Uses a priority queue to process events in order of their scheduled time.
 * Events are processed in batches during each scheduler tick.
 */
public class EventScheduler {
    
    private static EventScheduler instance;
    
    /** Priority queue ordered by scheduled execution time */
    private final PriorityBlockingQueue<ScheduledEvent> eventQueue;
    
    /** Registered recurring events by ID */
    private final Map<String, RecurringEventConfig> recurringEvents;
    
    /** Whether the scheduler is running */
    private volatile boolean running = false;
    
    /** Lock for thread safety */
    private final Object lock = new Object();
    
    /** Maximum events to process per tick to prevent overload */
    private static final int MAX_EVENTS_PER_TICK = 50;
    
    /** Real-time milliseconds per in-game hour (1 minute = 60000ms) */
    public static final long MS_PER_GAME_HOUR = 60_000L;
    
    /** Maximum frequency cap (24 in-game hours = 24 real minutes) */
    public static final int MAX_FREQUENCY_HOURS = 24;
    
    private EventScheduler() {
        this.eventQueue = new PriorityBlockingQueue<>();
        this.recurringEvents = new ConcurrentHashMap<>();
    }
    
    public static synchronized EventScheduler getInstance() {
        if (instance == null) {
            instance = new EventScheduler();
        }
        return instance;
    }
    
    /**
     * Initialize the scheduler with the tick service.
     * Runs event processing every second.
     */
    public void initialize(com.example.tassmud.util.TickService tickService) {
        if (running) return;
        running = true;
        
        // Process events every second
        tickService.scheduleAtFixedRate("event-scheduler", this::processEvents, 1000, 1000);
        System.out.println("[EventScheduler] Initialized");
    }
    
    /**
     * Schedule a one-time event.
     * @param event The event to schedule
     * @param delayMs Delay in milliseconds from now
     */
    public void scheduleOnce(GameEvent event, long delayMs) {
        long executeAt = System.currentTimeMillis() + delayMs;
        eventQueue.offer(new ScheduledEvent(event, executeAt, null));
    }
    
    /**
     * Schedule a recurring event.
     * @param id Unique ID for this recurring event (for cancellation)
     * @param event The event to execute
     * @param initialDelayMs Initial delay before first execution
     * @param periodMs Period between executions
     */
    public void scheduleRecurring(String id, GameEvent event, long initialDelayMs, long periodMs) {
        RecurringEventConfig config = new RecurringEventConfig(id, event, periodMs);
        recurringEvents.put(id, config);
        
        long executeAt = System.currentTimeMillis() + initialDelayMs;
        eventQueue.offer(new ScheduledEvent(event, executeAt, id));
    }
    
    /**
     * Cancel a recurring event.
     * @param id The ID of the recurring event to cancel
     * @return true if the event was found and cancelled
     */
    public boolean cancelRecurring(String id) {
        return recurringEvents.remove(id) != null;
    }
    
    /**
     * Convert in-game hours to real milliseconds.
     * Caps at MAX_FREQUENCY_HOURS (24 hours = 24 minutes real time).
     */
    public static long gameHoursToMs(int gameHours) {
        int capped = Math.min(gameHours, MAX_FREQUENCY_HOURS);
        return capped * MS_PER_GAME_HOUR;
    }
    
    /**
     * Process pending events. Called by the tick service.
     */
    private void processEvents() {
        if (!running) return;
        
        long now = System.currentTimeMillis();
        int processed = 0;
        
        while (processed < MAX_EVENTS_PER_TICK) {
            ScheduledEvent scheduled = eventQueue.peek();
            if (scheduled == null || scheduled.executeAt > now) {
                break; // No more events ready
            }
            
            // Remove and process
            scheduled = eventQueue.poll();
            if (scheduled == null) break;
            
            try {
                scheduled.event.execute();
            } catch (Exception e) {
                System.err.println("[EventScheduler] Error executing event: " + e.getMessage());
                e.printStackTrace();
            }
            
            // Re-schedule if recurring
            if (scheduled.recurringId != null) {
                RecurringEventConfig config = recurringEvents.get(scheduled.recurringId);
                if (config != null) {
                    long nextExecute = now + config.periodMs;
                    eventQueue.offer(new ScheduledEvent(config.event, nextExecute, scheduled.recurringId));
                }
            }
            
            processed++;
        }
    }
    
    /**
     * Shutdown the scheduler.
     */
    public void shutdown() {
        running = false;
        eventQueue.clear();
        recurringEvents.clear();
        System.out.println("[EventScheduler] Shutdown");
    }
    
    /**
     * Get the number of pending events.
     */
    public int getPendingEventCount() {
        return eventQueue.size();
    }
    
    /**
     * Get the number of registered recurring events.
     */
    public int getRecurringEventCount() {
        return recurringEvents.size();
    }
    
    // ===== Inner Classes =====
    
    /**
     * A scheduled event in the queue.
     */
    private static class ScheduledEvent implements Comparable<ScheduledEvent> {
        final GameEvent event;
        final long executeAt;
        final String recurringId; // null for one-time events
        
        ScheduledEvent(GameEvent event, long executeAt, String recurringId) {
            this.event = event;
            this.executeAt = executeAt;
            this.recurringId = recurringId;
        }
        
        @Override
        public int compareTo(ScheduledEvent other) {
            return Long.compare(this.executeAt, other.executeAt);
        }
    }
    
    /**
     * Configuration for a recurring event.
     */
    private static class RecurringEventConfig {
        final String id;
        final GameEvent event;
        final long periodMs;
        
        RecurringEventConfig(String id, GameEvent event, long periodMs) {
            this.id = id;
            this.event = event;
            this.periodMs = periodMs;
        }
    }
}
