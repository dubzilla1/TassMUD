package com.example.tassmud.net;

import com.example.tassmud.persistence.*;
import com.example.tassmud.util.*;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal telnet MUD server starter.
 * Listens on port 4000 and spawns a ClientHandler per connection.
 */
public class Server {
    private static final int PORT = 4000;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public void start() throws IOException {
        // Ensure database table(s) exist before accepting players
        CharacterDAO dao = new CharacterDAO();
        dao.ensureTable();
        // Load default data from resources (idempotent)
        DataLoader.loadDefaults(dao);
        // Ensure initial GM flag for 'Tass' (if the character exists)
        boolean gmSet = dao.setCharacterFlagByName("Tass", "is_gm", "true");
        if (gmSet) System.out.println("[startup] is_gm flag set for 'Tass'");

        // At startup, attempt to ping the database so we know it's reachable
        pingDatabase();

        // Start tick service for world updates
        TickService tickService = new TickService();
        // sample world tick: harmless heartbeat that touches the DB to keep it warm
        tickService.scheduleAtFixedRate("world-heartbeat", () -> {
            try {
                int anyRoom = dao.getAnyRoomId();
                System.out.println("[tick] world heartbeat, anyRoom=" + anyRoom + " ts=" + System.currentTimeMillis());
            } catch (Throwable t) {
                System.err.println("[tick] world heartbeat error: " + t.getMessage());
            }
        }, 0, 3000);

        // Start the in-game clock that persists the date on day rollover
        GameClock gameClock = new GameClock(tickService, dao);

        // Ensure the tick service and thread pool are stopped on JVM shutdown
        final GameClock gameClockRef = gameClock;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown hook: stopping clock, tick service and thread pool...");
            try { gameClockRef.shutdown(); } catch (Exception ignored) {}
            try { tickService.shutdown(); } catch (Exception ignored) {}
            try { pool.shutdownNow(); } catch (Exception ignored) {}
        }));

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("TassMUD server listening on port " + PORT);
            while (true) {
                Socket client = serverSocket.accept();
                System.out.println("Accepted connection from " + client.getRemoteSocketAddress());
                pool.submit(new ClientHandler(client, gameClock));
            }
        }
    }

    private void pingDatabase() {
        // Use an in-memory H2 database for now; keeps DB "spun up" inside the JVM.
        String url = "jdbc:h2:mem:tassmud;DB_CLOSE_DELAY=-1";
        String user = "sa";
        String pass = "";
        System.out.println("Pinging database: " + url);
        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            if (c != null && !c.isClosed()) {
                System.out.println("Database ping successful (H2 in-memory).");
            } else {
                System.err.println("Database connection returned closed/invalid connection.");
            }
        } catch (SQLException e) {
            System.err.println("Database ping failed: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        try {
            new Server().start();
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("Failed to start server: " + e.getMessage());
        }
    }
}
