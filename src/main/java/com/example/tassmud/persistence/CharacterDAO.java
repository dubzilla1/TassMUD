package com.example.tassmud.persistence;

import com.example.tassmud.model.Area;
import com.example.tassmud.model.ArmorCategory;
import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.CharacterSpell;
import com.example.tassmud.model.EquipmentSlot;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Room;
import com.example.tassmud.model.SectorType;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.Modifier;
import com.example.tassmud.model.Stat;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.model.SkillTrait;
import com.example.tassmud.model.Spell;
import com.example.tassmud.model.SpellTrait;
import com.example.tassmud.util.PasswordUtil;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CharacterDAO {

    private static final Logger logger = LoggerFactory.getLogger(CharacterDAO.class);

        // --- Skill DAO ---
        public boolean addSkill(String name, String description) {
            String sql = "INSERT INTO skilltb (name, description) VALUES (?, ?)";
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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
                                    boolean isPassive, int maxLevel, Skill.SkillProgression progression,
                                    java.util.List<SkillTrait> traits, double cooldown,
                                    double duration, java.util.List<String> effectIds) {
            String sql = "MERGE INTO skilltb (id, skill_key, name, description, is_passive, max_level, progression, traits, cooldown, duration, effect_ids) " +
                         "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            String traitsStr = traits != null ? traits.stream().map(Enum::name).collect(java.util.stream.Collectors.joining(",")) : "";
            String effectStr = effectIds != null ? String.join(",", effectIds) : "";
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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
        public java.util.List<Skill> getAllSkills() {
            java.util.List<Skill> skills = new java.util.ArrayList<>();
            String sql = "SELECT id, skill_key, name, description, progression, traits, cooldown, duration, effect_ids FROM skilltb ORDER BY name";
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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
            
            Skill.SkillProgression progression = Skill.SkillProgression.fromString(progressionStr);
            
            // Parse traits from comma-separated string
            java.util.List<SkillTrait> traits = new java.util.ArrayList<>();
            if (traitsStr != null && !traitsStr.isEmpty()) {
                for (String t : traitsStr.split(",")) {
                    SkillTrait trait = SkillTrait.fromString(t.trim());
                    if (trait != null) traits.add(trait);
                }
            }
            
            // Parse effect IDs from comma-separated string
            java.util.List<String> effectIds = new java.util.ArrayList<>();
            if (effectIdsStr != null && !effectIdsStr.isEmpty()) {
                for (String e : effectIdsStr.split(",")) {
                    String trimmed = e.trim();
                    if (!trimmed.isEmpty()) effectIds.add(trimmed);
                }
            }
            
            return new Skill(id, name, description, progression, traits, cooldown, duration, effectIds);
        }

        // --- Spell DAO ---
        public boolean addSpell(String name, String description) {
            String sql = "INSERT INTO spelltb (name, description) VALUES (?, ?)";
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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
            String sql = "MERGE INTO spelltb (id, name, description, school, level, casting_time, target, progression, effect_ids, traits, cooldown, duration) " +
                         "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setInt(1, spell.getId());
                ps.setString(2, spell.getName());
                ps.setString(3, spell.getDescription());
                ps.setString(4, spell.getSchool().name());
                ps.setInt(5, spell.getLevel());
                ps.setDouble(6, spell.getBaseCastingTime());
                ps.setString(7, spell.getTarget().name());
                ps.setString(8, spell.getProgression().name());
                // Store effect IDs as comma-separated string
                String effectStr = String.join(",", spell.getEffectIds());
                ps.setString(9, effectStr);
                // Store traits as comma-separated string
                String traitsStr = spell.getTraits().stream().map(Enum::name).collect(java.util.stream.Collectors.joining(","));
                ps.setString(10, traitsStr);
                ps.setDouble(11, spell.getCooldown());
                ps.setDouble(12, spell.getDuration());
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                logger.warn("Failed to add spell {}: {}", spell.getName(), e.getMessage());
                return false;
            }
        }

        public Spell getSpellById(int id) {
            String sql = "SELECT id, name, description, school, level, casting_time, target, progression, effect_ids, traits, cooldown, duration FROM spelltb WHERE id = ?";
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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
            String sql = "SELECT id, name, description, school, level, casting_time, target, progression, effect_ids, traits, cooldown, duration FROM spelltb WHERE name = ?";
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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
        
        public java.util.List<Spell> getAllSpells() {
            java.util.List<Spell> spells = new java.util.ArrayList<>();
            String sql = "SELECT id, name, description, school, level, casting_time, target, progression, effect_ids, traits, cooldown, duration FROM spelltb ORDER BY school, level, name";
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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
        
        public java.util.List<Spell> getSpellsBySchool(Spell.SpellSchool school) {
            java.util.List<Spell> spells = new java.util.ArrayList<>();
            String sql = "SELECT id, name, description, school, level, casting_time, target, progression, effect_ids, traits, cooldown, duration FROM spelltb WHERE school = ? ORDER BY level, name";
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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
            
            Spell.SpellSchool school = Spell.SpellSchool.fromString(schoolStr);
            Spell.SpellTarget target = Spell.SpellTarget.fromString(targetStr);
            Skill.SkillProgression progression = Skill.SkillProgression.fromString(progressionStr);
            
            java.util.List<String> effectIds = new java.util.ArrayList<>();
            if (effectStr != null && !effectStr.isEmpty()) {
                for (String e : effectStr.split(",")) {
                    String trimmed = e.trim();
                    if (!trimmed.isEmpty()) effectIds.add(trimmed);
                }
            }
            
            // Parse traits from comma-separated string
            java.util.List<SpellTrait> traits = new java.util.ArrayList<>();
            if (traitsStr != null && !traitsStr.isEmpty()) {
                for (String t : traitsStr.split(",")) {
                    SpellTrait trait = SpellTrait.fromString(t.trim());
                    if (trait != null) traits.add(trait);
                }
            }
            
            return new Spell(id, name, description, school, level, castingTime, target, effectIds, progression, traits, cooldown, duration);
        }

        // --- CharacterSkill DAO ---
        public boolean setCharacterSkillLevel(int characterId, int skillId, int level) {
            String sql = "MERGE INTO character_skill (character_id, skill_id, level) KEY (character_id, skill_id) VALUES (?, ?, ?)";
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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
         * Learn a new skill (starts at 1% proficiency, or 100% for INSTANT skills).
         * If already known, does nothing and returns false.
         */
        public boolean learnSkill(int characterId, int skillId) {
            // Check if already known
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
            // Check if already known
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
        public java.util.List<CharacterSkill> getAllCharacterSkills(int characterId) {
            java.util.List<CharacterSkill> skills = new java.util.ArrayList<>();
            String sql = "SELECT character_id, skill_id, level FROM character_skill WHERE character_id = ? ORDER BY skill_id";
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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
            int roll = (int) (Math.random() * 100) + 1;
            
            if (roll <= gainChance) {
                increaseSkillProficiency(characterId, skillId, 1);
                return true;
            }
            return false;
        }

        // --- CharacterSpell DAO ---
        public boolean setCharacterSpellLevel(int characterId, int spellId, int level) {
            String sql = "MERGE INTO character_spell (character_id, spell_id, level) KEY (character_id, spell_id) VALUES (?, ?, ?)";
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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
         * Learn a new spell (starts at 1% proficiency, or 100% for INSTANT spells).
         * If already known, does nothing and returns false.
         */
        public boolean learnSpell(int characterId, int spellId) {
            // Check if already known
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
            // Check if already known
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
        public java.util.List<CharacterSpell> getAllCharacterSpells(int characterId) {
            java.util.List<CharacterSpell> spells = new java.util.ArrayList<>();
            String sql = "SELECT character_id, spell_id, level FROM character_spell WHERE character_id = ? ORDER BY spell_id";
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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
            int roll = (int) (Math.random() * 100) + 1;
            
            if (roll <= gainChance) {
                increaseSpellProficiency(characterId, spellId, 1);
                return true;
            }
            return false;
        }
        
    private static final String URL = System.getProperty("tassmud.db.url", "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1");
    private static final String USER = "sa";
    private static final String PASS = "";

    public CharacterDAO() {
        // Ensure character-related tables/migrations run only once per JVM startup
        MigrationManager.ensureMigration("CharacterDAO", this::ensureTable);
    }

    public void ensureTable() {
                // Skills table
                try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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
                    // Migration: add columns for existing databases
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

                // Spells table
                try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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
                    // Migration: add columns for existing databases
                    s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS school VARCHAR(50) DEFAULT 'ARCANE'");
                    s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS level INT DEFAULT 1");
                    s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS casting_time DOUBLE DEFAULT 1.0");
                    s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS target VARCHAR(50) DEFAULT 'SELF'");
                    s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS progression VARCHAR(50) DEFAULT 'NORMAL'");
                    s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS effect_ids VARCHAR(500) DEFAULT ''");
                    s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS traits VARCHAR(500) DEFAULT ''");
                    s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS cooldown DOUBLE DEFAULT 0");
                    s.execute("ALTER TABLE spelltb ADD COLUMN IF NOT EXISTS duration DOUBLE DEFAULT 0");
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to create spelltb table", e);
                }

                // Characters table must exist before join tables that reference it
                try (Connection c = DriverManager.getConnection(URL, USER, PASS);
                     Statement s = c.createStatement()) {
                    s.execute("CREATE TABLE IF NOT EXISTS characters (" +
                        "id IDENTITY PRIMARY KEY, " +
                        "name VARCHAR(100) UNIQUE NOT NULL, " +
                        "password_hash VARCHAR(512) NOT NULL, " +
                        "salt VARCHAR(512) NOT NULL, " +
                        "age INT DEFAULT 0, " +
                        "description VARCHAR(1024) DEFAULT '', " +
                        "hp_max INT DEFAULT 100, " +
                        "hp_cur INT DEFAULT 100, " +
                        "mp_max INT DEFAULT 50, " +
                        "mp_cur INT DEFAULT 50, " +
                        "mv_max INT DEFAULT 100, " +
                        "mv_cur INT DEFAULT 100, " +
                        "str INT DEFAULT 10, " +
                        "dex INT DEFAULT 10, " +
                        "con INT DEFAULT 10, " +
                        "intel INT DEFAULT 10, " +
                        "wis INT DEFAULT 10, " +
                        "cha INT DEFAULT 10, " +
                        "armor INT DEFAULT 10, " +
                        "fortitude INT DEFAULT 10, " +
                        "reflex INT DEFAULT 10, " +
                        "will INT DEFAULT 10, " +
                        "current_room INT, " +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP) ");

                    // For backwards-compatibility, add any missing columns (H2 supports IF NOT EXISTS)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS age INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS description VARCHAR(1024) DEFAULT ''");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS hp_max INT DEFAULT 100");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS hp_cur INT DEFAULT 100");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS mp_max INT DEFAULT 50");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS mp_cur INT DEFAULT 50");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS mv_max INT DEFAULT 100");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS mv_cur INT DEFAULT 100");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS str INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS dex INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS con INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS intel INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS wis INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS cha INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS armor INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS fortitude INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS reflex INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS will INT DEFAULT 10");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS current_room INT");
                    // Equipment bonus columns (persisted from equipped items)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS armor_equip_bonus INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS fortitude_equip_bonus INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS reflex_equip_bonus INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS will_equip_bonus INT DEFAULT 0");
                    // Current class reference (denormalized for convenience)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS current_class_id INT DEFAULT NULL");
                    // Autoflee threshold (0-100, defaults to 0)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS autoflee INT DEFAULT 0");
                    // Talent points for training abilities/skills/spells
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS talent_points INT DEFAULT 0");
                    // Trained ability score bonuses (added via talent points)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS trained_str INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS trained_dex INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS trained_con INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS trained_int INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS trained_wis INT DEFAULT 0");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS trained_cha INT DEFAULT 0");
                    // Gold pieces (currency)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS gold_pieces BIGINT DEFAULT 0");
                    // Autoloot and autogold flags (default true for convenience)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS autoloot BOOLEAN DEFAULT TRUE");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS autogold BOOLEAN DEFAULT TRUE");
                    // Autosac flag (auto-sacrifice empty corpses, default false - requires autoloot+autogold)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS autosac BOOLEAN DEFAULT FALSE");
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS autojunk BOOLEAN DEFAULT FALSE");
                    // Autoassist flag (auto-assist group members in combat, default true)
                    s.execute("ALTER TABLE characters ADD COLUMN IF NOT EXISTS autoassist BOOLEAN DEFAULT TRUE");
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to create characters table", e);
                }

                // Character-Skill join table
                try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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

                // Character-Spell join table (tracks which spells a character knows and at what level)
                try (Connection c = DriverManager.getConnection(URL, USER, PASS);
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

                // Area table
                try (Connection c = DriverManager.getConnection(URL, USER, PASS);
                     Statement s = c.createStatement()) {
                    s.execute("CREATE TABLE IF NOT EXISTS area (" +
                            "id IDENTITY PRIMARY KEY, " +
                            "name VARCHAR(200) UNIQUE NOT NULL, " +
                            "description VARCHAR(2048) DEFAULT '' " +
                            ")");
                    // Migration: add sector_type column for movement costs
                    s.execute("ALTER TABLE area ADD COLUMN IF NOT EXISTS sector_type VARCHAR(50) DEFAULT 'FIELD'");
                    logger.debug("Migration: ensured column area.sector_type");
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to create area table", e);
                }

                // Room table with explicit exits (nullable room IDs)
                try (Connection c = DriverManager.getConnection(URL, USER, PASS);
                     Statement s = c.createStatement()) {
                    s.execute("CREATE TABLE IF NOT EXISTS room (" +
                            "id IDENTITY PRIMARY KEY, " +
                            "area_id INT NOT NULL, " +
                            "name VARCHAR(200) NOT NULL, " +
                            "short_desc VARCHAR(512) DEFAULT '', " +
                            "long_desc VARCHAR(2048) DEFAULT '', " +
                            "exit_n INT, " +
                            "exit_e INT, " +
                            "exit_s INT, " +
                            "exit_w INT, " +
                            "exit_u INT, " +
                            "exit_d INT " +
                            ")");
                    // Add missing columns if needed (migrations)
                    s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS area_id INT NOT NULL");
                    s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS name VARCHAR(200) NOT NULL");
                    s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS short_desc VARCHAR(512) DEFAULT ''");
                    s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS long_desc VARCHAR(2048) DEFAULT ''");
                    s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS exit_n INT");
                    s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS exit_e INT");
                    s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS exit_s INT");
                    s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS exit_w INT");
                    s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS exit_u INT");
                    s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS exit_d INT");
                    // Migration: add move_cost column for room-specific movement cost override
                    s.execute("ALTER TABLE room ADD COLUMN IF NOT EXISTS move_cost INT");
                    logger.debug("Migration: ensured column room.move_cost");
                    // Door table for exit metadata (open/closed/locked/hidden/blocked)
                    s.execute("CREATE TABLE IF NOT EXISTS door (" +
                              "from_room_id INT NOT NULL, " +
                              "direction VARCHAR(16) NOT NULL, " +
                              "to_room_id INT, " +
                              "state VARCHAR(32) DEFAULT 'OPEN', " +
                              "locked BOOLEAN DEFAULT FALSE, " +
                              "hidden BOOLEAN DEFAULT FALSE, " +
                              "blocked BOOLEAN DEFAULT FALSE, " +
                              "key_item_id INT, " +
                              "description VARCHAR(2048) DEFAULT '', " +
                              "PRIMARY KEY (from_room_id, direction) " +
                              ")");
                    // Add convenience migration columns if missing
                    s.execute("ALTER TABLE door ADD COLUMN IF NOT EXISTS state VARCHAR(32) DEFAULT 'OPEN'");
                    s.execute("ALTER TABLE door ADD COLUMN IF NOT EXISTS locked BOOLEAN DEFAULT FALSE");
                    s.execute("ALTER TABLE door ADD COLUMN IF NOT EXISTS hidden BOOLEAN DEFAULT FALSE");
                    s.execute("ALTER TABLE door ADD COLUMN IF NOT EXISTS blocked BOOLEAN DEFAULT FALSE");
                    s.execute("ALTER TABLE door ADD COLUMN IF NOT EXISTS description VARCHAR(2048) DEFAULT ''");
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to create room table", e);
                }
        

        // Key/value settings table for game-wide state (e.g., current date)
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS settings (k VARCHAR(200) PRIMARY KEY, v VARCHAR(2000))");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create settings table", e);
        }

        // Room extras table: key/value textual extras for rooms (e.g., plaques, signs)
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS room_extra (room_id INT NOT NULL, k VARCHAR(200) NOT NULL, v VARCHAR(2048), PRIMARY KEY(room_id, k))");
            s.execute("ALTER TABLE room_extra ADD COLUMN IF NOT EXISTS v VARCHAR(2048)");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create room_extra table", e);
        }

        // Per-character flag table (expandable key/value toggles)
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS character_flag (" +
                    "character_id INT NOT NULL, " +
                    "k VARCHAR(200) NOT NULL, " +
                    "v VARCHAR(2000), " +
                    "PRIMARY KEY (character_id, k)" +
                    ")");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create character_flag table", e);
        }

        // Per-character equipment table (maps slot_id -> item_instance_id)
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS character_equipment (" +
                    "character_id INT NOT NULL, " +
                    "slot_id INT NOT NULL, " +
                    "item_instance_id BIGINT NULL, " +
                    "PRIMARY KEY (character_id, slot_id)" +
                    ")");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create character_equipment table", e);
        }

        // Character modifiers (stat-affecting temporary/permanent effects)
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS character_modifier (" +
                    "id VARCHAR(36) PRIMARY KEY, " +
                    "character_id INT NOT NULL, " +
                    "source VARCHAR(200), " +
                    "stat VARCHAR(50) NOT NULL, " +
                    "op VARCHAR(20) NOT NULL, " +
                    "val DOUBLE NOT NULL, " +
                    "expires_at BIGINT DEFAULT 0, " +
                    "priority INT DEFAULT 0 " +
                    ")");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create character_modifier table", e);
        }

        // Room flags table (maps room_id -> flag key)
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS room_flag (" +
                    "room_id INT NOT NULL, " +
                    "flag VARCHAR(50) NOT NULL, " +
                    "PRIMARY KEY (room_id, flag)" +
                    ")");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create room_flag table", e);
        }
    }

    // Simple settings accessor
    public String getSetting(String key) {
        String sql = "SELECT v FROM settings WHERE k = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("v");
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    // --- Character flag accessors ---
    public boolean setCharacterFlag(int characterId, String key, String value) {
        String sql = "MERGE INTO character_flag (character_id, k, v) KEY (character_id, k) VALUES (?, ?, ?)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, key);
            ps.setString(3, value == null ? "" : value);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    // --- Equipment accessors ---
    public boolean setCharacterEquipment(int characterId, int slotId, Long itemInstanceId) {
        String sql = "MERGE INTO character_equipment (character_id, slot_id, item_instance_id) KEY (character_id, slot_id) VALUES (?, ?, ?)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setInt(2, slotId);
            if (itemInstanceId == null) ps.setNull(3, Types.BIGINT); else ps.setLong(3, itemInstanceId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public Long getCharacterEquipment(int characterId, int slotId) {
        String sql = "SELECT item_instance_id FROM character_equipment WHERE character_id = ? AND slot_id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setInt(2, slotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getObject("item_instance_id") == null ? null : rs.getLong("item_instance_id");
                }
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    public java.util.Map<Integer, Long> getEquipmentMapByCharacterId(int characterId) {
        java.util.Map<Integer, Long> map = new java.util.HashMap<>();
        String sql = "SELECT slot_id, item_instance_id FROM character_equipment WHERE character_id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int slot = rs.getInt("slot_id");
                    Long iid = rs.getObject("item_instance_id") == null ? null : rs.getLong("item_instance_id");
                    map.put(slot, iid);
                }
            }
        } catch (SQLException e) {
            return map;
        }
        return map;
    }

    public boolean setCharacterEquipmentByName(String name, int slotId, Long itemInstanceId) {
        Integer id = getCharacterIdByName(name);
        if (id == null) return false;
        return setCharacterEquipment(id, slotId, itemInstanceId);
    }

    public java.util.Map<Integer, Long> getEquipmentMapByName(String name) {
        Integer id = getCharacterIdByName(name);
        if (id == null) return java.util.Collections.emptyMap();
        return getEquipmentMapByCharacterId(id);
    }

    /**
     * Clear all equipment from a character (unequip all slots).
     * Used for player death to move equipment to corpse.
     */
    public void clearAllEquipment(int characterId) {
        String sql = "DELETE FROM character_equipment WHERE character_id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Failed to clear equipment: {}", e.getMessage());
        }
    }

    /**
     * Check if a character has a shield equipped in their off-hand.
     * This is used for skills with the SHIELD trait (e.g., Bash).
     * @param characterId The character to check
     * @param itemDao ItemDAO instance for looking up item templates
     * @return true if the character has a shield equipped in off-hand
     */
    public boolean hasShield(int characterId, ItemDAO itemDao) {
        Long offHandInstanceId = getCharacterEquipment(characterId, EquipmentSlot.OFF_HAND.id);
        if (offHandInstanceId == null) return false;
        
        ItemInstance instance = itemDao.getInstance(offHandInstanceId);
        if (instance == null) return false;
        
        ItemTemplate template = itemDao.getTemplateById(instance.templateId);
        if (template == null) return false;
        
        // Must be explicitly a shield type, not just any item in off-hand
        // This handles two-handed weapons which occupy both hands but are NOT shields
        if (!template.isShield()) return false;
        
        // Extra safety: weapons are never shields (handles edge case where type might be misconfigured)
        if (template.isWeapon()) return false;
        
        return true;
    }

    /**
     * Check if a character has a shield equipped in their off-hand (by name).
     * @param name The character name
     * @param itemDao ItemDAO instance for looking up item templates
     * @return true if the character has a shield equipped in off-hand
     */
    public boolean hasShieldByName(String name, ItemDAO itemDao) {
        Integer id = getCharacterIdByName(name);
        if (id == null) return false;
        return hasShield(id, itemDao);
    }

    // Recalculate equipment bonuses by averaging stats across all equipment slots and persist to DB
    // Empty slots and weapons count as 0 in the average (allows future armor-on-weapons and shields)
    // Armor bonuses are scaled by proficiency: effectiveness = 50% + proficiency%
    public boolean recalculateEquipmentBonuses(int characterId, ItemDAO itemDao) {
        java.util.Map<Integer, Long> equipped = getEquipmentMapByCharacterId(characterId);
        
        // Total number of equipment slots (all slots count in the average, empty = 0)
        int totalSlots = EquipmentSlot.values().length;
        
        // Sum up bonuses from all equipped items
        int armorSum = 0, fortSum = 0, reflexSum = 0, willSum = 0;
        
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            Long instanceId = equipped.get(slot.getId());
            if (instanceId == null) continue; // Empty slot counts as 0
            
            ItemInstance inst = itemDao.getInstance(instanceId);
            if (inst == null) continue;
            ItemTemplate tmpl = itemDao.getTemplateById(inst.templateId);
            if (tmpl == null) continue;
            
            // Use effective values (instance overrides if present, otherwise template)
            int baseArmorBonus = inst.getEffectiveArmorSave(tmpl);
            int baseFortBonus = inst.getEffectiveFortSave(tmpl);
            int baseRefBonus = inst.getEffectiveRefSave(tmpl);
            int baseWillBonus = inst.getEffectiveWillSave(tmpl);
            
            // Scale armor bonus by proficiency if this is armor
            int effectiveArmorBonus = baseArmorBonus;
            if (tmpl.isArmor() && baseArmorBonus != 0) {
                ArmorCategory armorCat = tmpl.getArmorCategory();
                if (armorCat != null) {
                    // Look up character's proficiency in this armor category
                    double effectiveness = 0.50; // Base 50% with no proficiency
                    Skill armorSkill = getSkillByKey(armorCat.getSkillKey());
                    if (armorSkill != null) {
                        CharacterSkill charSkill = getCharacterSkill(characterId, armorSkill.getId());
                        if (charSkill != null) {
                            // Effectiveness = 50% + proficiency% (e.g., 25% prof = 75% effectiveness)
                            effectiveness = 0.50 + (charSkill.getProficiency() / 100.0);
                        }
                    }
                    // Apply effectiveness: round to nearest integer
                    effectiveArmorBonus = (int) Math.round(baseArmorBonus * effectiveness);
                }
            }
            
            armorSum += effectiveArmorBonus;
            fortSum += baseFortBonus;
            reflexSum += baseRefBonus;
            willSum += baseWillBonus;
        }
        
        // Calculate averages (round to nearest integer)
        int armorBonus = Math.round((float) armorSum / totalSlots);
        int fortBonus = Math.round((float) fortSum / totalSlots);
        int reflexBonus = Math.round((float) reflexSum / totalSlots);
        int willBonus = Math.round((float) willSum / totalSlots);
        
        String sql = "UPDATE characters SET armor_equip_bonus = ?, fortitude_equip_bonus = ?, reflex_equip_bonus = ?, will_equip_bonus = ? WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, armorBonus);
            ps.setInt(2, fortBonus);
            ps.setInt(3, reflexBonus);
            ps.setInt(4, willBonus);
            ps.setInt(5, characterId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean recalculateEquipmentBonusesByName(String name, ItemDAO itemDao) {
        Integer id = getCharacterIdByName(name);
        if (id == null) return false;
        return recalculateEquipmentBonuses(id, itemDao);
    }

    /**
     * Update a character's current class ID on the characters table.
     */
    public boolean updateCharacterClass(int characterId, Integer classId) {
        String sql = "UPDATE characters SET current_class_id = ? WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (classId == null) ps.setNull(1, Types.INTEGER); else ps.setInt(1, classId);
            ps.setInt(2, characterId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public boolean updateCharacterClassByName(String name, Integer classId) {
        Integer id = getCharacterIdByName(name);
        if (id == null) return false;
        return updateCharacterClass(id, classId);
    }

    public String getCharacterFlag(int characterId, String key) {
        String sql = "SELECT v FROM character_flag WHERE character_id = ? AND k = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("v");
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    public boolean setCharacterFlagByName(String name, String key, String value) {
        Integer id = getCharacterIdByName(name);
        if (id == null) return false;
        return setCharacterFlag(id, key, value);
    }

    public String getCharacterFlagByName(String name, String key) {
        Integer id = getCharacterIdByName(name);
        if (id == null) return null;
        return getCharacterFlag(id, key);
    }

    // Return list of columns for a given table (name case-insensitive)
    public java.util.List<String> listTableColumns(String tableName) {
        java.util.List<String> cols = new java.util.ArrayList<>();
        String sql = "SELECT COLUMN_NAME, TYPE_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tableName == null ? "" : tableName.trim().toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString("COLUMN_NAME") + " (" + rs.getString("TYPE_NAME") + ")");
                }
            }
        } catch (SQLException e) {
            // return empty list on error
        }
        return cols;
    }

    public boolean isCharacterFlagTrueByName(String name, String key) {
        String v = getCharacterFlagByName(name, key);
        if (v == null) return false;
        // Accept a few common truthy values for flag checks so both '1' and 'true'
        // (or 'yes', 'on') work regardless of how they were set elsewhere.
        String tv = v.trim().toLowerCase();
        return tv.equals("1") || tv.equals("true") || tv.equals("yes") || tv.equals("on");
    }

    public Integer getCharacterIdByName(String name) {
        String sql = "SELECT id FROM characters WHERE name = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("id");
            }
        } catch (SQLException ignored) {}
        return null;
    }

    public boolean setSetting(String key, String value) {
        String sql = "MERGE INTO settings (k, v) KEY(k) VALUES (?, ?)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public CharacterRecord findByName(String name) {
        String sql = "SELECT name, password_hash, salt, age, description, hp_max, hp_cur, mp_max, mp_cur, mv_max, mv_cur, str, dex, con, intel, wis, cha, armor, fortitude, reflex, will, armor_equip_bonus, fortitude_equip_bonus, reflex_equip_bonus, will_equip_bonus, current_room, current_class_id, autoflee, talent_points, trained_str, trained_dex, trained_con, trained_int, trained_wis, trained_cha, gold_pieces, autoloot, autogold, autosac, autojunk, autoassist FROM characters WHERE name = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                        Integer currentRoom = rs.getObject("current_room") == null ? null : rs.getInt("current_room");
                        Integer currentClassId = rs.getObject("current_class_id") == null ? null : rs.getInt("current_class_id");
                        return new CharacterRecord(
                            rs.getString("name"),
                            rs.getString("password_hash"),
                            rs.getString("salt"),
                            rs.getInt("age"),
                            rs.getString("description"),
                            rs.getInt("hp_max"), rs.getInt("hp_cur"),
                            rs.getInt("mp_max"), rs.getInt("mp_cur"),
                            rs.getInt("mv_max"), rs.getInt("mv_cur"),
                            rs.getInt("str"), rs.getInt("dex"), rs.getInt("con"),
                            rs.getInt("intel"), rs.getInt("wis"), rs.getInt("cha"),
                            rs.getInt("armor"), rs.getInt("fortitude"), rs.getInt("reflex"), rs.getInt("will"),
                            rs.getInt("armor_equip_bonus"), rs.getInt("fortitude_equip_bonus"), rs.getInt("reflex_equip_bonus"), rs.getInt("will_equip_bonus"),
                            currentRoom,
                            currentClassId,
                            rs.getInt("autoflee"),
                            rs.getInt("talent_points"),
                            rs.getInt("trained_str"), rs.getInt("trained_dex"), rs.getInt("trained_con"),
                            rs.getInt("trained_int"), rs.getInt("trained_wis"), rs.getInt("trained_cha"),
                            rs.getLong("gold_pieces"),
                            rs.getBoolean("autoloot"), rs.getBoolean("autogold"), rs.getBoolean("autosac"), rs.getBoolean("autojunk"),
                            rs.getBoolean("autoassist")
                        );
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query character", e);
        }
        return null;
    }
    
    /**
     * Find a character by their ID.
     */
    public CharacterRecord findById(int characterId) {
        String sql = "SELECT name, password_hash, salt, age, description, hp_max, hp_cur, mp_max, mp_cur, mv_max, mv_cur, str, dex, con, intel, wis, cha, armor, fortitude, reflex, will, armor_equip_bonus, fortitude_equip_bonus, reflex_equip_bonus, will_equip_bonus, current_room, current_class_id, autoflee, talent_points, trained_str, trained_dex, trained_con, trained_int, trained_wis, trained_cha, gold_pieces, autoloot, autogold, autosac, autojunk, autoassist FROM characters WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Integer currentRoom = rs.getObject("current_room") == null ? null : rs.getInt("current_room");
                    Integer currentClassId = rs.getObject("current_class_id") == null ? null : rs.getInt("current_class_id");
                    return new CharacterRecord(
                        rs.getString("name"),
                        rs.getString("password_hash"),
                        rs.getString("salt"),
                        rs.getInt("age"),
                        rs.getString("description"),
                        rs.getInt("hp_max"), rs.getInt("hp_cur"),
                        rs.getInt("mp_max"), rs.getInt("mp_cur"),
                        rs.getInt("mv_max"), rs.getInt("mv_cur"),
                        rs.getInt("str"), rs.getInt("dex"), rs.getInt("con"),
                        rs.getInt("intel"), rs.getInt("wis"), rs.getInt("cha"),
                        rs.getInt("armor"), rs.getInt("fortitude"), rs.getInt("reflex"), rs.getInt("will"),
                        rs.getInt("armor_equip_bonus"), rs.getInt("fortitude_equip_bonus"), rs.getInt("reflex_equip_bonus"), rs.getInt("will_equip_bonus"),
                        currentRoom,
                        currentClassId,
                        rs.getInt("autoflee"),
                        rs.getInt("talent_points"),
                        rs.getInt("trained_str"), rs.getInt("trained_dex"), rs.getInt("trained_con"),
                        rs.getInt("trained_int"), rs.getInt("trained_wis"), rs.getInt("trained_cha"),
                        rs.getLong("gold_pieces"),
                        rs.getBoolean("autoloot"), rs.getBoolean("autogold"), rs.getBoolean("autosac"), rs.getBoolean("autojunk"),
                        rs.getBoolean("autoassist")
                    );
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to find character by ID: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Load all modifiers for a character.
     */
    public java.util.List<Modifier> getModifiersForCharacter(int characterId) {
        java.util.List<Modifier> out = new java.util.ArrayList<>();
        String sql = "SELECT id, source, stat, op, val, expires_at, priority FROM character_modifier WHERE character_id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                            java.util.UUID id = java.util.UUID.fromString(rs.getString("id"));
                            String source = rs.getString("source");
                            Stat stat = Stat.valueOf(rs.getString("stat"));
                            Modifier.Op op = Modifier.Op.valueOf(rs.getString("op"));
                            double value = rs.getDouble("val");
                            long expiresAt = rs.getLong("expires_at");
                            int priority = rs.getInt("priority");
                            Modifier m = new Modifier(id, source, stat, op, value, expiresAt, priority);
                        out.add(m);
                    } catch (IllegalArgumentException iae) {
                        // skip malformed rows
                    }
                }
            }
        } catch (SQLException e) {
            // return what we have on error
        }
        return out;
    }

    /**
     * Persist all modifiers for a character. Existing rows for the character are replaced.
     */
    public boolean saveModifiersForCharacter(int characterId, GameCharacter ch) {
        // Remove expired modifiers from the Character instance before saving
        java.util.List<Modifier> mods = ch.getAllModifiers();
        mods.removeIf(Modifier::isExpired);

        String deleteSql = "DELETE FROM character_modifier WHERE character_id = ?";
        String insertSql = "INSERT INTO character_modifier (id, character_id, source, stat, op, val, expires_at, priority) VALUES (?,?,?,?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement(deleteSql)) {
                del.setInt(1, characterId);
                del.executeUpdate();
            }

            try (PreparedStatement ins = c.prepareStatement(insertSql)) {
                for (Modifier m : mods) {
                    ins.setString(1, m.id().toString());
                    ins.setInt(2, characterId);
                    ins.setString(3, m.source());
                    ins.setString(4, m.stat().name());
                    ins.setString(5, m.op().name());
                    ins.setDouble(6, m.value());
                    ins.setLong(7, m.expiresAtMillis());
                    ins.setInt(8, m.priority());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            c.commit();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Persist a list of modifiers for a character. Replaces existing rows for the character.
     */
    public boolean saveModifierListForCharacter(int characterId, java.util.List<Modifier> mods) {
        if (mods == null) mods = new java.util.ArrayList<>();
        mods.removeIf(Modifier::isExpired);

        String deleteSql = "DELETE FROM character_modifier WHERE character_id = ?";
        String insertSql = "INSERT INTO character_modifier (id, character_id, source, stat, op, val, expires_at, priority) VALUES (?,?,?,?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement(deleteSql)) {
                del.setInt(1, characterId);
                del.executeUpdate();
            }

            try (PreparedStatement ins = c.prepareStatement(insertSql)) {
                for (Modifier m : mods) {
                    ins.setString(1, m.id().toString());
                    ins.setInt(2, characterId);
                    ins.setString(3, m.source());
                    ins.setString(4, m.stat().name());
                    ins.setString(5, m.op().name());
                    ins.setDouble(6, m.value());
                    ins.setLong(7, m.expiresAtMillis());
                    ins.setInt(8, m.priority());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            c.commit();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    // --- Area / Room DAO ---
    public int addArea(String name, String description) {
        String sql = "INSERT INTO area (name, description) VALUES (?, ?)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, description);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            logger.warn("[CharacterDAO] addArea (auto id) failed for name={}: {}", name, e.getMessage(), e);
            return -1;
        }
        return -1;
    }

    // Insert area with explicit id (idempotent via MERGE)
    public int addAreaWithId(int id, String name, String description) {
        return addAreaWithId(id, name, description, SectorType.FIELD);
    }
    
    // Insert area with explicit id and sector type (idempotent via MERGE)
    public int addAreaWithId(int id, String name, String description, SectorType sectorType) {
        String sql = "MERGE INTO area (id, name, description, sector_type) KEY(id) VALUES (?, ?, ?, ?)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setString(2, name);
            ps.setString(3, description == null ? "" : description);
            ps.setString(4, sectorType != null ? sectorType.name() : "FIELD");
            ps.executeUpdate();
            return id;
        } catch (SQLException e) {
            // If MERGE failed, try a direct INSERT with explicit id as a fallback
            String insertSql = "INSERT INTO area (id, name, description, sector_type) VALUES (?, ?, ?, ?)";
            try (Connection c2 = DriverManager.getConnection(URL, USER, PASS);
                 PreparedStatement psIns = c2.prepareStatement(insertSql)) {
                psIns.setInt(1, id);
                psIns.setString(2, name);
                psIns.setString(3, description == null ? "" : description);
                psIns.setString(4, sectorType != null ? sectorType.name() : "FIELD");
                psIns.executeUpdate();
                return id;
            } catch (SQLException e2) {
                // fallback: try to find by name
                String sql2 = "SELECT id FROM area WHERE name = ?";
                try (Connection c3 = DriverManager.getConnection(URL, USER, PASS);
                     PreparedStatement ps2 = c3.prepareStatement(sql2)) {
                    ps2.setString(1, name);
                    try (ResultSet rs = ps2.executeQuery()) {
                        if (rs.next()) return rs.getInt(1);
                    }
                } catch (SQLException ignored) {}
                return -1;
            }
        }
    }

    public Area getAreaById(int id) {
        String sql = "SELECT id, name, description, sector_type FROM area WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String sectorStr = rs.getString("sector_type");
                    SectorType sectorType = SectorType.fromString(sectorStr);
                    return new Area(rs.getInt("id"), rs.getString("name"), rs.getString("description"), sectorType);
                }
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }

    public int addRoom(int areaId, String name, String shortDesc, String longDesc,
                       Integer exitN, Integer exitE, Integer exitS, Integer exitW, Integer exitU, Integer exitD) {
        String sql = "INSERT INTO room (area_id, name, short_desc, long_desc, exit_n, exit_e, exit_s, exit_w, exit_u, exit_d) VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, areaId);
            ps.setString(2, name);
            ps.setString(3, shortDesc == null ? "" : shortDesc);
            ps.setString(4, longDesc == null ? "" : longDesc);
            if (exitN == null) ps.setNull(5, Types.INTEGER); else ps.setInt(5, exitN);
            if (exitE == null) ps.setNull(6, Types.INTEGER); else ps.setInt(6, exitE);
            if (exitS == null) ps.setNull(7, Types.INTEGER); else ps.setInt(7, exitS);
            if (exitW == null) ps.setNull(8, Types.INTEGER); else ps.setInt(8, exitW);
            if (exitU == null) ps.setNull(9, Types.INTEGER); else ps.setInt(9, exitU);
            if (exitD == null) ps.setNull(10, Types.INTEGER); else ps.setInt(10, exitD);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            logger.warn("[CharacterDAO] addRoom (auto id) failed for areaId={} name={}: {}", areaId, name, e.getMessage(), e);
            return -1;
        }
        return -1;
    }

    // Insert room with explicit id (idempotent via MERGE)
    public int addRoomWithId(int id, int areaId, String name, String shortDesc, String longDesc,
                             Integer exitN, Integer exitE, Integer exitS, Integer exitW, Integer exitU, Integer exitD) {
        String sql = "MERGE INTO room (id, area_id, name, short_desc, long_desc, exit_n, exit_e, exit_s, exit_w, exit_u, exit_d) KEY(id) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setInt(2, areaId);
            ps.setString(3, name);
            ps.setString(4, shortDesc == null ? "" : shortDesc);
            ps.setString(5, longDesc == null ? "" : longDesc);
            if (exitN == null) ps.setNull(6, Types.INTEGER); else ps.setInt(6, exitN);
            if (exitE == null) ps.setNull(7, Types.INTEGER); else ps.setInt(7, exitE);
            if (exitS == null) ps.setNull(8, Types.INTEGER); else ps.setInt(8, exitS);
            if (exitW == null) ps.setNull(9, Types.INTEGER); else ps.setInt(9, exitW);
            if (exitU == null) ps.setNull(10, Types.INTEGER); else ps.setInt(10, exitU);
            if (exitD == null) ps.setNull(11, Types.INTEGER); else ps.setInt(11, exitD);
            ps.executeUpdate();
            return id;
        } catch (SQLException e) {
            logger.warn("[CharacterDAO] addRoomWithId failed for id={}: {}", id, e.getMessage(), e);
            return -1;
        }
    }

    public Room getRoomById(int id) {
        String sql = "SELECT id, area_id, name, short_desc, long_desc, exit_n, exit_e, exit_s, exit_w, exit_u, exit_d, move_cost FROM room WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Integer exitN = rs.getObject("exit_n") == null ? null : rs.getInt("exit_n");
                    Integer exitE = rs.getObject("exit_e") == null ? null : rs.getInt("exit_e");
                    Integer exitS = rs.getObject("exit_s") == null ? null : rs.getInt("exit_s");
                    Integer exitW = rs.getObject("exit_w") == null ? null : rs.getInt("exit_w");
                    Integer exitU = rs.getObject("exit_u") == null ? null : rs.getInt("exit_u");
                    Integer exitD = rs.getObject("exit_d") == null ? null : rs.getInt("exit_d");
                    Integer moveCost = rs.getObject("move_cost") == null ? null : rs.getInt("move_cost");
                    return new Room(rs.getInt("id"), rs.getInt("area_id"), rs.getString("name"), rs.getString("short_desc"), rs.getString("long_desc"), exitN, exitE, exitS, exitW, exitU, exitD, moveCost);
                }
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
    }
    
    /**
     * Get the movement cost for entering a room.
     * Uses the room's custom move_cost if set, otherwise falls back to the area's sector type.
     * @return movement cost in movement points
     */
    public int getMoveCostForRoom(int roomId) {
        Room room = getRoomById(roomId);
        if (room == null) return 1; // default
        
        // If room has a custom move cost, use it
        if (room.hasCustomMoveCost()) {
            return room.getMoveCost();
        }
        
        // Otherwise, get the area's sector type cost
        Area area = getAreaById(room.getAreaId());
        if (area == null) return 1; // default
        
        return area.getMoveCost();
    }

    public boolean updateRoomExits(int roomId, Integer exitN, Integer exitE, Integer exitS, Integer exitW, Integer exitU, Integer exitD) {
        String sql = "UPDATE room SET exit_n = ?, exit_e = ?, exit_s = ?, exit_w = ?, exit_u = ?, exit_d = ? WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (exitN == null) ps.setNull(1, Types.INTEGER); else ps.setInt(1, exitN);
            if (exitE == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, exitE);
            if (exitS == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, exitS);
            if (exitW == null) ps.setNull(4, Types.INTEGER); else ps.setInt(4, exitW);
            if (exitU == null) ps.setNull(5, Types.INTEGER); else ps.setInt(5, exitU);
            if (exitD == null) ps.setNull(6, Types.INTEGER); else ps.setInt(6, exitD);
            ps.setInt(7, roomId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    // --- Door accessors ---
    /**
     * Insert or update a door record for an exit. Uses MERGE so it's idempotent.
     */
    public boolean upsertDoor(int fromRoomId, String direction, Integer toRoomId, String state, boolean locked, boolean hidden, boolean blocked, Integer keyItemId, String description) {
        String sql = "MERGE INTO door (from_room_id, direction, to_room_id, state, locked, hidden, blocked, key_item_id, description) KEY(from_room_id, direction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, fromRoomId);
            ps.setString(2, direction == null ? "" : direction.toLowerCase());
            if (toRoomId == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, toRoomId);
            ps.setString(4, state == null ? "OPEN" : state);
            ps.setBoolean(5, locked);
            ps.setBoolean(6, hidden);
            ps.setBoolean(7, blocked);
            if (keyItemId == null) ps.setNull(8, Types.INTEGER); else ps.setInt(8, keyItemId);
            if (description == null) ps.setString(9, ""); else ps.setString(9, description);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.warn("Failed to upsert door for room {} dir {}: {}", fromRoomId, direction, e.getMessage());
            return false;
        }
    }

    public java.util.List<com.example.tassmud.model.Door> getDoorsForRoom(int fromRoomId) {
        java.util.List<com.example.tassmud.model.Door> out = new java.util.ArrayList<>();
        String sql = "SELECT direction, to_room_id, state, locked, hidden, blocked, key_item_id FROM door WHERE from_room_id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, fromRoomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String dir = rs.getString("direction");
                    Integer to = rs.getObject("to_room_id") == null ? null : rs.getInt("to_room_id");
                    String state = rs.getString("state");
                    boolean locked = rs.getBoolean("locked");
                    boolean hidden = rs.getBoolean("hidden");
                    boolean blocked = rs.getBoolean("blocked");
                    Integer keyId = rs.getObject("key_item_id") == null ? null : rs.getInt("key_item_id");
                    String desc = "";
                    try { desc = rs.getString("description"); } catch (Exception ignored) {}
                    out.add(new com.example.tassmud.model.Door(fromRoomId, dir, to, state, locked, hidden, blocked, keyId, desc));
                }
            }
        } catch (SQLException e) {
            // return what we have
        }
        return out;
    }

    public com.example.tassmud.model.Door getDoor(int fromRoomId, String direction) {
        String sql = "SELECT direction, to_room_id, state, locked, hidden, blocked, key_item_id, description FROM door WHERE from_room_id = ? AND direction = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, fromRoomId);
            ps.setString(2, direction == null ? "" : direction.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Integer to = rs.getObject("to_room_id") == null ? null : rs.getInt("to_room_id");
                    String state = rs.getString("state");
                    boolean locked = rs.getBoolean("locked");
                    boolean hidden = rs.getBoolean("hidden");
                    boolean blocked = rs.getBoolean("blocked");
                    Integer keyId = rs.getObject("key_item_id") == null ? null : rs.getInt("key_item_id");
                    String desc = "";
                    try { desc = rs.getString("description"); } catch (Exception ignored) {}
                    return new com.example.tassmud.model.Door(fromRoomId, direction, to, state, locked, hidden, blocked, keyId, desc);
                }
            }
        } catch (SQLException e) {
            // ignore
        }
        return null;
    }

    // Room extras accessors
    public boolean upsertRoomExtra(int roomId, String key, String value) {
        String sql = "MERGE INTO room_extra (room_id, k, v) KEY(room_id, k) VALUES (?, ?, ?)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            ps.setString(2, key == null ? "" : key);
            ps.setString(3, value == null ? "" : value);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public java.util.Map<String,String> getRoomExtras(int roomId) {
        java.util.Map<String,String> out = new java.util.HashMap<>();
        String sql = "SELECT k, v FROM room_extra WHERE room_id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("k"), rs.getString("v"));
                }
            }
        } catch (SQLException e) {
            // ignore
        }
        return out;
    }

    public String getRoomExtra(int roomId, String key) {
        String sql = "SELECT v FROM room_extra WHERE room_id = ? AND k = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            ps.setString(2, key == null ? "" : key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("v");
            }
        } catch (SQLException e) {
            // ignore
        }
        return null;
    }

    // --- Room flag accessors ---
    
    /**
     * Add a flag to a room. Uses MERGE so it's idempotent.
     * @param roomId the room ID
     * @param flag the RoomFlag to add
     * @return true if successful
     */
    public boolean addRoomFlag(int roomId, com.example.tassmud.model.RoomFlag flag) {
        if (flag == null) return false;
        return addRoomFlag(roomId, flag.getKey());
    }
    
    /**
     * Add a flag to a room by key string. Uses MERGE so it's idempotent.
     * @param roomId the room ID
     * @param flagKey the flag key string
     * @return true if successful
     */
    public boolean addRoomFlag(int roomId, String flagKey) {
        if (flagKey == null || flagKey.trim().isEmpty()) return false;
        String sql = "MERGE INTO room_flag (room_id, flag) KEY(room_id, flag) VALUES (?, ?)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            ps.setString(2, flagKey.toLowerCase().trim());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.warn("Failed to add room flag {} to room {}: {}", flagKey, roomId, e.getMessage());
            return false;
        }
    }
    
    /**
     * Remove a flag from a room.
     * @param roomId the room ID
     * @param flag the RoomFlag to remove
     * @return true if successful
     */
    public boolean removeRoomFlag(int roomId, com.example.tassmud.model.RoomFlag flag) {
        if (flag == null) return false;
        return removeRoomFlag(roomId, flag.getKey());
    }
    
    /**
     * Remove a flag from a room by key string.
     * @param roomId the room ID
     * @param flagKey the flag key string
     * @return true if successful
     */
    public boolean removeRoomFlag(int roomId, String flagKey) {
        if (flagKey == null || flagKey.trim().isEmpty()) return false;
        String sql = "DELETE FROM room_flag WHERE room_id = ? AND flag = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            ps.setString(2, flagKey.toLowerCase().trim());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.warn("Failed to remove room flag {} from room {}: {}", flagKey, roomId, e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if a room has a specific flag.
     * @param roomId the room ID
     * @param flag the RoomFlag to check
     * @return true if the room has the flag
     */
    public boolean hasRoomFlag(int roomId, com.example.tassmud.model.RoomFlag flag) {
        if (flag == null) return false;
        return hasRoomFlag(roomId, flag.getKey());
    }
    
    /**
     * Check if a room has a specific flag by key string.
     * @param roomId the room ID
     * @param flagKey the flag key string
     * @return true if the room has the flag
     */
    public boolean hasRoomFlag(int roomId, String flagKey) {
        if (flagKey == null || flagKey.trim().isEmpty()) return false;
        String sql = "SELECT 1 FROM room_flag WHERE room_id = ? AND flag = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            ps.setString(2, flagKey.toLowerCase().trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }
    
    /**
     * Get all flags for a room.
     * @param roomId the room ID
     * @return set of RoomFlags for the room
     */
    public java.util.Set<com.example.tassmud.model.RoomFlag> getRoomFlags(int roomId) {
        java.util.Set<com.example.tassmud.model.RoomFlag> flags = java.util.EnumSet.noneOf(com.example.tassmud.model.RoomFlag.class);
        String sql = "SELECT flag FROM room_flag WHERE room_id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, roomId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString("flag");
                    com.example.tassmud.model.RoomFlag flag = com.example.tassmud.model.RoomFlag.fromKey(key);
                    if (flag != null) {
                        flags.add(flag);
                    }
                }
            }
        } catch (SQLException e) {
            // return what we have
        }
        return flags;
    }
    
    /**
     * Set all flags for a room (replaces existing flags).
     * @param roomId the room ID
     * @param flags the set of flags to apply
     * @return true if successful
     */
    public boolean setRoomFlags(int roomId, java.util.Set<com.example.tassmud.model.RoomFlag> flags) {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
            // Clear existing flags
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM room_flag WHERE room_id = ?")) {
                ps.setInt(1, roomId);
                ps.executeUpdate();
            }
            // Add new flags
            if (flags != null && !flags.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO room_flag (room_id, flag) VALUES (?, ?)")) {
                    for (com.example.tassmud.model.RoomFlag flag : flags) {
                        ps.setInt(1, roomId);
                        ps.setString(2, flag.getKey());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            return true;
        } catch (SQLException e) {
            logger.warn("Failed to set room flags for room {}: {}", roomId, e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if a room is a SAFE room (no combat allowed).
     * Convenience method for combat checks.
     */
    public boolean isRoomSafe(int roomId) {
        return hasRoomFlag(roomId, com.example.tassmud.model.RoomFlag.SAFE);
    }
    
    /**
     * Check if a room is a PRISON room (no exit except GM teleport).
     * Convenience method for movement checks.
     */
    public boolean isRoomPrison(int roomId) {
        return hasRoomFlag(roomId, com.example.tassmud.model.RoomFlag.PRISON);
    }
    
    /**
     * Check if a room has NO_MOB flag (mobs cannot enter by normal movement).
     * Convenience method for mob movement checks.
     */
    public boolean isRoomNoMob(int roomId) {
        return hasRoomFlag(roomId, com.example.tassmud.model.RoomFlag.NO_MOB);
    }
    
    /**
     * Check if a room is PRIVATE (only one non-GM PC allowed).
     * Convenience method for entry checks.
     */
    public boolean isRoomPrivate(int roomId) {
        return hasRoomFlag(roomId, com.example.tassmud.model.RoomFlag.PRIVATE);
    }
    
    /**
     * Check if a room has NO_RECALL flag.
     * Convenience method for recall checks.
     */
    public boolean isRoomNoRecall(int roomId) {
        return hasRoomFlag(roomId, com.example.tassmud.model.RoomFlag.NO_RECALL);
    }
    
    /**
     * Check if a room is DARK (requires light source).
     * Convenience method for visibility checks.
     */
    public boolean isRoomDark(int roomId) {
        return hasRoomFlag(roomId, com.example.tassmud.model.RoomFlag.DARK);
    }

    public boolean createCharacter(GameCharacter ch, String passwordHashBase64, String saltBase64) {
        String sql = "INSERT INTO characters (name, password_hash, salt, age, description, hp_max, hp_cur, mp_max, mp_cur, mv_max, mv_cur, str, dex, con, intel, wis, cha, armor, fortitude, reflex, will, current_room) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, ch.getName());
            ps.setString(2, passwordHashBase64);
            ps.setString(3, saltBase64);
            ps.setInt(4, ch.getAge());
            ps.setString(5, ch.getDescription());
            ps.setInt(6, ch.getHpMax());
            ps.setInt(7, ch.getHpCur());
            ps.setInt(8, ch.getMpMax());
            ps.setInt(9, ch.getMpCur());
            ps.setInt(10, ch.getMvMax());
            ps.setInt(11, ch.getMvCur());
            ps.setInt(12, ch.getStr());
            ps.setInt(13, ch.getDex());
            ps.setInt(14, ch.getCon());
            ps.setInt(15, ch.getIntel());
            ps.setInt(16, ch.getWis());
            ps.setInt(17, ch.getCha());
            ps.setInt(18, ch.getArmor());
            ps.setInt(19, ch.getFortitude());
            ps.setInt(20, ch.getReflex());
            ps.setInt(21, ch.getWill());
            // current_room may be null
            if (ch.getCurrentRoom() == null) ps.setNull(22, Types.INTEGER); else ps.setInt(22, ch.getCurrentRoom());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public int getAnyRoomId() {
        String sql = "SELECT id FROM room LIMIT 1";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt("id");
        } catch (SQLException ignored) {}
        return -1;
    }

    public boolean updateCharacterRoom(String name, Integer roomId) {
        String sql = "UPDATE characters SET current_room = ? WHERE name = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (roomId == null) ps.setNull(1, Types.INTEGER); else ps.setInt(1, roomId);
            ps.setString(2, name);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    // Persist mutable character state: current HP/MP/MV and room
    public boolean saveCharacterStateByName(String name, int hpCur, int mpCur, int mvCur, Integer currentRoom) {
        String sql = "UPDATE characters SET hp_cur = ?, mp_cur = ?, mv_cur = ?, current_room = ? WHERE name = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, hpCur);
            ps.setInt(2, mpCur);
            ps.setInt(3, mvCur);
            if (currentRoom == null) ps.setNull(4, Types.INTEGER); else ps.setInt(4, currentRoom);
            ps.setString(5, name);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }
    
    /**
     * Deduct movement points from a character.
     * @return true if successful, false if failed or insufficient points
     */
    public boolean deductMovementPoints(String name, int cost) {
        String sql = "UPDATE characters SET mv_cur = mv_cur - ? WHERE name = ? AND mv_cur >= ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, cost);
            ps.setString(2, name);
            ps.setInt(3, cost);
            int rows = ps.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            return false;
        }
    }
    
    /**
     * Deduct mana points from a character.
     * @return true if successful, false if failed or insufficient mana
     */
    public boolean deductManaPoints(String name, int cost) {
        String sql = "UPDATE characters SET mp_cur = mp_cur - ? WHERE name = ? AND mp_cur >= ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, cost);
            ps.setString(2, name);
            ps.setInt(3, cost);
            int rows = ps.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            return false;
        }
    }
    
    /**
     * Add to a character's max and current HP/MP/MV (used for level-up bonuses).
     * Increases both max and current by the specified amounts.
     */
    public boolean addVitals(int characterId, int hpAdd, int mpAdd, int mvAdd) {
        String sql = "UPDATE characters SET hp_max = hp_max + ?, hp_cur = hp_cur + ?, " +
                     "mp_max = mp_max + ?, mp_cur = mp_cur + ?, " +
                     "mv_max = mv_max + ?, mv_cur = mv_cur + ? WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, hpAdd);
            ps.setInt(2, hpAdd);
            ps.setInt(3, mpAdd);
            ps.setInt(4, mpAdd);
            ps.setInt(5, mvAdd);
            ps.setInt(6, mvAdd);
            ps.setInt(7, characterId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.warn("Failed to add vitals: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Restore a character to full HP/MP/MV (set current = max).
     */
    public boolean restoreVitals(int characterId) {
        String sql = "UPDATE characters SET hp_cur = hp_max, mp_cur = mp_max, mv_cur = mv_max WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.warn("Failed to restore vitals: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Set specific vitals for a character (used for death/revival).
     * @param characterId The character to update
     * @param hp The HP to set (or null to leave unchanged)
     * @param mp The MP to set (or null to leave unchanged)
     * @param mv The MV to set (or null to leave unchanged)
     */
    public boolean setVitals(int characterId, Integer hp, Integer mp, Integer mv) {
        StringBuilder sql = new StringBuilder("UPDATE characters SET ");
        List<Integer> params = new ArrayList<>();
        boolean first = true;
        
        if (hp != null) {
            sql.append("hp_cur = ?");
            params.add(hp);
            first = false;
        }
        if (mp != null) {
            if (!first) sql.append(", ");
            sql.append("mp_cur = ?");
            params.add(mp);
            first = false;
        }
        if (mv != null) {
            if (!first) sql.append(", ");
            sql.append("mv_cur = ?");
            params.add(mv);
        }
        
        if (params.isEmpty()) return true; // Nothing to update
        
        sql.append(" WHERE id = ?");
        
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Integer p : params) {
                ps.setInt(idx++, p);
            }
            ps.setInt(idx, characterId);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.warn("Failed to set vitals: {}", e.getMessage());
            return false;
        }
    }

    public boolean verifyPassword(String name, char[] password) {
        CharacterRecord rec = findByName(name);
        if (rec == null) return false;
        byte[] salt = java.util.Base64.getDecoder().decode(rec.saltBase64);
        String computed = PasswordUtil.hashPasswordBase64(password, salt);
        return PasswordUtil.constantTimeEquals(rec.passwordHashBase64, computed);
    }

    public static class CharacterRecord {
        public final String name;
        public final String passwordHashBase64;
        public final String saltBase64;
        public final int age;
        public final String description;
        public final int hpMax;
        public final int hpCur;
        public final int mpMax;
        public final int mpCur;
        public final int mvMax;
        public final int mvCur;
        public final int str;
        public final int dex;
        public final int con;
        public final int intel;
        public final int wis;
        public final int cha;
        public final int armor;
        public final int fortitude;
        public final int reflex;
        public final int will;
        // Equipment bonuses (persisted from equipped items)
        public final int armorEquipBonus;
        public final int fortitudeEquipBonus;
        public final int reflexEquipBonus;
        public final int willEquipBonus;
        public final Integer currentRoom;
        public final Integer currentClassId;
        public final int autoflee;  // Auto-flee threshold (0-100)
        // Talent points and trained ability bonuses
        public final int talentPoints;
        public final int trainedStr;
        public final int trainedDex;
        public final int trainedCon;
        public final int trainedInt;
        public final int trainedWis;
        public final int trainedCha;
        // Currency
        public final long goldPieces;
        // Auto-loot settings
        public final boolean autoloot;  // Auto-loot items from corpses
        public final boolean autogold;  // Auto-loot gold from corpses
        public final boolean autosac;   // Auto-sacrifice empty corpses (requires autoloot+autogold)
        public final boolean autojunk;
        public final boolean autoassist; // Auto-assist group members in combat

        // Convenience methods to get total saves (base + equipment)
        public int getArmorTotal() { return armor + armorEquipBonus; }
        public int getFortitudeTotal() { return fortitude + fortitudeEquipBonus; }
        public int getReflexTotal() { return reflex + reflexEquipBonus; }
        public int getWillTotal() { return will + willEquipBonus; }
        
        // Convenience methods to get total ability scores (base + trained)
        public int getStrTotal() { return str + trainedStr; }
        public int getDexTotal() { return dex + trainedDex; }
        public int getConTotal() { return con + trainedCon; }
        public int getIntTotal() { return intel + trainedInt; }
        public int getWisTotal() { return wis + trainedWis; }
        public int getChaTotal() { return cha + trainedCha; }

        public CharacterRecord(String name, String passwordHashBase64, String saltBase64,
                               int age, String description,
                               int hpMax, int hpCur,
                               int mpMax, int mpCur,
                               int mvMax, int mvCur,
                               int str, int dex, int con, int intel, int wis, int cha,
                               int armor, int fortitude, int reflex, int will,
                               int armorEquipBonus, int fortitudeEquipBonus, int reflexEquipBonus, int willEquipBonus,
                               Integer currentRoom,
                               Integer currentClassId,
                               int autoflee,
                               int talentPoints,
                               int trainedStr, int trainedDex, int trainedCon, int trainedInt, int trainedWis, int trainedCha,
                               long goldPieces,
                               boolean autoloot, boolean autogold, boolean autosac, boolean autojunk,
                               boolean autoassist) {
            this.name = name;
            this.passwordHashBase64 = passwordHashBase64;
            this.saltBase64 = saltBase64;
            this.age = age;
            this.description = description;
            this.hpMax = hpMax;
            this.hpCur = hpCur;
            this.mpMax = mpMax;
            this.mpCur = mpCur;
            this.mvMax = mvMax;
            this.mvCur = mvCur;
            this.str = str;
            this.dex = dex;
            this.con = con;
            this.intel = intel;
            this.wis = wis;
            this.cha = cha;
            this.armor = armor;
            this.fortitude = fortitude;
            this.reflex = reflex;
            this.will = will;
            this.armorEquipBonus = armorEquipBonus;
            this.fortitudeEquipBonus = fortitudeEquipBonus;
            this.reflexEquipBonus = reflexEquipBonus;
            this.willEquipBonus = willEquipBonus;
            this.currentRoom = currentRoom;
            this.currentClassId = currentClassId;
            this.autoflee = autoflee;
            this.talentPoints = talentPoints;
            this.trainedStr = trainedStr;
            this.trainedDex = trainedDex;
            this.trainedCon = trainedCon;
            this.trainedInt = trainedInt;
            this.trainedWis = trainedWis;
            this.trainedCha = trainedCha;
            this.goldPieces = goldPieces;
            this.autoloot = autoloot;
            this.autogold = autogold;
            this.autosac = autosac;
            this.autojunk = autojunk;
            this.autoassist = autoassist;
        }
    }

    /**
     * Get character name by their ID.
     */
    public String getCharacterNameById(int characterId) {
        String sql = "SELECT name FROM characters WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("name");
                }
            }
        } catch (SQLException e) {
            // ignore
        }
        return null;
    }

    /**
     * Get character record by their ID (includes current_room).
     */
    public CharacterRecord getCharacterById(int characterId) {
        String sql = "SELECT name, password_hash, salt, age, description, hp_max, hp_cur, mp_max, mp_cur, mv_max, mv_cur, str, dex, con, intel, wis, cha, armor, fortitude, reflex, will, armor_equip_bonus, fortitude_equip_bonus, reflex_equip_bonus, will_equip_bonus, current_room, current_class_id, autoflee, talent_points, trained_str, trained_dex, trained_con, trained_int, trained_wis, trained_cha, gold_pieces, autoloot, autogold, autosac, autojunk, autoassist FROM characters WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Integer currentRoom = rs.getObject("current_room") == null ? null : rs.getInt("current_room");
                    Integer currentClassId = rs.getObject("current_class_id") == null ? null : rs.getInt("current_class_id");
                    return new CharacterRecord(
                        rs.getString("name"),
                        rs.getString("password_hash"),
                        rs.getString("salt"),
                        rs.getInt("age"),
                        rs.getString("description"),
                        rs.getInt("hp_max"), rs.getInt("hp_cur"),
                        rs.getInt("mp_max"), rs.getInt("mp_cur"),
                        rs.getInt("mv_max"), rs.getInt("mv_cur"),
                        rs.getInt("str"), rs.getInt("dex"), rs.getInt("con"),
                        rs.getInt("intel"), rs.getInt("wis"), rs.getInt("cha"),
                        rs.getInt("armor"), rs.getInt("fortitude"), rs.getInt("reflex"), rs.getInt("will"),
                        rs.getInt("armor_equip_bonus"), rs.getInt("fortitude_equip_bonus"), rs.getInt("reflex_equip_bonus"), rs.getInt("will_equip_bonus"),
                        currentRoom,
                        currentClassId,
                        rs.getInt("autoflee"),
                        rs.getInt("talent_points"),
                        rs.getInt("trained_str"), rs.getInt("trained_dex"), rs.getInt("trained_con"),
                        rs.getInt("trained_int"), rs.getInt("trained_wis"), rs.getInt("trained_cha"),
                        rs.getLong("gold_pieces"),
                        rs.getBoolean("autoloot"), rs.getBoolean("autogold"), rs.getBoolean("autosac"), rs.getBoolean("autojunk"),
                        rs.getBoolean("autoassist")
                    );
                }
            }
        } catch (SQLException e) {
            // ignore
        }
        return null;
    }
    
    /**
     * Set a character's autoflee threshold.
     * @param characterId the character ID
     * @param autoflee the autoflee percentage (0-100)
     * @return true if successful
     */
    public boolean setAutoflee(int characterId, int autoflee) {
        // Clamp to valid range
        autoflee = Math.max(0, Math.min(100, autoflee));
        
        String sql = "UPDATE characters SET autoflee = ? WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, autoflee);
            ps.setInt(2, characterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warn("Failed to set autoflee: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Get a character's autoflee threshold.
     * @param characterId the character ID
     * @return the autoflee percentage (0-100), or 0 if not found
     */
    public int getAutoflee(int characterId) {
        String sql = "SELECT autoflee FROM characters WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("autoflee");
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to get autoflee: {}", e.getMessage());
        }
        return 0;
    }
    
    /**
     * Set a character's autoloot flag.
     * @param characterId the character ID
     * @param autoloot whether to automatically loot items from corpses
     * @return true if successful
     */
    public boolean setAutoloot(int characterId, boolean autoloot) {
        String sql = "UPDATE characters SET autoloot = ? WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBoolean(1, autoloot);
            ps.setInt(2, characterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warn("Failed to set autoloot: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Set a character's autogold flag.
     * @param characterId the character ID
     * @param autogold whether to automatically loot gold from corpses
     * @return true if successful
     */
    public boolean setAutogold(int characterId, boolean autogold) {
        String sql = "UPDATE characters SET autogold = ? WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBoolean(1, autogold);
            ps.setInt(2, characterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warn("Failed to set autogold: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Set a character's autosac flag.
     * @param characterId the character ID
     * @param autosac whether to automatically sacrifice empty corpses
     * @return true if successful
     */
    public boolean setAutosac(int characterId, boolean autosac) {
        String sql = "UPDATE characters SET autosac = ? WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBoolean(1, autosac);
            ps.setInt(2, characterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warn("Failed to set autosac: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Set a character's autoassist flag.
     * @param characterId the character ID
     * @param autoassist whether to automatically assist group members in combat
     * @return true if successful
     */
    public boolean setAutoassist(int characterId, boolean autoassist) {
        String sql = "UPDATE characters SET autoassist = ? WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBoolean(1, autoassist);
            ps.setInt(2, characterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warn("Failed to set autoassist: {}", e.getMessage());
            return false;
        }
    }
    
    // ===================== TALENT POINTS =====================
    
    /**
     * Get a character's current talent points.
     * @param characterId the character ID
     * @return the number of unspent talent points
     */
    public int getTalentPoints(int characterId) {
        String sql = "SELECT talent_points FROM characters WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("talent_points");
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to get talent points: {}", e.getMessage());
        }
        return 0;
    }
    
    /**
     * Set a character's talent points.
     * @param characterId the character ID
     * @param points the new talent point total
     * @return true if successful
     */
    public boolean setTalentPoints(int characterId, int points) {
        String sql = "UPDATE characters SET talent_points = ? WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(0, points));
            ps.setInt(2, characterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warn("Failed to set talent points: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Add talent points to a character (e.g., on level-up).
     * @param characterId the character ID
     * @param points the points to add
     * @return true if successful
     */
    public boolean addTalentPoints(int characterId, int points) {
        String sql = "UPDATE characters SET talent_points = talent_points + ? WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, points);
            ps.setInt(2, characterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warn("Failed to add talent points: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Get the trained bonus for an ability score.
     * @param characterId the character ID
     * @param ability the ability name (str, dex, con, int, wis, cha)
     * @return the trained bonus (0 if none or invalid ability)
     */
    public int getTrainedAbility(int characterId, String ability) {
        String column = getTrainedAbilityColumn(ability);
        if (column == null) return 0;
        
        String sql = "SELECT " + column + " FROM characters WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            logger.warn("Failed to get trained ability: {}", e.getMessage());
        }
        return 0;
    }
    
    /**
     * Set the trained bonus for an ability score.
     * @param characterId the character ID
     * @param ability the ability name (str, dex, con, int, wis, cha)
     * @param bonus the new trained bonus
     * @return true if successful
     */
    public boolean setTrainedAbility(int characterId, String ability, int bonus) {
        String column = getTrainedAbilityColumn(ability);
        if (column == null) return false;
        
        String sql = "UPDATE characters SET " + column + " = ? WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(0, bonus));
            ps.setInt(2, characterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warn("Failed to set trained ability: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Increment the trained bonus for an ability by 1.
     * @param characterId the character ID
     * @param ability the ability name (str, dex, con, int, wis, cha)
     * @return true if successful
     */
    public boolean incrementTrainedAbility(int characterId, String ability) {
        String column = getTrainedAbilityColumn(ability);
        if (column == null) return false;
        
        String sql = "UPDATE characters SET " + column + " = " + column + " + 1 WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warn("Failed to increment trained ability: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Map ability name to database column.
     */
    private String getTrainedAbilityColumn(String ability) {
        if (ability == null) return null;
        switch (ability.toLowerCase()) {
            case "str": case "strength": return "trained_str";
            case "dex": case "dexterity": return "trained_dex";
            case "con": case "constitution": return "trained_con";
            case "int": case "intel": case "intelligence": return "trained_int";
            case "wis": case "wisdom": return "trained_wis";
            case "cha": case "charisma": return "trained_cha";
            default: return null;
        }
    }
    
    /**
     * Calculate the talent point cost to train an ability from its current total to the next point.
     * Costs: 10-16 = 1 point, 17-18 = 2 points, 19-20 = 4 points, 21+ = impossible
     * @param currentTotal the current total ability score (base + trained)
     * @return the cost, or -1 if training is not possible
     */
    public static int getAbilityTrainingCost(int currentTotal) {
        if (currentTotal < 10) return 1;  // Below minimum, easy to train
        if (currentTotal <= 16) return 1;
        if (currentTotal <= 18) return 2;
        if (currentTotal <= 20) return 4;
        return -1;  // Cannot train above 20
    }

    // ========== Gold Methods ==========

    /**
     * Get the current gold pieces for a character.
     * @param characterId the character's ID
     * @return the amount of gold pieces, or 0 if not found
     */
    public long getGold(int characterId) {
        String sql = "SELECT gold_pieces FROM characters WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("gold_pieces");
                }
            }
        } catch (SQLException e) {
            logger.warn("Error getting gold for character {}: {}", characterId, e.getMessage());
        }
        return 0;
    }

    /**
     * Set the gold pieces for a character to a specific amount.
     * @param characterId the character's ID
     * @param amount the new gold amount (must be >= 0)
     * @return true if successful, false otherwise
     */
    public boolean setGold(int characterId, long amount) {
        if (amount < 0) {
            logger.warn("Attempted to set negative gold for character {}", characterId);
            return false;
        }
        String sql = "UPDATE characters SET gold_pieces = ? WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setInt(2, characterId);
            int rows = ps.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            logger.warn("Error setting gold for character {}: {}", characterId, e.getMessage());
        }
        return false;
    }

    /**
     * Add gold pieces to a character's current amount.
     * @param characterId the character's ID
     * @param amount the amount to add (can be negative to subtract)
     * @return true if successful, false otherwise
     */
    public boolean addGold(int characterId, long amount) {
        String sql = "UPDATE characters SET gold_pieces = gold_pieces + ? WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, amount);
            ps.setInt(2, characterId);
            int rows = ps.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            logger.warn("Error adding gold for character {}: {}", characterId, e.getMessage());
        }
        return false;
    }
    
    /**
     * Set a character attribute by name. Used by GM cset command.
     * Returns a result message describing success or failure.
     * 
     * Supported attributes:
     * - Vitals: hp, hpmax, mp, mpmax, mv, mvmax
     * - Base Abilities: str, dex, con, int, wis, cha
     * - Trained Abilities: trained_str, trained_dex, trained_con, trained_int, trained_wis, trained_cha
     * - Saves: armor, fortitude, reflex, will
     * - Equipment bonuses: armor_equip, fort_equip, reflex_equip, will_equip
     * - Other: age, room, class, autoflee, talents, gold, xp, level
     */
    public String setCharacterAttribute(int characterId, String attribute, String value) {
        if (attribute == null || value == null) {
            return "Attribute and value are required.";
        }
        
        String attr = attribute.toLowerCase().trim();
        
        // Handle special attributes first
        switch (attr) {
            case "xp": {
                try {
                    int xp = Integer.parseInt(value);
                    CharacterClassDAO classDAO = new CharacterClassDAO();
                    Integer classId = classDAO.getCharacterCurrentClassId(characterId);
                    if (classId == null) return "Character has no class.";
                    String sql = "UPDATE character_class_progress SET class_xp = ? WHERE character_id = ? AND class_id = ?";
                    try (Connection c = DriverManager.getConnection(URL, USER, PASS);
                         PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setInt(1, Math.max(0, xp));
                        ps.setInt(2, characterId);
                        ps.setInt(3, classId);
                        if (ps.executeUpdate() > 0) return "XP set to " + xp;
                    }
                    return "Failed to set XP.";
                } catch (Exception e) { return "Invalid XP value: " + value; }
            }
            case "level": {
                try {
                    int level = Integer.parseInt(value);
                    if (level < 1 || level > 55) return "Level must be between 1 and 55.";
                    CharacterClassDAO classDAO = new CharacterClassDAO();
                    Integer classId = classDAO.getCharacterCurrentClassId(characterId);
                    if (classId == null) return "Character has no class.";
                    String sql = "UPDATE character_class_progress SET class_level = ? WHERE character_id = ? AND class_id = ?";
                    try (Connection c = DriverManager.getConnection(URL, USER, PASS);
                         PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setInt(1, level);
                        ps.setInt(2, characterId);
                        ps.setInt(3, classId);
                        if (ps.executeUpdate() > 0) return "Level set to " + level;
                    }
                    return "Failed to set level.";
                } catch (Exception e) { return "Invalid level value: " + value; }
            }
            case "class": {
                try {
                    int classId = Integer.parseInt(value);
                    String sql = "UPDATE characters SET current_class_id = ? WHERE id = ?";
                    try (Connection c = DriverManager.getConnection(URL, USER, PASS);
                         PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setInt(1, classId);
                        ps.setInt(2, characterId);
                        if (ps.executeUpdate() > 0) return "Class ID set to " + classId;
                    }
                    return "Failed to set class.";
                } catch (Exception e) { return "Invalid class ID: " + value; }
            }
            case "description": case "desc": {
                String sql = "UPDATE characters SET description = ? WHERE id = ?";
                try (Connection c = DriverManager.getConnection(URL, USER, PASS);
                     PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, value);
                    ps.setInt(2, characterId);
                    if (ps.executeUpdate() > 0) return "Description set.";
                } catch (SQLException e) { return "Failed to set description: " + e.getMessage(); }
                return "Failed to set description.";
            }
        }
        
        // Map attribute name to column
        String column = null;
        boolean isLong = false;
        
        switch (attr) {
            // Vitals
            case "hp": case "hpcur": case "hp_cur": column = "hp_cur"; break;
            case "hpmax": case "hp_max": column = "hp_max"; break;
            case "mp": case "mpcur": case "mp_cur": column = "mp_cur"; break;
            case "mpmax": case "mp_max": column = "mp_max"; break;
            case "mv": case "mvcur": case "mv_cur": column = "mv_cur"; break;
            case "mvmax": case "mv_max": column = "mv_max"; break;
            
            // Base abilities
            case "str": case "strength": column = "str"; break;
            case "dex": case "dexterity": column = "dex"; break;
            case "con": case "constitution": column = "con"; break;
            case "int": case "intel": case "intelligence": column = "intel"; break;
            case "wis": case "wisdom": column = "wis"; break;
            case "cha": case "charisma": column = "cha"; break;
            
            // Trained abilities
            case "trained_str": case "trainedstr": case "tstr": column = "trained_str"; break;
            case "trained_dex": case "traineddex": case "tdex": column = "trained_dex"; break;
            case "trained_con": case "trainedcon": case "tcon": column = "trained_con"; break;
            case "trained_int": case "trainedint": case "tint": column = "trained_int"; break;
            case "trained_wis": case "trainedwis": case "twis": column = "trained_wis"; break;
            case "trained_cha": case "trainedcha": case "tcha": column = "trained_cha"; break;
            
            // Saves
            case "armor": case "ac": column = "armor"; break;
            case "fortitude": case "fort": column = "fortitude"; break;
            case "reflex": case "ref": column = "reflex"; break;
            case "will": column = "will"; break;
            
            // Equipment bonuses
            case "armor_equip": case "armorequip": case "ac_equip": column = "armor_equip_bonus"; break;
            case "fort_equip": case "fortequip": case "fortitude_equip": column = "fortitude_equip_bonus"; break;
            case "reflex_equip": case "reflexequip": case "ref_equip": column = "reflex_equip_bonus"; break;
            case "will_equip": case "willequip": column = "will_equip_bonus"; break;
            
            // Other
            case "age": column = "age"; break;
            case "room": case "currentroom": case "current_room": column = "current_room"; break;
            case "autoflee": column = "autoflee"; break;
            case "talents": case "talentpoints": case "talent_points": column = "talent_points"; break;
            case "gold": case "goldpieces": case "gold_pieces": column = "gold_pieces"; isLong = true; break;
            
            default:
                return "Unknown attribute: " + attribute + ". Use CSET LIST to see available attributes.";
        }
        
        // Parse and update
        String sql = "UPDATE characters SET " + column + " = ? WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (isLong) {
                long longVal = Long.parseLong(value);
                ps.setLong(1, longVal);
            } else {
                int intVal = Integer.parseInt(value);
                ps.setInt(1, intVal);
            }
            ps.setInt(2, characterId);
            if (ps.executeUpdate() > 0) {
                return attr.toUpperCase() + " set to " + value;
            }
            return "Failed to update " + attr + ".";
        } catch (NumberFormatException e) {
            return "Invalid numeric value: " + value;
        } catch (SQLException e) {
            return "Database error: " + e.getMessage();
        }
    }
    
    /**
     * Get list of all settable attributes for CSET command.
     */
    public static java.util.List<String> getSettableAttributes() {
        return java.util.Arrays.asList(
            "hp", "hpmax", "mp", "mpmax", "mv", "mvmax",
            "str", "dex", "con", "int", "wis", "cha",
            "trained_str", "trained_dex", "trained_con", "trained_int", "trained_wis", "trained_cha",
            "armor", "fortitude", "reflex", "will",
            "armor_equip", "fort_equip", "reflex_equip", "will_equip",
            "age", "room", "class", "autoflee", "talents", "gold", "xp", "level", "description"
        );
    }

    public boolean setAutojunk(int characterId, boolean autojunk) {
        String sql = "UPDATE characters SET autojunk = ? WHERE id = ?";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBoolean(1, autojunk);
            ps.setInt(2, characterId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warn("Failed to set autojunk: {}", e.getMessage());
            return false;
        }
    }

    public int getPlayerLevel(Integer characterId) {
        if (characterId == null) return 1;
        CharacterClassDAO classDAO = new CharacterClassDAO();
        
        Integer currentClassId = classDAO.getCharacterCurrentClassId(characterId);
        if (currentClassId != null) {
            return Math.max(1, classDAO.getCharacterClassLevel(characterId, currentClassId));
        }
        
        // Fallback to character base level (if any)
        return 1;
    }
}
