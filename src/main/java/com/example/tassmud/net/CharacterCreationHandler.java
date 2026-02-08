package com.example.tassmud.net;

import com.example.tassmud.model.CharacterClass;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.Room;
import com.example.tassmud.model.Skill;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.persistence.TransactionManager;
import com.example.tassmud.util.PasswordUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.List;

/**
 * Handles the login and character creation flow for new connections.
 * Extracted from ClientHandler to keep session management separate from
 * the interactive onboarding experience.
 */
public class CharacterCreationHandler {

    private final PrintWriter out;

    /**
     * Result of a successful login or character creation.
     * ClientHandler uses this to set its own session state.
     */
    public record LoginResult(String playerName, Integer characterId, Integer currentRoomId) {}

    public CharacterCreationHandler(PrintWriter out) {
        this.out = out;
    }

    // =========================================================================
    // LOGIN FLOW
    // =========================================================================

    /**
     * Runs the full login/creation flow: title art → name prompt →
     * password authentication (existing) or character creation (new) →
     * room validation → MOTD.
     *
     * @return LoginResult on success, null on failure/disconnect
     */
    public LoginResult runLogin(BufferedReader in) throws Exception {
        // Show title art
        try {
            java.io.InputStream titleStream = getClass().getClassLoader().getResourceAsStream("asciiart/title.txt");
            if (titleStream != null) {
                try (BufferedReader titleReader = new BufferedReader(new InputStreamReader(titleStream))) {
                    String line;
                    while ((line = titleReader.readLine()) != null) {
                        out.println(line);
                    }
                }
                out.println();
            }
        } catch (Exception e) {
            // Silently ignore if title art can't be loaded
        }

        out.println("Welcome to TassMUD!");
        out.flush();
        try {
            // brief pause to avoid socket output interleaving in some telnet clients
            Thread.sleep(50);
        } catch (InterruptedException ignored) {
        }

        CharacterDAO dao = DaoProvider.characters();

        // Login / character creation flow
        out.print("Enter character name: "); out.flush();
        String name = in.readLine();
        if (name == null) return null;
        name = name.trim();
        if (name.isEmpty()) {
            out.println("Invalid name. Disconnecting.");
            return null;
        }

        CharacterRecord rec = dao.findByName(name);
        if (rec == null) {
            // Character creation flow
            rec = runCharacterCreation(name, in, dao);
            if (rec == null) {
                return null;
            }
        } else {
            // Existing character — authenticate
            boolean authenticated = false;
            for (int tries = 3; tries > 0; tries--) {
                out.print("Password: "); out.flush();
                String pwAttempt = in.readLine();
                if (pwAttempt == null) return null;
                if (dao.verifyPassword(name, pwAttempt.toCharArray())) {
                    authenticated = true;
                    out.println("Welcome back, " + name + "!");
                    break;
                } else {
                    out.println("Invalid password. " + (tries - 1) + " attempts remaining.");
                }
            }
            if (!authenticated) {
                out.println("Too many failed attempts. Goodbye.");
                return null;
            }
        }

        // Ensure player's saved room exists; if not, place them into the debug Void (id 0)
        if (rec != null) {
            Integer cur = rec.currentRoom;
            Room check = (cur == null) ? null : DaoProvider.rooms().getRoomById(cur);
            if (check == null) {
                out.println("Notice: your character was not in a valid room; placing you in The Void.");
                dao.updateCharacterRoom(name, 0);
                rec = dao.findByName(name);
            }
        }

        Integer characterId = dao.getCharacterIdByName(name);
        Integer currentRoomId = rec != null ? rec.currentRoom : null;

        // Show MOTD (if any) after login/creation
        try {
            String motd = DaoProvider.settings().getSetting("motd");
            if (motd != null && !motd.trim().isEmpty()) {
                out.println("--- Message of the Day ---");
                String[] motdLines = motd.split("\\r?\\n");
                for (String ml : motdLines) out.println(ml);
                out.println("--- End of MOTD ---");
            }
        } catch (Exception ignored) {}

        out.println("Type 'look', 'say <text>', 'chat <text>', 'yell <text>', 'whisper <who> <text>', 'motd', or 'quit'");

        return new LoginResult(name, characterId, currentRoomId);
    }

    // =========================================================================
    // CHARACTER CREATION FLOW
    // =========================================================================

