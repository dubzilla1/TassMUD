package com.example.tassmud.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Small utility to wipe known log files at startup so each run begins fresh.
 */
public class LogFileCleaner {
    private static final Logger logger = LoggerFactory.getLogger(LogFileCleaner.class);

    /** Delete common log files in ./logs directory. */
    public static void cleanLogs() {
        try {
            File logsDir = new File("./logs");
            if (!logsDir.exists() || !logsDir.isDirectory()) {
                logger.debug("LogFileCleaner: no logs directory found at {}", logsDir.getAbsolutePath());
                return;
            }
            File[] files = logsDir.listFiles();
            if (files == null) return;
            int deleted = 0;
            for (File f : files) {
                // only delete regular files (avoid directories)
                if (!f.isFile()) continue;
                String name = f.getName().toLowerCase();
                // target common logfile extensions and known filenames
                if (name.endsWith(".log") || name.endsWith(".err") || name.endsWith(".out") || name.startsWith("server") || name.startsWith("spawn")) {
                    try {
                        if (f.delete()) deleted++; else logger.debug("LogFileCleaner: failed to delete {}", f.getAbsolutePath());
                    } catch (Exception e) {
                        logger.debug("LogFileCleaner: exception deleting {}: {}", f.getAbsolutePath(), e.getMessage());
                    }
                }
            }
            logger.info("LogFileCleaner: removed {} files from {}", deleted, logsDir.getAbsolutePath());
        } catch (Throwable t) {
            logger.warn("LogFileCleaner: error while cleaning logs: {}", t.getMessage());
        }
    }
}
