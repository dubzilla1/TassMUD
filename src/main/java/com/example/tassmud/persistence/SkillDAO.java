package com.example.tassmud.persistence;

import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.SkillProgression;
import com.example.tassmud.model.SkillTrait;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DAO for skilltb and character_skill tables.
 * Extracted from CharacterDAO to separate skill/proficiency concerns from character data.
 */
public class SkillDAO {

    private static final Logger logger = LoggerFactory.getLogger(SkillDAO.class);

    private static final String URL = System.getProperty("tassmud.db.url",
            "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1");
    public SkillDAO() {
        MigrationManager.ensureMigration("SkillDAO", this::ensureTable);
    }

    // ========================== Schema ==========================

    public void ensureTable() {
        // Skills table
        try (Connection c = TransactionManager.getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS skilltb (" +
                    "id INT PRIMARY KEY, " +
                    "skill_key VARCHAR(100) UNIQUE NOT NULL, " +
                    "name VARCHAR(100) NOT NULL, " +
                    "description VARCHAR(1024) DEFAULT '', " +
                    "is_passive BOOLEAN DEFAULT FALSE, " +
                    "max_level INT DEFAULT 100, " +
                    "progression VARCHAR(50) DEFAULT 'NORMAL', " +
                    "traits VARCHAR(500) DEFAULT '', " +
                    "cooldown DOUBLE DEFAULT 0 " +
                    ")");
            s.execute("ALTER TABLE skilltb ADD COLUMN IF NOT EXISTS skill_key VARCHAR(100)");
            s.execute("ALTER TABLE skilltb ADD COLUMN IF NOT EXISTS is_passive BOOLEAN DEFAULT FALSE");
            s.execute("ALTER TABLE skilltb ADD COLUMN IF NOT EXISTS max_level INT DEFAULT 100");
            s.execute("ALTER TABLE skilltb ADD COLUMN IF NOT EXISTS progression VARCHAR(50) DEFAULT 'NORMAL'");
            s.execute("ALTER TABLE skilltb ADD COLUMN IF NOT EXISTS traits VARCHAR(500) DEFAULT ''");
            s.execute("ALTER TABLE skilltb ADD COLUMN IF NOT EXISTS cooldown DOUBLE DEFAULT 0");
            s.execute("ALTER TABLE skilltb ADD COLUMN IF NOT EXISTS duration DOUBLE DEFAULT 0");
            s.execute("ALTER TABLE skilltb ADD COLUMN IF NOT EXISTS effect_ids VARCHAR(500) DEFAULT ''");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create skilltb table", e);
        }

        // Character-Skill join table
        try (Connection c = TransactionManager.getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS character_skill (" +
                    "character_id INT NOT NULL, " +
                    "skill_id INT NOT NULL, " +
                    "level INT DEFAULT 1, " +
                    "PRIMARY KEY (character_id, skill_id) " +
                    ")");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create character_skill table", e);
        }
    }

    // ========================== Skill Definition Methods ==========================

