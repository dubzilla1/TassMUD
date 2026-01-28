package com.example.tassmud.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Generates ASCII art maps for areas in TassMUD.
 * 
 * Usage: java -cp target/tass-mud-0.1.0-shaded.jar com.example.tassmud.tools.MapGenerator [areaId]
 * 
 * If no areaId is provided, generates maps for all areas.
 * Maps are saved to the maps/ directory.
 */
public class MapGenerator {
    private static final Logger logger = LoggerFactory.getLogger(MapGenerator.class);
    
    private static final String URL = "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
    private static final String USER = "sa";
    private static final String PASS = "";
    
    // Room cell dimensions (for the ASCII grid)
    private static final int CELL_WIDTH = 7;   // Width of a room cell including borders
    private static final int CELL_HEIGHT = 3;  // Height of a room cell including borders
    
    public static void main(String[] args) {
        MapGenerator gen = new MapGenerator();
        
        if (args.length > 0) {
            try {
                int areaId = Integer.parseInt(args[0]);
                gen.generateMapForArea(areaId);
            } catch (NumberFormatException e) {
                logger.error("Invalid area ID: {}", args[0]);
                System.exit(1);
            }
        } else {
            gen.generateAllMaps();
        }
    }
    
    /**
     * Generate a map for a specific area and return as string (for in-game use).
     * This uses the CharacterDAO which shares the server's DB connection.
     */
    public static String generateMapForAreaInGame(int areaId) {
        MapGenerator gen = new MapGenerator();
        
        // Use CharacterDAO for DB access (shares server's connection)
        com.example.tassmud.persistence.CharacterDAO charDao = new com.example.tassmud.persistence.CharacterDAO();
        com.example.tassmud.model.Area areaModel = charDao.getAreaById(areaId);
        
        if (areaModel == null) {
            return "Area " + areaId + " not found.";
        }
        
        // Get all rooms for this area
        List<Room> rooms = gen.getRoomsByAreaUsingCharacterDAO(areaId, charDao);
        if (rooms.isEmpty()) {
            return "Area " + areaId + " (" + areaModel.getName() + ") has no rooms.";
        }
        
        Area area = new Area(areaModel.getId(), areaModel.getName(), areaModel.getDescription());
        String map = gen.generateAsciiMap(area, rooms);
        
        // Also save to file
        try {
            java.io.File mapsDir = new java.io.File("maps");
            if (!mapsDir.exists()) mapsDir.mkdirs();
            
            String filename = "maps/area_" + areaId + "_" + gen.sanitizeFilename(area.name) + ".txt";
            try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
                pw.print(map);
            }
        } catch (IOException e) {
            // Ignore file save errors for in-game use
        }
        
