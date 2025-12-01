package com.example.tassmud.net;

import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.event.EventScheduler;
import com.example.tassmud.event.SpawnManager;
import com.example.tassmud.persistence.*;
import com.example.tassmud.util.*;
import com.example.tassmud.util.CooldownManager;
import com.example.tassmud.util.RegenerationService;
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
    private static final int DEFAULT_PORT = 4003;
    private static final int PORT;

    static {
        int portVal = DEFAULT_PORT;
        try {
            String env = System.getenv("TASSMUD_PORT");
            if (env != null && !env.isEmpty()) {
                portVal = Integer.parseInt(env);
            } else {
                String prop = System.getProperty("tassmud.port");
                if (prop != null && !prop.isEmpty()) {
                    portVal = Integer.parseInt(prop);
                }
            }
        } catch (Exception e) {
            // fallback to default
            portVal = DEFAULT_PORT;
        }
        PORT = portVal;
    }
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
        
        // Set Tass's class to Ranger (id=5) at level 1 if not already set
        CharacterClassDAO classDao = new CharacterClassDAO();
        try {
            classDao.loadClassesFromYamlResource("/data/classes.yaml");
        } catch (Exception e) {
            System.err.println("[startup] Warning: Could not load class data: " + e.getMessage());
        }
        Integer tassId = dao.getCharacterIdByName("Tass");
        if (tassId != null) {
            // Check if Tass already has a class assigned
            Integer currentClassId = classDao.getCharacterCurrentClassId(tassId);
            if (currentClassId == null) {
                // Assign Ranger (id=5) as Tass's class
                int rangerClassId = 5;
                classDao.setCharacterCurrentClass(tassId, rangerClassId);
                dao.updateCharacterClass(tassId, rangerClassId);
                System.out.println("[startup] Assigned Tass the Ranger class (level 1)");
            }
        }

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

        // Initialize cooldown system
        CooldownManager cooldownManager = CooldownManager.getInstance();
        cooldownManager.initialize(tickService);
        
        // Initialize combat system
        CombatManager combatManager = CombatManager.getInstance();
        combatManager.initialize(tickService);
        // Set up message callbacks for combat
        combatManager.setRoomMessageCallback((roomId, message) -> {
            // Don't add [COMBAT] prefix to blank separator lines
            if (message.isEmpty()) {
                ClientHandler.broadcastRoomMessage(roomId, "");
            } else {
                ClientHandler.broadcastRoomMessage(roomId, "[COMBAT] " + message);
                // When combat ends, send prompts to all players in the room
                if (message.contains("Combat has ended")) {
                    ClientHandler.sendPromptsToRoom(roomId);
                }
            }
        });
        combatManager.setPlayerMessageCallback((charId, message) -> {
            ClientHandler.sendToCharacter(charId, message);
        });
        combatManager.setPlayerPromptCallback((charId) -> {
            ClientHandler.sendPromptToCharacter(charId);
        });

        // Initialize event scheduler for spawn system
        EventScheduler eventScheduler = EventScheduler.getInstance();
        eventScheduler.initialize(tickService);
        
        // Start all registered spawns (spawns were registered during DataLoader.loadDefaults)
        SpawnManager spawnManager = SpawnManager.getInstance();
        if (spawnManager.getSpawnCount() > 0) {
            // Trigger initial spawns to populate the world
            spawnManager.triggerInitialSpawns();
            // Schedule recurring spawns
            spawnManager.scheduleAllSpawns();
        }
        
        // Initialize regeneration service for HP/MP/MV recovery
        RegenerationService regenService = RegenerationService.getInstance();
        regenService.initialize(tickService);

        // Ensure the tick service and thread pool are stopped on JVM shutdown
        final GameClock gameClockRef = gameClock;
        final CombatManager combatManagerRef = combatManager;
        final EventScheduler eventSchedulerRef = eventScheduler;
        final RegenerationService regenServiceRef = regenService;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutdown hook: stopping combat, clock, event scheduler, regen service, tick service and thread pool...");
            try { regenServiceRef.shutdown(); } catch (Exception ignored) {}
            try { eventSchedulerRef.shutdown(); } catch (Exception ignored) {}
            try { combatManagerRef.shutdown(); } catch (Exception ignored) {}
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
