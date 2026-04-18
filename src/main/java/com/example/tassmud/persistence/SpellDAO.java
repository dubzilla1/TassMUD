package com.example.tassmud.persistence;

import com.example.tassmud.model.CharacterSpell;
import com.example.tassmud.model.SkillProgression;
import com.example.tassmud.model.Spell;
import com.example.tassmud.model.SpellTrait;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DAO for spelltb and character_spell tables.
 * Extracted from CharacterDAO to separate spell/proficiency concerns from character data.
 */
public class SpellDAO {

    private static final Logger logger = LoggerFactory.getLogger(SpellDAO.class);

    private static final String URL = System.getProperty("tassmud.db.url",
            "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1");
    public SpellDAO() {
        MigrationManager.ensureMigration("SpellDAO", this::ensureTable);
    }

    // ========================== Schema ==========================

    public void ensureTable() {
        // Spells table
        try (Connection c = TransactionManager.getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS spelltb (" +
                    "id INT PRIMARY KEY, " +
                    "name VARCHAR(100) UNIQUE NOT NULL, " +
                    "description VARCHAR(1024) DEFAULT '', " +
                    "school VARCHAR(50) DEFAULT 'ARCANE', " +
                    "level INT DEFAULT 1, " +
                    "casting_time DOUBLE DEFAULT 1.0, " +
                    "target VARCHAR(50) DEFAULT 'SELF', " +
                    "progression VARCHAR(50) DEFAULT 'NORMAL', " +
                    "effect_ids VARCHAR(500) DEFAULT '', " +
                    "traits VARCHAR(500) DEFAULT '', " +
                    "cooldown DOUBLE DEFAULT 0, " +
                    "duration DOUBLE DEFAULT 0 " +
                    ")");
            s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS school VARCHAR(50) DEFAULT 'ARCANE'");
            s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS level INT DEFAULT 1");
            s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS casting_time DOUBLE DEFAULT 1.0");
            s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS target VARCHAR(50) DEFAULT 'SELF'");
            s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS progression VARCHAR(50) DEFAULT 'NORMAL'");
            s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS effect_ids VARCHAR(500) DEFAULT ''");
            s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS traits VARCHAR(500) DEFAULT ''");
            s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS cooldown DOUBLE DEFAULT 0");
            s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS duration DOUBLE DEFAULT 0");
            s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS mp_cost INT DEFAULT 0");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create spelltb table", e);
        }

