package com.example.tassmud.util;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Lightweight logger for SpawnEvent messages. Appends messages to
 * `logs/SpawnEventLog.out` and `logs/SpawnEventLog.err` and mirrors to stdout/stderr.
 */
public class SpawnEventLogger {

    private static final File OUT_FILE = new File("logs/SpawnEventLog.out");
    private static final File ERR_FILE = new File("logs/SpawnEventLog.err");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static synchronized void append(File file, String line) {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            try (FileWriter fw = new FileWriter(file, true)) {
                fw.write(line);
                fw.write(System.lineSeparator());
            }
        } catch (IOException e) {
            // As a last resort, print to stderr
            System.err.println("SpawnEventLogger failed to write to " + file.getPath() + ": " + e.getMessage());
        }
    }

    public static void info(String msg) {
        String line = String.format("%s %s", LocalDateTime.now().format(TS), msg);
        append(OUT_FILE, line);
        System.out.println(line);
    }

    public static void error(String msg) {
        String line = String.format("%s %s", LocalDateTime.now().format(TS), msg);
        append(ERR_FILE, line);
        System.err.println(line);
    }
}