        return map;
    }
    
    /**
     * Get rooms using CharacterDAO (for in-game use).
     */
    private List<Room> getRoomsByAreaUsingCharacterDAO(int areaId, com.example.tassmud.persistence.CharacterDAO charDao) {
        List<Room> rooms = new ArrayList<>();
        // We need to query all rooms for this area - CharacterDAO doesn't have this method
        // So we'll use direct SQL but through the same connection pattern
        String sql = "SELECT id, name, exit_n, exit_e, exit_s, exit_w, exit_u, exit_d FROM room WHERE area_id = ? ORDER BY id";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, areaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rooms.add(new Room(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getObject("exit_n") == null ? null : rs.getInt("exit_n"),
                        rs.getObject("exit_e") == null ? null : rs.getInt("exit_e"),
                        rs.getObject("exit_s") == null ? null : rs.getInt("exit_s"),
                        rs.getObject("exit_w") == null ? null : rs.getInt("exit_w"),
                        rs.getObject("exit_u") == null ? null : rs.getInt("exit_u"),
                        rs.getObject("exit_d") == null ? null : rs.getInt("exit_d")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to get rooms: {}", e.getMessage(), e);
        }
        return rooms;
    }
    
    /**
     * Generate maps for all areas.
     */
    public void generateAllMaps() {
        List<Area> areas = getAllAreas();
        logger.info("Found {} areas.", areas.size());
        
        for (Area area : areas) {
            generateMapForArea(area.id);
        }
    }
    
    /**
     * Generate a map for a specific area.
     */
    public void generateMapForArea(int areaId) {
        Area area = getAreaById(areaId);
        if (area == null) {
            logger.error("Area {} not found.", areaId);
            return;
        }
        
        List<Room> rooms = getRoomsByArea(areaId);
        if (rooms.isEmpty()) {
            logger.info("Area {} ({}) has no rooms.", areaId, area.name);
            return;
        }
        
        logger.info("Generating map for Area {}: {} ({} rooms)", areaId, area.name, rooms.size());
        
        String map = generateAsciiMap(area, rooms);
        
        // Save to file
        String filename = "maps/area_" + areaId + "_" + sanitizeFilename(area.name) + ".txt";
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            pw.print(map);
            logger.info("  Saved to: {}", filename);
        } catch (IOException e) {
            logger.warn("  Failed to save map: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Generate the ASCII map for an area.
     */
    private String generateAsciiMap(Area area, List<Room> rooms) {
        StringBuilder sb = new StringBuilder();
        
        // Header
        sb.append("=".repeat(60)).append("\n");
        sb.append("AREA ").append(area.id).append(": ").append(area.name).append("\n");
        if (area.description != null && !area.description.isEmpty()) {
            sb.append(area.description).append("\n");
        }
        sb.append("Rooms: ").append(rooms.size()).append("\n");
        sb.append("=".repeat(60)).append("\n\n");
        
        // Build room lookup map
        Map<Integer, Room> roomMap = new HashMap<>();
        for (Room r : rooms) {
            roomMap.put(r.id, r);
        }
        
        // Find connected components (groups of rooms connected to each other)
        List<Set<Integer>> components = findConnectedComponents(rooms, roomMap);
        
        // Generate map for each component
        int componentNum = 1;
        for (Set<Integer> component : components) {
            if (components.size() > 1) {
                sb.append("--- Section ").append(componentNum++).append(" ---\n\n");
            }
            
            List<Room> componentRooms = new ArrayList<>();
            for (Integer roomId : component) {
                componentRooms.add(roomMap.get(roomId));
            }
            
            sb.append(generateComponentMap(componentRooms, roomMap));
            sb.append("\n");
        }
        
        // Room legend
        sb.append("\n").append("-".repeat(60)).append("\n");
        sb.append("ROOM LEGEND:\n");
        sb.append("-".repeat(60)).append("\n");
        for (Room r : rooms) {
            sb.append("  %4d: %s\n".formatted(r.id, r.name));
        }
        
        return sb.toString();
    }
    
    /**
     * Generate the ASCII grid for a connected component of rooms.
     */
    private String generateComponentMap(List<Room> rooms, Map<Integer, Room> allRooms) {
        if (rooms.isEmpty()) return "";
        
        // Place rooms on a 2D grid using BFS from the first room
        Map<Integer, int[]> positions = new HashMap<>(); // roomId -> [x, y]
        
        // Start from first room at origin
        Room startRoom = rooms.getFirst();
        positions.put(startRoom.id, new int[]{0, 0});
        
        Queue<Integer> queue = new LinkedList<>();
        queue.add(startRoom.id);
        Set<Integer> visited = new HashSet<>();
        visited.add(startRoom.id);
        
        // BFS to assign positions based on exits
        while (!queue.isEmpty()) {
            int currentId = queue.poll();
            Room current = allRooms.get(currentId);
            if (current == null) continue;
            
            int[] pos = positions.get(currentId);
            
            // Process each exit
            if (current.exitN != null && !visited.contains(current.exitN) && allRooms.containsKey(current.exitN)) {
                positions.put(current.exitN, new int[]{pos[0], pos[1] - 1});
                visited.add(current.exitN);
                queue.add(current.exitN);
            }
            if (current.exitS != null && !visited.contains(current.exitS) && allRooms.containsKey(current.exitS)) {
                positions.put(current.exitS, new int[]{pos[0], pos[1] + 1});
                visited.add(current.exitS);
                queue.add(current.exitS);
            }
            if (current.exitE != null && !visited.contains(current.exitE) && allRooms.containsKey(current.exitE)) {
                positions.put(current.exitE, new int[]{pos[0] + 1, pos[1]});
                visited.add(current.exitE);
                queue.add(current.exitE);
            }
            if (current.exitW != null && !visited.contains(current.exitW) && allRooms.containsKey(current.exitW)) {
                positions.put(current.exitW, new int[]{pos[0] - 1, pos[1]});
                visited.add(current.exitW);
                queue.add(current.exitW);
            }
        }
        
        // Normalize positions to start at (0, 0)
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        
        for (int[] pos : positions.values()) {
            minX = Math.min(minX, pos[0]);
            minY = Math.min(minY, pos[1]);
            maxX = Math.max(maxX, pos[0]);
            maxY = Math.max(maxY, pos[1]);
        }
        
        // Shift all positions to be positive
        for (int[] pos : positions.values()) {
            pos[0] -= minX;
            pos[1] -= minY;
        }
        
        int gridWidth = maxX - minX + 1;
        int gridHeight = maxY - minY + 1;
        
        // Create the ASCII grid
        // Each cell is CELL_WIDTH x CELL_HEIGHT characters
        // Plus connectors between cells
        int charWidth = gridWidth * CELL_WIDTH + (gridWidth - 1) * 3;  // 3 chars for horizontal connectors
        int charHeight = gridHeight * CELL_HEIGHT + (gridHeight - 1); // 1 char for vertical connectors
        
        char[][] grid = new char[charHeight][charWidth];
        for (char[] row : grid) {
            Arrays.fill(row, ' ');
        }
        
        // Create position lookup (x,y -> roomId)
        Map<String, Integer> posToRoom = new HashMap<>();
        for (Map.Entry<Integer, int[]> entry : positions.entrySet()) {
            int[] pos = entry.getValue();
            posToRoom.put(pos[0] + "," + pos[1], entry.getKey());
        }
        
        // Draw each room
        for (Map.Entry<Integer, int[]> entry : positions.entrySet()) {
            int roomId = entry.getKey();
            int[] pos = entry.getValue();
            Room room = allRooms.get(roomId);
            
            int charX = pos[0] * (CELL_WIDTH + 3);
            int charY = pos[1] * (CELL_HEIGHT + 1);
            
            // Bounds check before drawing
            if (charX < 0 || charY < 0 || charX + CELL_WIDTH > charWidth || charY + CELL_HEIGHT > charHeight) {
                continue;
            }
            
            // Draw room box
            drawRoomBox(grid, charX, charY, roomId, room);
            
            // Draw connectors to adjacent rooms
            if (room.exitE != null && positions.containsKey(room.exitE)) {
                // Draw east connector
                int connX = charX + CELL_WIDTH;
                int connY = charY + 1;
                if (connX + 2 < charWidth && connY < charHeight) {
                    grid[connY][connX] = '-';
                    grid[connY][connX + 1] = '-';
                    grid[connY][connX + 2] = '-';
                }
            }
            
            if (room.exitS != null && positions.containsKey(room.exitS)) {
                // Draw south connector
                int connX = charX + CELL_WIDTH / 2;
                int connY = charY + CELL_HEIGHT;
                if (connX < charWidth && connY < charHeight) {
                    grid[connY][connX] = '|';
                }
            }
            
            // Handle up/down exits with markers
            if (room.exitU != null && charY >= 0 && charX + CELL_WIDTH - 2 < charWidth) {
                grid[charY][charX + CELL_WIDTH - 2] = '^';
            }
            if (room.exitD != null && charY + CELL_HEIGHT - 1 < charHeight && charX + CELL_WIDTH - 2 < charWidth) {
                grid[charY + CELL_HEIGHT - 1][charX + CELL_WIDTH - 2] = 'v';
            }
        }
        
        // Convert grid to string
        StringBuilder sb = new StringBuilder();
        for (char[] row : grid) {
            // Trim trailing spaces
            int lastNonSpace = row.length - 1;
            while (lastNonSpace >= 0 && row[lastNonSpace] == ' ') {
                lastNonSpace--;
            }
            sb.append(new String(row, 0, lastNonSpace + 1)).append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * Draw a room box at the specified position in the grid.
     * Box looks like:
     *   [1000]
     *   [    ]
     *   [____]
     */
    private void drawRoomBox(char[][] grid, int x, int y, int roomId, Room room) {
        // Format room ID to fit in box (max 4 digits displayed)
        String idStr = "%4d".formatted(roomId % 10000);
        
        // Bounds check
        if (y + 2 >= grid.length || x + 5 >= grid[0].length) {
            return;
        }
        
        // Row 0: top with room ID
        grid[y][x] = '[';
        grid[y][x + 1] = idStr.charAt(0);
        grid[y][x + 2] = idStr.charAt(1);
        grid[y][x + 3] = idStr.charAt(2);
        grid[y][x + 4] = idStr.charAt(3);
        grid[y][x + 5] = ']';
        
        // Row 1: middle (for horizontal connectors)
        grid[y + 1][x] = '[';
        grid[y + 1][x + 1] = ' ';
        grid[y + 1][x + 2] = ' ';
        grid[y + 1][x + 3] = ' ';
        grid[y + 1][x + 4] = ' ';
        grid[y + 1][x + 5] = ']';
        
        // Row 2: bottom border  
        grid[y + 2][x] = '[';
        grid[y + 2][x + 1] = '_';
        grid[y + 2][x + 2] = '_';
        grid[y + 2][x + 3] = '_';
        grid[y + 2][x + 4] = '_';
        grid[y + 2][x + 5] = ']';
    }
    
    /**
     * Find connected components in the room graph.
     */
    private List<Set<Integer>> findConnectedComponents(List<Room> rooms, Map<Integer, Room> roomMap) {
        Set<Integer> allRoomIds = new HashSet<>();
        for (Room r : rooms) {
            allRoomIds.add(r.id);
        }
        
        List<Set<Integer>> components = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        
        for (Room room : rooms) {
            if (visited.contains(room.id)) continue;
            
            // BFS to find all connected rooms
            Set<Integer> component = new HashSet<>();
            Queue<Integer> queue = new LinkedList<>();
            queue.add(room.id);
            
            while (!queue.isEmpty()) {
                int currentId = queue.poll();
                if (visited.contains(currentId)) continue;
                visited.add(currentId);
                
                if (!allRoomIds.contains(currentId)) continue;
                component.add(currentId);
                
                Room current = roomMap.get(currentId);
                if (current == null) continue;
                
                // Add connected rooms (only if they're in this area)
                if (current.exitN != null && allRoomIds.contains(current.exitN) && !visited.contains(current.exitN)) {
                    queue.add(current.exitN);
                }
                if (current.exitS != null && allRoomIds.contains(current.exitS) && !visited.contains(current.exitS)) {
                    queue.add(current.exitS);
                }
                if (current.exitE != null && allRoomIds.contains(current.exitE) && !visited.contains(current.exitE)) {
                    queue.add(current.exitE);
                }
                if (current.exitW != null && allRoomIds.contains(current.exitW) && !visited.contains(current.exitW)) {
                    queue.add(current.exitW);
                }
                if (current.exitU != null && allRoomIds.contains(current.exitU) && !visited.contains(current.exitU)) {
                    queue.add(current.exitU);
                }
                if (current.exitD != null && allRoomIds.contains(current.exitD) && !visited.contains(current.exitD)) {
                    queue.add(current.exitD);
                }
            }
            
            if (!component.isEmpty()) {
                components.add(component);
            }
        }
        
        return components;
    }
    
    // ==================== Database Access ====================
    
    private List<Area> getAllAreas() {
        List<Area> areas = new ArrayList<>();
        String sql = "SELECT id, name, description FROM area ORDER BY id";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                areas.add(new Area(rs.getInt("id"), rs.getString("name"), rs.getString("description")));
            }
        } catch (SQLException e) {
            logger.warn("Failed to get areas: {}", e.getMessage(), e);
        }
        return areas;
    }
    
    private Area getAreaById(int id) {
        String sql = "SELECT id, name, description FROM area WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Area(rs.getInt("id"), rs.getString("name"), rs.getString("description"));
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to get area: {}", e.getMessage(), e);
        }
        return null;
    }
    
    private List<Room> getRoomsByArea(int areaId) {
        List<Room> rooms = new ArrayList<>();
        String sql = "SELECT id, name, exit_n, exit_e, exit_s, exit_w, exit_u, exit_d FROM room WHERE area_id = ? ORDER BY id";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, areaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rooms.add(new Room(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getObject("exit_n") == null ? null : rs.getInt("exit_n"),
                        rs.getObject("exit_e") == null ? null : rs.getInt("exit_e"),
                        rs.getObject("exit_s") == null ? null : rs.getInt("exit_s"),
                        rs.getObject("exit_w") == null ? null : rs.getInt("exit_w"),
                        rs.getObject("exit_u") == null ? null : rs.getInt("exit_u"),
                        rs.getObject("exit_d") == null ? null : rs.getInt("exit_d")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to get rooms: {}", e.getMessage(), e);
        }
        return rooms;
    }
    
    private String sanitizeFilename(String name) {
        return name.toLowerCase()
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
    }
    
    // ==================== Data Classes ====================
    
    private static class Area {
        final int id;
        final String name;
        final String description;
        
        Area(int id, String name, String description) {
            this.id = id;
            this.name = name;
            this.description = description;
        }
    }
    
    private static class Room {
        final int id;
        final String name;
        final Integer exitN, exitE, exitS, exitW, exitU, exitD;
        
        Room(int id, String name, Integer exitN, Integer exitE, Integer exitS, Integer exitW, Integer exitU, Integer exitD) {
            this.id = id;
            this.name = name;
            this.exitN = exitN;
            this.exitE = exitE;
            this.exitS = exitS;
            this.exitW = exitW;
            this.exitU = exitU;
            this.exitD = exitD;
        }
    }
}