    /**
     * Runs the full character creation flow.
     * Returns the CharacterRecord if successful, null if creation failed/aborted.
     */
    CharacterRecord runCharacterCreation(String name, BufferedReader in, CharacterDAO dao) throws IOException {
        out.println("Character '" + name + "' not found. Creating new character.");
        out.println();

        // Step 1: Password
        String passwordHash = null;
        String passwordSalt = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            out.print("Create password: "); out.flush();
            String p1 = in.readLine();
            if (p1 == null) return null;
            out.print("Re-type password: "); out.flush();
            String p2 = in.readLine();
            if (p2 == null) return null;
            if (!p1.equals(p2)) {
                out.println("Passwords do not match. Try again.");
                continue;
            }
            passwordSalt = PasswordUtil.generateSaltBase64();
            passwordHash = PasswordUtil.hashPasswordBase64(p1.toCharArray(),
                java.util.Base64.getDecoder().decode(passwordSalt));
            break;
        }
        if (passwordHash == null) {
            out.println("Failed to set password after several attempts. Goodbye.");
            return null;
        }
        out.println();

        // Step 2: Class Selection
        CharacterClass selectedClass = runClassSelection(in);
        if (selectedClass == null) {
            out.println("Class selection failed. Goodbye.");
            return null;
        }
        out.println();

        // Step 3: Age
        out.print("Enter your character's age (number): "); out.flush();
        String ageLine = in.readLine();
        int age = 0;
        try {
            if (ageLine != null) age = Integer.parseInt(ageLine.trim());
        } catch (Exception ignored) {}
        if (age < 1) age = 18; // default age
        out.println();

        // Step 4: Description
        out.print("Enter a short description of your character: "); out.flush();
        String desc = in.readLine();
        if (desc == null) desc = "";
        out.println();

        // Calculate initial stats based on class
        int baseHp = 100, baseMp = 50, baseMv = 100;
        int hpMax = baseHp + selectedClass.hpPerLevel;
        int mpMax = baseMp + selectedClass.mpPerLevel;
        int mvMax = baseMv + selectedClass.mvPerLevel;
        int hpCur = hpMax, mpCur = mpMax, mvCur = mvMax;

        // Default ability scores
        int str = 10, dex = 10, con = 10, intel = 10, wis = 10, cha = 10;
        // Default saves
        int armor = 10, fortitude = 10, reflex = 10, will = 10;

        // Default starting room: The Encampment (1000) - tutorial start
        int startRoomId = 1000;
        Room startRoom = DaoProvider.rooms().getRoomById(startRoomId);
        if (startRoom == null) {
            int anyRoomId = DaoProvider.rooms().getAnyRoomId();
            startRoomId = anyRoomId > 0 ? anyRoomId : 0;
        }
        Integer currentRoom = startRoomId > 0 ? startRoomId : null;

        // Create the character
        com.example.tassmud.model.StatBlock initStats = new com.example.tassmud.model.StatBlock(
                str, dex, con, intel, wis, cha, armor, fortitude, reflex, will);
        GameCharacter ch = new GameCharacter(name, age, desc, hpMax, hpCur, mpMax, mpCur, mvMax, mvCur,
                currentRoom, initStats);
        // Create character + assign class in a single transaction
        final String finalHash = passwordHash;
        final String finalSalt = passwordSalt;
        boolean ok = TransactionManager.runInTransaction(() -> {
            boolean created = dao.createCharacter(ch, finalHash, finalSalt);
            if (!created) return false;

            Integer newCharId = dao.getCharacterIdByName(name);
            if (newCharId == null) return false;

            CharacterClassDAO classDao = DaoProvider.classes();
            classDao.setCharacterCurrentClass(newCharId, selectedClass.id);
            dao.updateCharacterClass(newCharId, selectedClass.id);
            return true;
        });
        if (!ok) {
            out.println("Failed to create character (name may be taken). Try a different name.");
            return null;
        }

        // Get the character ID for display
        Integer characterId = dao.getCharacterIdByName(name);
        if (characterId == null) {
            out.println("Failed to retrieve character ID after creation.");
            return null;
        }

        out.println("=========================================");
        out.println("Character created successfully!");
        out.println("Name: " + name);
        out.println("Class: " + selectedClass.name + " (Level 1)");
        out.println("HP: " + hpMax + " | MP: " + mpMax + " | MV: " + mvMax);
        out.println("=========================================");
        out.println("Welcome to TassMUD, " + name + "!");
        out.println();

