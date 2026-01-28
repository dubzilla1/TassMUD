package com.example.tassmud.util;

import com.example.tassmud.persistence.CharacterDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-game calendar and clock.
 * Calendar: 10 months, 30 days per month. 24h/day, 60m/hour.
 * Speed: 1 in-game minute per real second.
 * Persist the current date to settings table on day rollover.
 */
public class GameClock {
    private static final Logger logger = LoggerFactory.getLogger(GameClock.class);
    private final CharacterDAO dao;
    private long year;
    private int month; // 1-10
    private int day;   // 1-30
    private int hour;  // 0-23
    private int minute; // 0-59

    private volatile boolean running = false;

    public GameClock(TickService tickService, CharacterDAO dao) {
        this.dao = dao;
        loadOrInit();
        // schedule tick every 1000ms (1 real second == 1 in-game minute)
        tickService.scheduleAtFixedRate("game-clock", this::tick, 1000, 1000);
        this.running = true;
    }

    private void loadOrInit() {
        String v = dao.getSetting("game.date");
        if (v == null) {
            // initialize to year 0, month 1, day 1, hour 0, minute 0
            this.year = 0;
            this.month = 1;
            this.day = 1;
            this.hour = 0;
            this.minute = 0;
            persist();
            return;
        }
        // expected format: year:month:day:hour:minute
        try {
            String[] parts = v.split(":");
            this.year = Long.parseLong(parts[0]);
            this.month = Integer.parseInt(parts[1]);
            this.day = Integer.parseInt(parts[2]);
            this.hour = Integer.parseInt(parts[3]);
            this.minute = Integer.parseInt(parts[4]);
        } catch (Exception e) {
            // fallback to defaults
            this.year = 0; this.month = 1; this.day = 1; this.hour = 0; this.minute = 0;
            persist();
        }
    }

    private void persist() {
        String s = "%d:%d:%d:%d:%d".formatted(year, month, day, hour, minute);
        dao.setSetting("game.date", s);
    }

    private void tick() {
        if (!running) return;
        // advance one in-game minute
        minute += 1;
        if (minute >= 60) {
            minute = 0;
            hour += 1;
            if (hour >= 24) {
                hour = 0;
                day += 1;
                // Persist on new day
                if (day > 30) {
                    day = 1;
                    month += 1;
                    if (month > 10) {
                        month = 1;
                        year += 1;
                    }
                }
                // Persist the new date when the day increments
                persist();
                logger.info("[game-clock] New in-game day: {}/{}/{} 00:00", year, month, day);
            }
        }
    }

    public String getCurrentDateString() {
        return "%d:%02d:%02d:%02d:%02d".formatted(year, month, day, hour, minute);
    }

    public void shutdown() {
        this.running = false;
    }
}