        // Character-Spell join table
        try (Connection c = TransactionManager.getConnection();
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS character_spell (" +
                    "character_id INT NOT NULL, " +
                    "spell_id INT NOT NULL, " +
                    "level INT DEFAULT 1, " +
                    "PRIMARY KEY (character_id, spell_id) " +
                    ")");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create character_spell table", e);
        }
    }

    // ========================== Spell Definition Methods ==========================

    /**
     * Remove all spell definitions (spelltb rows) so they can be cleanly
     * reloaded from YAML.  Does NOT touch character_spell (player progress).
     */
    public void clearSpellDefinitions() {
        try (Connection c = TransactionManager.getConnection();
             Statement s = c.createStatement()) {
            int deleted = s.executeUpdate("DELETE FROM spelltb");
            logger.info("Cleared {} stale spell definitions before YAML reload", deleted);
        } catch (SQLException e) {
            logger.warn("Failed to clear spell definitions: {}", e.getMessage());
        }
    }

    public boolean addSpell(String name, String description) {
        String sql = "INSERT INTO spelltb (name, description) VALUES (?, ?)";
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
     * Add a spell with full details. Uses MERGE to update if exists.
     */
    public boolean addSpellFull(Spell spell) {
        String sql = "MERGE INTO spelltb (id, name, description, school, level, casting_time, target, progression, effect_ids, traits, cooldown, duration, mp_cost) " +
                     "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, spell.getId());
            ps.setString(2, spell.getName());
            ps.setString(3, spell.getDescription());
            ps.setString(4, spell.getSchool().name());
            ps.setInt(5, spell.getLevel());
            ps.setDouble(6, spell.getBaseCastingTime());
            ps.setString(7, spell.getTarget().name());
            ps.setString(8, spell.getProgression().name());
            String effectStr = String.join(",", spell.getEffectIds());
            ps.setString(9, effectStr);
            String traitsStr = spell.getTraits().stream().map(Enum::name).collect(Collectors.joining(","));
            ps.setString(10, traitsStr);
            ps.setDouble(11, spell.getCooldown());
            ps.setDouble(12, spell.getDuration());
            ps.setInt(13, spell.getMpCost());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.warn("Failed to add spell {}: {}", spell.getName(), e.getMessage());
            return false;
        }
    }

    public Spell getSpellById(int id) {
        String sql = "SELECT id, name, description, school, level, casting_time, target, progression, effect_ids, traits, cooldown, duration, mp_cost FROM spelltb WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapSpellFromResultSet(rs);
                }
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    public Spell getSpellByName(String name) {
        String sql = "SELECT id, name, description, school, level, casting_time, target, progression, effect_ids, traits, cooldown, duration, mp_cost FROM spelltb WHERE name = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return mapSpellFromResultSet(rs);
                }
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    public List<Spell> getAllSpells() {
        List<Spell> spells = new ArrayList<>();
        String sql = "SELECT id, name, description, school, level, casting_time, target, progression, effect_ids, traits, cooldown, duration, mp_cost FROM spelltb ORDER BY school, level, name";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                spells.add(mapSpellFromResultSet(rs));
            }
        } catch (SQLException e) {
            // Return empty list on error
        }
        return spells;
    }

    public List<Spell> getSpellsBySchool(Spell.SpellSchool school) {
        List<Spell> spells = new ArrayList<>();
        String sql = "SELECT id, name, description, school, level, casting_time, target, progression, effect_ids, traits, cooldown, duration, mp_cost FROM spelltb WHERE school = ? ORDER BY level, name";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, school.name());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    spells.add(mapSpellFromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            // Return empty list on error
        }
        return spells;
    }

    private Spell mapSpellFromResultSet(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        String name = rs.getString("name");
        String description = rs.getString("description");
        String schoolStr = rs.getString("school");
        int level = rs.getInt("level");
        double castingTime = rs.getDouble("casting_time");
        String targetStr = rs.getString("target");
        String progressionStr = rs.getString("progression");
        String effectStr = rs.getString("effect_ids");
        String traitsStr = rs.getString("traits");
        double cooldown = rs.getDouble("cooldown");
        double duration = rs.getDouble("duration");
        int mpCost = 0;
        try { mpCost = rs.getInt("mp_cost"); } catch (SQLException ignored) {}
        String incantation = null;
        try { incantation = rs.getString("incantation"); } catch (SQLException ignored) {}

        Spell.SpellSchool school = Spell.SpellSchool.fromString(schoolStr);
        Spell.SpellTarget target = Spell.SpellTarget.fromString(targetStr);
        SkillProgression progression = SkillProgression.fromString(progressionStr);

        List<String> effectIds = new ArrayList<>();
        if (effectStr != null && !effectStr.isEmpty()) {
            for (String e : effectStr.split(",")) {
                String trimmed = e.trim();
                if (!trimmed.isEmpty()) effectIds.add(trimmed);
            }
        }

        List<SpellTrait> traits = new ArrayList<>();
        if (traitsStr != null && !traitsStr.isEmpty()) {
            for (String t : traitsStr.split(",")) {
                SpellTrait trait = SpellTrait.fromString(t.trim());
                if (trait != null) traits.add(trait);
            }
        }

        return new Spell(id, name, description, school, level, castingTime, target, effectIds, progression, traits, cooldown, duration, mpCost, incantation);
    }

    // ========================== Character-Spell Methods ==========================

    public boolean setCharacterSpellLevel(int characterId, int spellId, int level) {
        String sql = "MERGE INTO character_spell (character_id, spell_id, level) KEY (character_id, spell_id) VALUES (?, ?, ?)";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setInt(2, spellId);
            ps.setInt(3, level);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public CharacterSpell getCharacterSpell(int characterId, int spellId) {
        String sql = "SELECT character_id, spell_id, level FROM character_spell WHERE character_id = ? AND spell_id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setInt(2, spellId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new CharacterSpell(rs.getInt("character_id"), rs.getInt("spell_id"), rs.getInt("level"));
                }
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    /**
     * Learn a new spell (starts at 1% proficiency).
     * If already known, does nothing and returns false.
     */
    public boolean learnSpell(int characterId, int spellId) {
        if (getCharacterSpell(characterId, spellId) != null) {
            return false;
        }
        return setCharacterSpellLevel(characterId, spellId, CharacterSpell.MIN_PROFICIENCY);
    }

    /**
     * Learn a new spell with a specific starting proficiency based on spell definition.
     * INSTANT progression spells start at 100% (mastered).
     * If already known, does nothing and returns false.
     */
    public boolean learnSpell(int characterId, int spellId, Spell spellDef) {
        if (getCharacterSpell(characterId, spellId) != null) {
            return false;
        }
        int startingProficiency = spellDef != null
            ? spellDef.getProgression().getStartingProficiency()
            : CharacterSpell.MIN_PROFICIENCY;
        return setCharacterSpellLevel(characterId, spellId, startingProficiency);
    }

    /**
     * Check if a character knows a spell.
     */
    public boolean hasSpell(int characterId, int spellId) {
        return getCharacterSpell(characterId, spellId) != null;
    }

    /**
     * Get all spells learned by a character.
     */
    public List<CharacterSpell> getAllCharacterSpells(int characterId) {
        List<CharacterSpell> spells = new ArrayList<>();
        String sql = "SELECT character_id, spell_id, level FROM character_spell WHERE character_id = ? ORDER BY spell_id";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    spells.add(new CharacterSpell(
                        rs.getInt("character_id"),
                        rs.getInt("spell_id"),
                        rs.getInt("level")
                    ));
                }
            }
        } catch (SQLException e) {
            // Return empty list on error
        }
        return spells;
    }

    /**
     * Increase spell proficiency by a given amount.
     * Returns the new proficiency level, or -1 on error.
     */
    public int increaseSpellProficiency(int characterId, int spellId, int amount) {
        CharacterSpell spell = getCharacterSpell(characterId, spellId);
        if (spell == null) return -1;

        int newProficiency = Math.min(CharacterSpell.MAX_PROFICIENCY, spell.getProficiency() + amount);
        if (setCharacterSpellLevel(characterId, spellId, newProficiency)) {
            return newProficiency;
        }
        return -1;
    }

    /**
     * Try to improve a spell based on its progression curve.
     * Returns true if proficiency increased, false otherwise.
     */
    public boolean tryImproveSpell(int characterId, int spellId, Spell spellDef) {
        CharacterSpell charSpell = getCharacterSpell(characterId, spellId);
        if (charSpell == null || charSpell.isMastered()) return false;

        int gainChance = spellDef.getProgression().getGainChance(charSpell.getProficiency());
        int roll = ThreadLocalRandom.current().nextInt(1, 101);

        if (roll <= gainChance) {
            increaseSpellProficiency(characterId, spellId, 1);
            return true;
        }
        return false;
    }
}