        return dao.findByName(name);
    }

    /**
     * Handles class selection during character creation.
     * Returns the selected CharacterClass, or null if selection failed.
     */
    CharacterClass runClassSelection(BufferedReader in) throws IOException {
        CharacterClassDAO classDao = DaoProvider.classes();

        // Load classes from YAML if not already loaded
        try {
            classDao.loadClassesFromYamlResource("/data/classes.yaml");
        } catch (Exception e) {
            out.println("Warning: Could not load class data.");
        }

        List<CharacterClass> allClasses = classDao.getAllClasses();
        if (allClasses.isEmpty()) {
            out.println("Error: No classes available!");
            return null;
        }

        // Sort classes by ID for consistent display
        allClasses.sort((a, b) -> Integer.compare(a.id, b.id));

        out.println("=== SELECT YOUR CLASS ===");
        out.println();
        for (CharacterClass cls : allClasses) {
            out.println(String.format("  [%d] %s", cls.id, cls.name));
            out.println(String.format("      HP/lvl: %+d  MP/lvl: %+d  MV/lvl: %+d",
                cls.hpPerLevel, cls.mpPerLevel, cls.mvPerLevel));
        }
        out.println();
        out.println("Enter a number to see more details, or type the class name to select it.");
        out.println();

        // Allow up to 10 attempts to select a valid class
        for (int attempt = 0; attempt < 10; attempt++) {
            out.print("Your choice: "); out.flush();
            String input = in.readLine();
            if (input == null) return null;
            input = input.trim();
            if (input.isEmpty()) continue;

            // Check if input is a number (show details)
            try {
                int classId = Integer.parseInt(input);
                CharacterClass cls = classDao.getClassById(classId);
                if (cls != null) {
                    showClassDetails(cls);
                    out.println();
                    out.print("Select " + cls.name + "? (yes/no): "); out.flush();
                    String confirm = in.readLine();
                    if (confirm != null && (confirm.trim().equalsIgnoreCase("yes") || confirm.trim().equalsIgnoreCase("y"))) {
                        out.println("You have chosen the path of the " + cls.name + "!");
                        return cls;
                    }
                    out.println();
                    out.println("Enter a number to see more details, or type the class name to select it.");
                    continue;
                } else {
                    out.println("Invalid class number. Please try again.");
                    continue;
                }
            } catch (NumberFormatException ignored) {
                // Not a number, check if it's a class name
            }

            // Try to match by name (case-insensitive, partial match)
            CharacterClass match = null;
            for (CharacterClass cls : allClasses) {
                if (cls.name.equalsIgnoreCase(input)) {
                    match = cls;
                    break;
                }
            }
            // If no exact match, try prefix match
            if (match == null) {
                for (CharacterClass cls : allClasses) {
                    if (cls.name.toLowerCase().startsWith(input.toLowerCase())) {
                        match = cls;
                        break;
                    }
                }
            }

            if (match != null) {
                showClassDetails(match);
                out.println();
                out.print("Select " + match.name + "? (yes/no): "); out.flush();
                String confirm = in.readLine();
                if (confirm != null && (confirm.trim().equalsIgnoreCase("yes") || confirm.trim().equalsIgnoreCase("y"))) {
                    out.println("You have chosen the path of the " + match.name + "!");
                    return match;
                }
                out.println();
                out.println("Enter a number to see more details, or type the class name to select it.");
            } else {
                out.println("Unknown class '" + input + "'. Please enter a valid class number or name.");
            }
        }

        out.println("Too many invalid attempts.");
        return null;
    }

    /**
     * Display detailed information about a character class.
     */
    void showClassDetails(CharacterClass cls) {
        out.println();
        out.println("=== " + cls.name.toUpperCase() + " ===");
        out.println(cls.description);
        out.println();
        out.println("Stats per level:");
        out.println("  HP: +" + cls.hpPerLevel + "  MP: +" + cls.mpPerLevel + "  MV: +" + cls.mvPerLevel);
        out.println();
        // Show some early skills if available
        List<CharacterClass.ClassSkillGrant> grants = cls.getSkillsAtLevel(10);
        if (!grants.isEmpty()) {
            out.println("Early skills (levels 1-10):");
            for (CharacterClass.ClassSkillGrant grant : grants) {
                Skill skillDef = DaoProvider.skills().getSkillById(grant.skillId);
                String skillName = skillDef != null ? skillDef.getName() : "Skill #" + grant.skillId;
                out.println("  Level " + grant.classLevel + ": " + skillName);
            }
        }
    }
}
