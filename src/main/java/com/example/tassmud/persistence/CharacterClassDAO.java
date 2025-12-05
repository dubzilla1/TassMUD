package com.example.tassmud.persistence;

import com.example.tassmud.model.*;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.sql.*;
import java.util.*;

/**
 * Data Access Object for CharacterClass entities.
 * Loads class definitions from YAML and manages class-related database operations.
 */
public class CharacterClassDAO {
    private static final String URL = "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
    private static final String USER = "sa";
    private static final String PASS = "";
    
    // In-memory cache of loaded classes
    private static final Map<Integer, CharacterClass> classCache = new HashMap<>();
    
    public CharacterClassDAO() {
        ensureTables();
    }
    
    private void ensureTables() {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement()) {
            // Character class definitions (loaded from YAML, cached in DB)
            s.execute("""
                CREATE TABLE IF NOT EXISTS character_class (
                    id INT PRIMARY KEY,
                    name VARCHAR(50) NOT NULL,
                    description VARCHAR(1000),
                    hp_level INT DEFAULT 0,
                    mp_level INT DEFAULT 0,
                    mv_level INT DEFAULT 0
                )
            """);
            
            // Class skill grants - which skills/spells become available at which class level
            s.execute("""
                CREATE TABLE IF NOT EXISTS class_skill_grant (
                    class_id INT NOT NULL,
                    class_level INT NOT NULL,
                    skill_id INT NOT NULL,
                    PRIMARY KEY (class_id, class_level, skill_id)
                )
            """);
            
            // Migration: Add spell_id column for spell grants
            s.execute("ALTER TABLE class_skill_grant ADD COLUMN IF NOT EXISTS spell_id INT DEFAULT 0");
            
            // Character class progress - tracks a character's level/XP in each class
            s.execute("""
                CREATE TABLE IF NOT EXISTS character_class_progress (
                    character_id INT NOT NULL,
                    class_id INT NOT NULL,
                    class_level INT DEFAULT 1,
                    class_xp INT DEFAULT 0,
                    is_current BOOLEAN DEFAULT FALSE,
                    PRIMARY KEY (character_id, class_id)
                )
            """);
            
            System.out.println("CharacterClassDAO: tables ensured.");
        } catch (SQLException e) {
            System.err.println("Warning: failed to create character class tables: " + e.getMessage());
        }
    }
    
    /**
     * Load class definitions from YAML resource file.
     */
    public void loadClassesFromYamlResource(String resourcePath) throws Exception {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                System.err.println("Class resource not found: " + resourcePath);
                return;
            }
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(is);
            if (data == null || !data.containsKey("classes")) return;
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> classes = (List<Map<String, Object>>) data.get("classes");
            
            for (Map<String, Object> cls : classes) {
                int id = parseIntSafe(cls.get("id"));
                String name = str(cls.get("name"));
                String description = str(cls.get("description"));
                int hpLevel = parseIntSafe(cls.get("hp_level"));
                int mpLevel = parseIntSafe(cls.get("mp_level"));
                int mvLevel = parseIntSafe(cls.get("mv_level"));
                
                // Parse skill grants
                List<CharacterClass.ClassSkillGrant> skillGrants = new ArrayList<>();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> skills = (List<Map<String, Object>>) cls.get("skills");
                if (skills != null) {
                    for (Map<String, Object> skill : skills) {
                        int level = parseIntSafe(skill.get("level"));
                        int skillId = parseIntSafe(skill.get("skill_id"));
                        int spellId = parseIntSafe(skill.get("spell_id"));
                        skillGrants.add(new CharacterClass.ClassSkillGrant(id, level, skillId, spellId));
                    }
                }
                
                // Create and cache the class
                CharacterClass charClass = new CharacterClass(id, name, description, 
                    hpLevel, mpLevel, mvLevel, skillGrants);
                classCache.put(id, charClass);
                
                // Persist to database
                saveClassToDb(charClass);
            }
            
            System.out.println("Loaded character classes: " + classCache.size());
        }
    }
    
    private void saveClassToDb(CharacterClass cls) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
            // Save class definition
            try (PreparedStatement ps = c.prepareStatement(
                    "MERGE INTO character_class (id, name, description, hp_level, mp_level, mv_level) " +
                    "KEY(id) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setInt(1, cls.id);
                ps.setString(2, cls.name);
                ps.setString(3, cls.description);
                ps.setInt(4, cls.hpPerLevel);
                ps.setInt(5, cls.mpPerLevel);
                ps.setInt(6, cls.mvPerLevel);
                ps.executeUpdate();
            }
            
            // Save skill grants
            // First delete existing grants for this class
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM class_skill_grant WHERE class_id = ?")) {
                ps.setInt(1, cls.id);
                ps.executeUpdate();
            }
            
            // Insert new grants (skills and spells)
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO class_skill_grant (class_id, class_level, skill_id, spell_id) VALUES (?, ?, ?, ?)")) {
                for (CharacterClass.ClassSkillGrant grant : cls.getSkillGrants()) {
                    ps.setInt(1, grant.classId);
                    ps.setInt(2, grant.classLevel);
                    ps.setInt(3, grant.skillId);
                    ps.setInt(4, grant.spellId);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (SQLException e) {
            System.err.println("Failed to save class to DB: " + e.getMessage());
        }
    }
    
    /**
     * Get a character class by ID.
     */
    public CharacterClass getClassById(int id) {
        return classCache.get(id);
    }
    
    /**
     * Get a character class by name (case-insensitive).
     */
    public CharacterClass getClassByName(String name) {
        if (name == null) return null;
        for (CharacterClass cls : classCache.values()) {
            if (cls.name.equalsIgnoreCase(name)) {
                return cls;
            }
        }
        return null;
    }
    
    /**
     * Get all available character classes.
     */
    public List<CharacterClass> getAllClasses() {
        return new ArrayList<>(classCache.values());
    }
    
    /**
     * Set a character's current class.
     */
    public void setCharacterCurrentClass(int characterId, int classId) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
            // First, clear current flag for all classes
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE character_class_progress SET is_current = FALSE WHERE character_id = ?")) {
                ps.setInt(1, characterId);
                ps.executeUpdate();
            }
            
            // Ensure the character has a progress entry for this class
            try (PreparedStatement ps = c.prepareStatement(
                    "MERGE INTO character_class_progress (character_id, class_id, class_level, class_xp, is_current) " +
                    "KEY(character_id, class_id) VALUES (?, ?, COALESCE((SELECT class_level FROM character_class_progress " +
                    "WHERE character_id = ? AND class_id = ?), 1), COALESCE((SELECT class_xp FROM character_class_progress " +
                    "WHERE character_id = ? AND class_id = ?), 0), TRUE)")) {
                ps.setInt(1, characterId);
                ps.setInt(2, classId);
                ps.setInt(3, characterId);
                ps.setInt(4, classId);
                ps.setInt(5, characterId);
                ps.setInt(6, classId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            System.err.println("Failed to set character current class: " + e.getMessage());
        }
    }
    
    /**
     * Get a character's current class ID, or null if none set.
     */
    public Integer getCharacterCurrentClassId(int characterId) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT class_id FROM character_class_progress WHERE character_id = ? AND is_current = TRUE")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("class_id");
                }
            }
        } catch (SQLException e) {
            System.err.println("Failed to get character current class: " + e.getMessage());
        }
        return null;
    }
    
    /**
     * Get a character's level in a specific class.
     */
    public int getCharacterClassLevel(int characterId, int classId) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT class_level FROM character_class_progress WHERE character_id = ? AND class_id = ?")) {
            ps.setInt(1, characterId);
            ps.setInt(2, classId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("class_level");
                }
            }
        } catch (SQLException e) {
            System.err.println("Failed to get character class level: " + e.getMessage());
        }
        return 0;  // No progress in this class
    }
    
    /**
     * Get a character's XP in a specific class.
     */
    public int getCharacterClassXp(int characterId, int classId) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT class_xp FROM character_class_progress WHERE character_id = ? AND class_id = ?")) {
            ps.setInt(1, characterId);
            ps.setInt(2, classId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("class_xp");
                }
            }
        } catch (SQLException e) {
            System.err.println("Failed to get character class XP: " + e.getMessage());
        }
        return 0;
    }
    
    /**
     * Add XP to a character's current class. Returns true if they leveled up.
     * XP resets to 0 after each level-up (1000 XP per level).
     */
    public boolean addXpToCurrentClass(int characterId, int xpAmount) {
        Integer classId = getCharacterCurrentClassId(characterId);
        if (classId == null) return false;
        
        int currentXp = getCharacterClassXp(characterId, classId);
        int currentLevel = getCharacterClassLevel(characterId, classId);
        int newXp = currentXp + xpAmount;
        
        // Check if we've hit the threshold for next level (1000 XP)
        boolean leveledUp = false;
        int newLevel = currentLevel;
        
        if (newXp >= CharacterClass.XP_PER_LEVEL && currentLevel < CharacterClass.MAX_HERO_LEVEL) {
            // Level up! Reset XP to 0
            newLevel = currentLevel + 1;
            newXp = 0;
            leveledUp = true;
        }
        
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE character_class_progress SET class_xp = ?, class_level = ? " +
                     "WHERE character_id = ? AND class_id = ?")) {
            ps.setInt(1, newXp);
            ps.setInt(2, newLevel);
            ps.setInt(3, characterId);
            ps.setInt(4, classId);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Failed to add XP: " + e.getMessage());
            return false;
        }
        
        return leveledUp;
    }
    
    /**
     * Get all class progress for a character.
     */
    public List<ClassProgress> getCharacterClassProgress(int characterId) {
        List<ClassProgress> result = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT class_id, class_level, class_xp, is_current FROM character_class_progress " +
                     "WHERE character_id = ? ORDER BY class_id")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new ClassProgress(
                        rs.getInt("class_id"),
                        rs.getInt("class_level"),
                        rs.getInt("class_xp"),
                        rs.getBoolean("is_current")
                    ));
                }
            }
        } catch (SQLException e) {
            System.err.println("Failed to get class progress: " + e.getMessage());
        }
        return result;
    }
    
    /**
     * Handle level-up for a character in their current class.
     * This method encapsulates all level-up mechanics:
     * 1) The level has already been incremented in the DB by addXpToCurrentClass
     * 2) Grant any new skills from the class at the new level
     * 3) Add HP/MP/MV bonuses from the class and restore to full
     * 
     * @param characterId The character who leveled up
     * @param newLevel The new level they reached
     * @param messageCallback Callback to send messages to the player (can be null)
     * @return List of skill names learned (for display purposes)
     */
    public java.util.List<String> processLevelUp(int characterId, int newLevel, 
            java.util.function.Consumer<String> messageCallback) {
        java.util.List<String> learnedSkills = new java.util.ArrayList<>();
        
        Integer classId = getCharacterCurrentClassId(characterId);
        if (classId == null) return learnedSkills;
        
        CharacterClass charClass = getClassById(classId);
        if (charClass == null) return learnedSkills;
        
        CharacterDAO charDAO = new CharacterDAO();
        
        // 1) Grant any skills/spells that unlock at this level
        for (CharacterClass.ClassSkillGrant grant : charClass.getSkillsUnlockedAtLevel(newLevel)) {
            if (grant.isSpellGrant()) {
                // Grant spell
                if (!charDAO.hasSpell(characterId, grant.spellId)) {
                    Spell spellDef = charDAO.getSpellById(grant.spellId);
                    if (spellDef != null) {
                        charDAO.learnSpell(characterId, grant.spellId, spellDef);
                        learnedSkills.add(spellDef.getName() + " (spell)");
                        if (messageCallback != null) {
                            messageCallback.accept("You have learned the spell " + spellDef.getName() + "!");
                        }
                    } else {
                        charDAO.learnSpell(characterId, grant.spellId);
                        learnedSkills.add("spell #" + grant.spellId);
                        if (messageCallback != null) {
                            messageCallback.accept("You have learned a new spell!");
                        }
                    }
                }
            } else if (grant.isSkillGrant()) {
                // Grant skill
                if (!charDAO.hasSkill(characterId, grant.skillId)) {
                    Skill skillDef = charDAO.getSkillById(grant.skillId);
                    if (skillDef != null) {
                        charDAO.learnSkill(characterId, grant.skillId, skillDef);
                        learnedSkills.add(skillDef.getName());
                        if (messageCallback != null) {
                            messageCallback.accept("You have learned " + skillDef.getName() + "!");
                        }
                    } else {
                        charDAO.learnSkill(characterId, grant.skillId);
                        learnedSkills.add("skill #" + grant.skillId);
                        if (messageCallback != null) {
                            messageCallback.accept("You have learned a new skill!");
                        }
                    }
                }
            }
        }
        
        // 2) Add HP/MP/MV bonuses for this level
        int hpGain = charClass.hpPerLevel;
        int mpGain = charClass.mpPerLevel;
        int mvGain = charClass.mvPerLevel;
        charDAO.addVitals(characterId, hpGain, mpGain, mvGain);
        
        // 3) Restore to full (like GM restore command)
        charDAO.restoreVitals(characterId);
        
        // 4) Award 1 talent point for leveling
        charDAO.addTalentPoints(characterId, 1);
        if (messageCallback != null) {
            messageCallback.accept("You have gained a Talent Point! Use TRAIN to spend it.");
        }
        
        return learnedSkills;
    }
    
    /**
     * Record of a character's progress in a class.
     */
    public static class ClassProgress {
        public final int classId;
        public final int level;
        public final int xp;
        public final boolean isCurrent;
        
        public ClassProgress(int classId, int level, int xp, boolean isCurrent) {
            this.classId = classId;
            this.level = level;
            this.xp = xp;
            this.isCurrent = isCurrent;
        }
        
        public int xpToNextLevel() {
            if (level >= CharacterClass.MAX_HERO_LEVEL) return 0;
            int xpForNextLevel = CharacterClass.xpRequiredForLevel(level + 1);
            return xpForNextLevel - xp;
        }
    }
    
    private static String str(Object o) {
        return o == null ? null : o.toString().trim();
    }
    
    private static int parseIntSafe(Object o) {
        if (o == null) return 0;
        try { return Integer.parseInt(o.toString().trim()); } catch (Exception e) { return 0; }
    }
}
