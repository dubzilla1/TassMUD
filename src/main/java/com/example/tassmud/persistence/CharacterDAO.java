package com.example.tassmud.persistence;

import com.example.tassmud.model.Area;
import com.example.tassmud.model.ArmorCategory;
import com.example.tassmud.model.Character;
import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.CharacterSpell;
import com.example.tassmud.model.EquipmentSlot;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Room;
import com.example.tassmud.model.Skill;
import com.example.tassmud.model.SkillTrait;
import com.example.tassmud.model.Spell;
import com.example.tassmud.model.SpellTrait;
import com.example.tassmud.util.PasswordUtil;
import java.sql.*;

public class CharacterDAO {

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
                                    java.util.List<SkillTrait> traits, double cooldown) {
            String sql = "MERGE INTO skilltb (id, skill_key, name, description, is_passive, max_level, progression, traits, cooldown) " +
                         "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            String traitsStr = traits != null ? traits.stream().map(Enum::name).collect(java.util.stream.Collectors.joining(",")) : "";
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
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                System.err.println("Failed to add skill " + id + " (" + name + "): " + e.getMessage());
                return false;
            }
        }
        
        /**
         * Get a skill by its key (e.g., "simple_weapons").
         */
        public Skill getSkillByKey(String key) {
            String sql = "SELECT id, skill_key, name, description, progression, traits, cooldown FROM skilltb WHERE skill_key = ?";
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
            String sql = "SELECT id, skill_key, name, description, progression, traits, cooldown FROM skilltb WHERE id = ?";
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
            String sql = "SELECT id, skill_key, name, description, progression, traits, cooldown FROM skilltb ORDER BY name";
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
            
            Skill.SkillProgression progression = Skill.SkillProgression.fromString(progressionStr);
            
            // Parse traits from comma-separated string
            java.util.List<SkillTrait> traits = new java.util.ArrayList<>();
            if (traitsStr != null && !traitsStr.isEmpty()) {
                for (String t : traitsStr.split(",")) {
                    SkillTrait trait = SkillTrait.fromString(t.trim());
                    if (trait != null) traits.add(trait);
                }
            }
            
            return new Skill(id, name, description, progression, traits, cooldown);
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
            String sql = "MERGE INTO spelltb (id, name, description, school, level, casting_time, target, progression, effect_ids, traits, cooldown) " +
                         "KEY (id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
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
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                System.err.println("Failed to add spell " + spell.getName() + ": " + e.getMessage());
                return false;
            }
        }

        public Spell getSpellById(int id) {
            String sql = "SELECT id, name, description, school, level, casting_time, target, progression, effect_ids, traits, cooldown FROM spelltb WHERE id = ?";
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
            String sql = "SELECT id, name, description, school, level, casting_time, target, progression, effect_ids, traits, cooldown FROM spelltb WHERE name = ?";
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
            String sql = "SELECT id, name, description, school, level, casting_time, target, progression, effect_ids, traits, cooldown FROM spelltb ORDER BY school, level, name";
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
            String sql = "SELECT id, name, description, school, level, casting_time, target, progression, effect_ids, traits, cooldown FROM spelltb WHERE school = ? ORDER BY level, name";
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
            
            return new Spell(id, name, description, school, level, castingTime, target, effectIds, progression, traits, cooldown);
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
        
    private static final String URL = "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
    private static final String USER = "sa";
    private static final String PASS = "";

    public CharacterDAO() {
        ensureTable();
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
                            "cooldown DOUBLE DEFAULT 0 " +
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
                            "PRIMARY KEY (character_id, skill_id), " +
                            "FOREIGN KEY (character_id) REFERENCES characters(id), " +
                            "FOREIGN KEY (skill_id) REFERENCES skilltb(id) " +
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
                            "PRIMARY KEY (character_id, spell_id), " +
                            "FOREIGN KEY (character_id) REFERENCES characters(id), " +
                            "FOREIGN KEY (spell_id) REFERENCES spelltb(id) " +
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
                            "exit_d INT, " +
                            "FOREIGN KEY (area_id) REFERENCES area(id) " +
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

        // Per-character flag table (expandable key/value toggles)
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS character_flag (" +
                    "character_id INT NOT NULL, " +
                    "k VARCHAR(200) NOT NULL, " +
                    "v VARCHAR(2000), " +
                    "PRIMARY KEY (character_id, k), " +
                    "FOREIGN KEY (character_id) REFERENCES characters(id) " +
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
                    "PRIMARY KEY (character_id, slot_id), " +
                    "FOREIGN KEY (character_id) REFERENCES characters(id) " +
                    ")");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create character_equipment table", e);
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
            
            // Scale armor bonus by proficiency if this is armor
            int effectiveArmorBonus = tmpl.armorSaveBonus;
            if (tmpl.isArmor() && tmpl.armorSaveBonus != 0) {
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
                    effectiveArmorBonus = (int) Math.round(tmpl.armorSaveBonus * effectiveness);
                }
            }
            
            armorSum += effectiveArmorBonus;
            fortSum += tmpl.fortSaveBonus;
            reflexSum += tmpl.refSaveBonus;
            willSum += tmpl.willSaveBonus;
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
        String sql = "SELECT name, password_hash, salt, age, description, hp_max, hp_cur, mp_max, mp_cur, mv_max, mv_cur, str, dex, con, intel, wis, cha, armor, fortitude, reflex, will, armor_equip_bonus, fortitude_equip_bonus, reflex_equip_bonus, will_equip_bonus, current_room, current_class_id FROM characters WHERE name = ?";
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
                            currentClassId
                        );
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query character", e);
        }
        return null;
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
            return -1;
        }
        return -1;
    }

    // Insert area with explicit id (idempotent via MERGE)
    public int addAreaWithId(int id, String name, String description) {
        String sql = "MERGE INTO area (id, name, description) KEY(id) VALUES (?, ?, ?)";
        try (Connection c = DriverManager.getConnection(URL, USER, PASS);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setString(2, name);
            ps.setString(3, description == null ? "" : description);
            ps.executeUpdate();
            return id;
        } catch (SQLException e) {
            // fallback: try to find by name
            String sql2 = "SELECT id FROM area WHERE name = ?";
            try (Connection c = DriverManager.getConnection(URL, USER, PASS);
                 PreparedStatement ps2 = c.prepareStatement(sql2)) {
                ps2.setString(1, name);
                try (ResultSet rs = ps2.executeQuery()) {
                    if (rs.next()) return rs.getInt(1);
                }
            } catch (SQLException ignored) {}
            return -1;
        }
    }

    public Area getAreaById(int id) {
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
            return -1;
        }
    }

    public Room getRoomById(int id) {
        String sql = "SELECT id, area_id, name, short_desc, long_desc, exit_n, exit_e, exit_s, exit_w, exit_u, exit_d FROM room WHERE id = ?";
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
                    return new Room(rs.getInt("id"), rs.getInt("area_id"), rs.getString("name"), rs.getString("short_desc"), rs.getString("long_desc"), exitN, exitE, exitS, exitW, exitU, exitD);
                }
            }
        } catch (SQLException e) {
            return null;
        }
        return null;
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

    public boolean createCharacter(Character ch, String passwordHashBase64, String saltBase64) {
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
            System.err.println("Failed to add vitals: " + e.getMessage());
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
            System.err.println("Failed to restore vitals: " + e.getMessage());
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

        // Convenience methods to get total saves (base + equipment)
        public int getArmorTotal() { return armor + armorEquipBonus; }
        public int getFortitudeTotal() { return fortitude + fortitudeEquipBonus; }
        public int getReflexTotal() { return reflex + reflexEquipBonus; }
        public int getWillTotal() { return will + willEquipBonus; }

        public CharacterRecord(String name, String passwordHashBase64, String saltBase64,
                               int age, String description,
                               int hpMax, int hpCur,
                               int mpMax, int mpCur,
                               int mvMax, int mvCur,
                               int str, int dex, int con, int intel, int wis, int cha,
                               int armor, int fortitude, int reflex, int will,
                               int armorEquipBonus, int fortitudeEquipBonus, int reflexEquipBonus, int willEquipBonus,
                               Integer currentRoom,
                               Integer currentClassId) {
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
        String sql = "SELECT name, password_hash, salt, age, description, hp_max, hp_cur, mp_max, mp_cur, mv_max, mv_cur, str, dex, con, intel, wis, cha, armor, fortitude, reflex, will, armor_equip_bonus, fortitude_equip_bonus, reflex_equip_bonus, will_equip_bonus, current_room, current_class_id FROM characters WHERE id = ?";
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
                        currentClassId
                    );
                }
            }
        } catch (SQLException e) {
            // ignore
        }
        return null;
    }
}
