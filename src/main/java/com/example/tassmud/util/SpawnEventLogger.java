package com.example.tassmud.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logger for SpawnEvent messages. Logback routes this logger exclusively
 * to {@code logs/spawn.log} (see logback.xml), keeping spawn noise out of
 * server.log and server.err.
 */
public class SpawnEventLogger {

    private static final Logger logger = LoggerFactory.getLogger(SpawnEventLogger.class);

    public static void info(String msg) {
        logger.info(msg);
    }

    public static void error(String msg) {
        logger.error(msg);
    }
}
