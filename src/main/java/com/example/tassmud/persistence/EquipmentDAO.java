package com.example.tassmud.persistence;

import com.example.tassmud.model.ArmorCategory;
import com.example.tassmud.model.CharacterSkill;
import com.example.tassmud.model.EquipmentSlot;
import com.example.tassmud.model.ItemInstance;
import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.model.Skill;

import java.sql.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DAO for character_equipment table.
 * Extracted from CharacterDAO to separate equipment persistence from character data.
 */
public class EquipmentDAO {

    private static final Logger logger = LoggerFactory.getLogger(EquipmentDAO.class);

    private static final String URL = System.getProperty("tassmud.db.url",
            "jdbc:h2:file:./data/tassmud;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1");
    public EquipmentDAO() {
        MigrationManager.ensureMigration("EquipmentDAO", this::ensureTable);
    }

    // ========================== Schema ==========================

    public void ensureTable() {
        try (Connection c = TransactionManager.getConnection();
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
    }

    // ========================== Equipment Methods ==========================

    public boolean setCharacterEquipment(int characterId, int slotId, Long itemInstanceId) {
        String sql = "MERGE INTO character_equipment (character_id, slot_id, item_instance_id) KEY (character_id, slot_id) VALUES (?, ?, ?)";
        try (Connection c = TransactionManager.getConnection();
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
        try (Connection c = TransactionManager.getConnection();
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

    public Map<Integer, Long> getEquipmentMapByCharacterId(int characterId) {
        Map<Integer, Long> map = new HashMap<>();
        String sql = "SELECT slot_id, item_instance_id FROM character_equipment WHERE character_id = ?";
        try (Connection c = TransactionManager.getConnection();
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
        Integer id = DaoProvider.characters().getCharacterIdByName(name);
        if (id == null) return false;
        return setCharacterEquipment(id, slotId, itemInstanceId);
    }

    public Map<Integer, Long> getEquipmentMapByName(String name) {
        Integer id = DaoProvider.characters().getCharacterIdByName(name);
        if (id == null) return Collections.emptyMap();
        return getEquipmentMapByCharacterId(id);
    }

    /**
     * Clear all equipment from a character (unequip all slots).
     * Used for player death to move equipment to corpse.
     */
    public void clearAllEquipment(int characterId) {
        String sql = "DELETE FROM character_equipment WHERE character_id = ?";
        try (Connection c = TransactionManager.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Failed to clear equipment: {}", e.getMessage());
        }
    }

    /**
     * Check if a character has a shield equipped in their off-hand.
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

        if (!template.isShield()) return false;
        if (template.isWeapon()) return false;

        return true;
    }

    /**
     * Check if a character has a shield equipped in their off-hand (by name).
     */
    public boolean hasShieldByName(String name, ItemDAO itemDao) {
        Integer id = DaoProvider.characters().getCharacterIdByName(name);
        if (id == null) return false;
        return hasShield(id, itemDao);
    }

    /**
     * Recalculate equipment bonuses by averaging stats across all equipment slots and persist to DB.
     * Empty slots and weapons count as 0 in the average.
     * Armor bonuses are scaled by proficiency: effectiveness = 50% + proficiency%.
     */
    public boolean recalculateEquipmentBonuses(int characterId, ItemDAO itemDao) {
        Map<Integer, Long> equipped = getEquipmentMapByCharacterId(characterId);

        int totalSlots = EquipmentSlot.values().length;

        int armorSum = 0, fortSum = 0, reflexSum = 0, willSum = 0;

        SkillDAO skillDao = DaoProvider.skills();

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            Long instanceId = equipped.get(slot.getId());
            if (instanceId == null) continue;

            ItemInstance inst = itemDao.getInstance(instanceId);
            if (inst == null) continue;
            ItemTemplate tmpl = itemDao.getTemplateById(inst.templateId);
            if (tmpl == null) continue;

            int baseArmorBonus = inst.getEffectiveArmorSave(tmpl);
            int baseFortBonus = inst.getEffectiveFortSave(tmpl);
            int baseRefBonus = inst.getEffectiveRefSave(tmpl);
            int baseWillBonus = inst.getEffectiveWillSave(tmpl);

            int effectiveArmorBonus = baseArmorBonus;
            if (tmpl.isArmor() && baseArmorBonus != 0) {
                ArmorCategory armorCat = tmpl.getArmorCategory();
                if (armorCat != null) {
                    double effectiveness = 0.50;
                    Skill armorSkill = skillDao.getSkillByKey(armorCat.getSkillKey());
                    if (armorSkill != null) {
                        CharacterSkill charSkill = skillDao.getCharacterSkill(characterId, armorSkill.getId());
                        if (charSkill != null) {
                            effectiveness = 0.50 + (charSkill.getProficiency() / 100.0);
                        }
                    }
                    effectiveArmorBonus = (int) Math.round(baseArmorBonus * effectiveness);
                }
            }

            armorSum += effectiveArmorBonus;
            fortSum += baseFortBonus;
            reflexSum += baseRefBonus;
            willSum += baseWillBonus;
        }

        int armorBonus = Math.round((float) armorSum / totalSlots);
        int fortBonus = Math.round((float) fortSum / totalSlots);
        int reflexBonus = Math.round((float) reflexSum / totalSlots);
        int willBonus = Math.round((float) willSum / totalSlots);

        String sql = "UPDATE characters SET armor_equip_bonus = ?, fortitude_equip_bonus = ?, reflex_equip_bonus = ?, will_equip_bonus = ? WHERE id = ?";
        try (Connection c = TransactionManager.getConnection();
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
        Integer id = DaoProvider.characters().getCharacterIdByName(name);
        if (id == null) return false;
        return recalculateEquipmentBonuses(id, itemDao);
    }
}