    public boolean addSkill(String name, String description) {
        String sql = "INSERT INTO skilltb (name, description) VALUES (?, ?)";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, description);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Add a skill with full details. Uses MERGE to update if exists.
     */
    public boolean addSkillFull(int id, String key, String name, String description,
                                boolean isPassive, int maxLevel, SkillProgression progression,
                                List<SkillTrait> traits, double cooldown,
                                double duration, List<String> effectIds) {
        String sql = "MERGE INTO skilltb (id, skill_key, name, description, is_passive, max_level, progression, traits, cooldown, duration, effect_ids) " +
                     "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String traitsStr = traits != null ? traits.stream().map(Enum::name).collect(Collectors.joining(",")) : "";
        String effectStr = effectIds != null ? String.join(",", effectIds) : "";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setString(2, key);
            ps.setString(3, name);
            ps.setString(4, description);
            ps.setBoolean(5, isPassive);
            ps.setInt(6, maxLevel);
            ps.setString(7, progression != null ? progression.name() : "NORMAL");
            ps.setString(8, traitsStr);
            ps.setDouble(9, cooldown);
            ps.setDouble(10, duration);
            ps.setString(11, effectStr);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.warn("Failed to add skill {} ({}): {}", id, name, e.getMessage());
            return false;
        }
    }

    /**
     * Get a skill by its key (e.g., "simple_weapons").
     */
    public Skill getSkillByKey(String key) {
        String sql = "SELECT id, skill_key, name, description, progression, traits, cooldown, duration, effect_ids FROM skilltb WHERE skill_key = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapSkillFromResultSet(rs);
                }
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    public Skill getSkillById(int id) {
        String sql = "SELECT id, skill_key, name, description, progression, traits, cooldown, duration, effect_ids FROM skilltb WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapSkillFromResultSet(rs);
                }
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    /**
     * Get all skills from the database.
     */
    public List<Skill> getAllSkills() {
        List<Skill> skills = new ArrayList<>();
        String sql = "SELECT id, skill_key, name, description, progression, traits, cooldown, duration, effect_ids FROM skilltb ORDER BY name";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                skills.add(mapSkillFromResultSet(rs));
            }
        } catch (SQLException e) {
            // Return empty list on error
        }
        return skills;
    }

    /**
     * Map a ResultSet row to a Skill object.
     */
    private Skill mapSkillFromResultSet(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        String name = rs.getString("name");
        String description = rs.getString("description");
        String progressionStr = rs.getString("progression");
        String traitsStr = rs.getString("traits");
        double cooldown = rs.getDouble("cooldown");
        double duration = rs.getDouble("duration");
        String effectIdsStr = rs.getString("effect_ids");

        SkillProgression progression = SkillProgression.fromString(progressionStr);

        List<SkillTrait> traits = new ArrayList<>();
        if (traitsStr != null && !traitsStr.isEmpty()) {
            for (String t : traitsStr.split(",")) {
                SkillTrait trait = SkillTrait.fromString(t.trim());
                if (trait != null) traits.add(trait);
            }
        }

        List<String> effectIds = new ArrayList<>();
        if (effectIdsStr != null && !effectIdsStr.isEmpty()) {
            for (String e : effectIdsStr.split(",")) {
                String trimmed = e.trim();
                if (!trimmed.isEmpty()) effectIds.add(trimmed);
            }
        }

        return new Skill(id, name, description, progression, traits, cooldown, duration, effectIds);
    }

    // ========================== Character-Skill Methods ==========================

    public boolean setCharacterSkillLevel(int characterId, int skillId, int level) {
        String sql = "MERGE INTO character_skill (character_id, skill_id, level) KEY (character_id, skill_id) VALUES (?, ?, ?)";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setInt(2, skillId);
            ps.setInt(3, level);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public CharacterSkill getCharacterSkill(int characterId, int skillId) {
        String sql = "SELECT character_id, skill_id, level FROM character_skill WHERE character_id = ? AND skill_id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setInt(2, skillId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new CharacterSkill(rs.getInt("character_id"), rs.getInt("skill_id"), rs.getInt("level"));
                }
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    /**
     * Learn a new skill (starts at 1% proficiency).
     * If already known, does nothing and returns false.
     */
    public boolean learnSkill(int characterId, int skillId) {
        if (getCharacterSkill(characterId, skillId) != null) {
            return false;
        }
        return setCharacterSkillLevel(characterId, skillId, CharacterSkill.MIN_PROFICIENCY);
    }

    /**
     * Learn a new skill with a specific starting proficiency based on skill definition.
     * INSTANT progression skills start at 100% (mastered).
     * If already known, does nothing and returns false.
     */
    public boolean learnSkill(int characterId, int skillId, Skill skillDef) {
        if (getCharacterSkill(characterId, skillId) != null) {
            return false;
        }
        int startingProficiency = skillDef != null
            ? skillDef.getProgression().getStartingProficiency()
            : CharacterSkill.MIN_PROFICIENCY;
        return setCharacterSkillLevel(characterId, skillId, startingProficiency);
    }

    /**
     * Check if a character knows a skill.
     */
    public boolean hasSkill(int characterId, int skillId) {
        return getCharacterSkill(characterId, skillId) != null;
    }

    /**
     * Get all skills learned by a character.
     */
    public List<CharacterSkill> getAllCharacterSkills(int characterId) {
        List<CharacterSkill> skills = new ArrayList<>();
        String sql = "SELECT character_id, skill_id, level FROM character_skill WHERE character_id = ? ORDER BY skill_id";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    skills.add(new CharacterSkill(
                        rs.getInt("character_id"),
                        rs.getInt("skill_id"),
                        rs.getInt("level")
                    ));
                }
            }
        } catch (SQLException e) {
            // Return empty list on error
        }
        return skills;
    }

    /**
     * Increase skill proficiency by a given amount.
     * Returns the new proficiency level, or -1 on error.
     */
    public int increaseSkillProficiency(int characterId, int skillId, int amount) {
        CharacterSkill skill = getCharacterSkill(characterId, skillId);
        if (skill == null) return -1;

        int newProficiency = Math.min(CharacterSkill.MAX_PROFICIENCY, skill.getProficiency() + amount);
        if (setCharacterSkillLevel(characterId, skillId, newProficiency)) {
            return newProficiency;
        }
        return -1;
    }

    /**
     * Try to improve a skill based on its progression curve.
     * Returns true if proficiency increased, false otherwise.
     */
    public boolean tryImproveSkill(int characterId, int skillId, Skill skillDef) {
        CharacterSkill charSkill = getCharacterSkill(characterId, skillId);
        if (charSkill == null || charSkill.isMastered()) return false;

        int gainChance = skillDef.getProgression().getGainChance(charSkill.getProficiency());
        int roll = ThreadLocalRandom.current().nextInt(1, 101);

        if (roll <= gainChance) {
            increaseSkillProficiency(characterId, skillId, 1);
            return true;
        }
        return false;
    }
}
