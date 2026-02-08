package com.example.tassmud.persistence;

/**
 * Central provider for DAO singletons.
 * All DAOs are stateless and acquire/release DB connections per operation,
 * so sharing a single instance is safe and eliminates unnecessary object churn.
 * <p>
 * Usage: {@code DaoProvider.characters().getCharacterById(id);}
 */
public final class DaoProvider {

    private static final CharacterDAO CHARACTER_DAO = new CharacterDAO();
    private static final ItemDAO ITEM_DAO = new ItemDAO();
    private static final MobileDAO MOBILE_DAO = new MobileDAO();
    private static final CharacterClassDAO CHARACTER_CLASS_DAO = new CharacterClassDAO();
    private static final ShopDAO SHOP_DAO = new ShopDAO();
    private static final RoomDAO ROOM_DAO = new RoomDAO();
    private static final SkillDAO SKILL_DAO = new SkillDAO();
    private static final SpellDAO SPELL_DAO = new SpellDAO();
    private static final EquipmentDAO EQUIPMENT_DAO = new EquipmentDAO();
    private static final SettingsDAO SETTINGS_DAO = new SettingsDAO();

    private DaoProvider() {
        // Utility class — not instantiable
    }

    /** @return the shared CharacterDAO instance */
    public static CharacterDAO characters() {
        return CHARACTER_DAO;
    }

    /** @return the shared ItemDAO instance */
    public static ItemDAO items() {
        return ITEM_DAO;
    }

    /** @return the shared MobileDAO instance */
    public static MobileDAO mobiles() {
        return MOBILE_DAO;
    }

    /** @return the shared CharacterClassDAO instance */
    public static CharacterClassDAO classes() {
        return CHARACTER_CLASS_DAO;
    }

    /** @return the shared ShopDAO instance */
    public static ShopDAO shops() {
        return SHOP_DAO;
    }

    /** @return the shared RoomDAO instance */
    public static RoomDAO rooms() {
        return ROOM_DAO;
    }

    /** @return the shared SkillDAO instance */
    public static SkillDAO skills() {
        return SKILL_DAO;
    }

    /** @return the shared SpellDAO instance */
    public static SpellDAO spells() {
        return SPELL_DAO;
    }

    /** @return the shared EquipmentDAO instance */
    public static EquipmentDAO equipment() {
        return EQUIPMENT_DAO;
    }

    /** @return the shared SettingsDAO instance */
    public static SettingsDAO settings() {
        return SETTINGS_DAO;
    }
}
