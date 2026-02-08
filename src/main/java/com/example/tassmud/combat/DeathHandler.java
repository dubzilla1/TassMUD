package com.example.tassmud.combat;

import com.example.tassmud.model.GameCharacter;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.Modifier;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterClassDAO;
import com.example.tassmud.persistence.CharacterDAO;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.persistence.MobileDAO;
import com.example.tassmud.persistence.TransactionManager;
import com.example.tassmud.util.LootGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * Handles mob and player death processing: corpse creation, loot,
 * equipped-item transfer, autogold/autoloot/autosac, XP deduction,
 * and player death teleport.
 *
 * Extracted from CombatManager to isolate death-sequence business logic
 * from combat orchestration.
 */
public class DeathHandler {

    private static final Logger logger = LoggerFactory.getLogger(DeathHandler.class);

    private final BiConsumer<Integer, String> playerMessageCallback;
    private final BiConsumer<Integer, String> roomMessageCallback;
    private final CombatRewardService rewardService;

    public DeathHandler(BiConsumer<Integer, String> playerMessageCallback,
                        BiConsumer<Integer, String> roomMessageCallback,
                        CombatRewardService rewardService) {
        this.playerMessageCallback = playerMessageCallback;
        this.roomMessageCallback = roomMessageCallback;
        this.rewardService = rewardService;
    }

    // ── helpers ────────────────────────────────────────────────────────

    private void sendToPlayer(Integer characterId, String message) {
        if (playerMessageCallback != null && characterId != null) {
            playerMessageCallback.accept(characterId, message);
        }
    }

    private void broadcastToRoom(int roomId, String message) {
        if (roomMessageCallback != null) {
            roomMessageCallback.accept(roomId, message);
        }
    }

    // ── public API ─────────────────────────────────────────────────────

    /**
     * Handle a combatant being killed.
     * Dispatches to mob-death or player-death processing as appropriate,
     * then awards XP and weapon proficiency to the killer.
     */
    public void handleCombatantDeath(Combat combat, Combatant victim, Combatant killer) {
        String deathMessage = victim.getName() + " has been slain by " + killer.getName() + "!";
        broadcastToRoom(combat.getRoomId(), deathMessage);
        combat.logEvent(deathMessage);

        // Mark as inactive
        victim.setActive(false);

        // Handle player death
        if (victim.isPlayer()) {
            handlePlayerDeath(combat, victim);
        }

        // Handle mob death — spawn corpse and despawn mob
        if (victim.isMobile()) {
            handleMobDeath(combat, victim, killer);
        }

        // Award weapon skill proficiency to the killer if they are a player
        rewardService.awardWeaponSkillOnKill(killer);
    }

    // ── mob death ──────────────────────────────────────────────────────

    private void handleMobDeath(Combat combat, Combatant victim, Combatant killer) {
        Mobile mob = victim.getMobile();
        if (mob == null) return;

        int roomId = combat.getRoomId();
        String mobName = mob.getName();
        int mobLevel = Math.max(1, mob.getLevel());

        // Calculate gold to put in corpse
        Random rand = new Random();
        int baseGold = mobLevel * 2;
        int bonusGold = (mobLevel * mobLevel) / 5;
        int variance = rand.nextInt(mobLevel + 1);
        long goldAmount = baseGold + bonusGold + variance;

        // Wrap corpse creation + loot + equip-transfer in a single transaction
        ItemDAO itemDAO = DaoProvider.items();
        final long[] corpseIdHolder = {-1};
        final List<LootGenerator.GeneratedItem>[] lootHolder = new List[]{Collections.emptyList()};

        TransactionManager.runInTransaction(() -> {
            try {
                corpseIdHolder[0] = itemDAO.createCorpse(roomId, mobName, goldAmount);
                if (corpseIdHolder[0] > 0) {
                    // Generate random loot in the corpse
                    lootHolder[0] = LootGenerator.generateLoot(mobLevel, corpseIdHolder[0], itemDAO);
                }
            } catch (Exception e) {
                logger.warn("[DeathHandler] Failed to create corpse for {}: {}", mobName, e.getMessage(), e);
            }

            // Move any item instances that were used to equip this mob into the corpse.
            moveEquippedItemsToCorpse(mob, corpseIdHolder[0]);
        });

        long corpseId = corpseIdHolder[0];

        // Send loot messages outside the transaction
        if (corpseId > 0) {
            broadcastToRoom(roomId, mobName + " falls to the ground, leaving behind a corpse.");
            List<LootGenerator.GeneratedItem> loot = lootHolder[0];
            if (loot.isEmpty()) {
                logger.info("[Loot] No items generated for {} (level {})", mobName, mobLevel);
            } else {
                for (LootGenerator.GeneratedItem item : loot) {
                    String itemName = item.customName != null ? item.customName : "item#" + item.templateId;
                    logger.info("[Loot] Generated '{}' from {} (level {})", itemName, mobName, mobLevel);
                    if (item.customName != null) {
                        broadcastToRoom(roomId, "  * " + item.customName);
                    }
                }
            }
        }

        // Handle autogold / autoloot / autosac for the killer
        if (killer.isPlayer() && killer.getCharacterId() != null && corpseId > 0) {
            handleAutoPickup(killer, corpseId, goldAmount, roomId);
        }

        // Mark the mob as dead and remove from the world
        try {
            MobileDAO mobileDAO = DaoProvider.mobiles();
            mob.die();
            com.example.tassmud.util.MobileRegistry.getInstance().unregister(mob.getInstanceId());
            mobileDAO.deleteInstance(mob.getInstanceId());
        } catch (Exception e) {
            logger.warn("[DeathHandler] Failed to despawn mob {}: {}", mobName, e.getMessage(), e);
        }

        // Award XP to the killer if they are a player
        rewardService.awardExperienceOnKill(killer, victim);
    }

    /**
     * Move any item instances carried/equipped by the mob into a corpse container.
     * SpawnEvent applied modifiers with source="equip#&lt;instanceId&gt;" or "inventory#&lt;instanceId&gt;".
     */
    private void moveEquippedItemsToCorpse(Mobile mob, long corpseId) {
        if (corpseId <= 0) return;
        try {
            ItemDAO itemDAO = DaoProvider.items();
            List<Modifier> mods = mob.getAllModifiers();
            for (Modifier mod : mods) {
                String src = mod.source();
                if (src == null) continue;
                String idPart = null;
                if (src.startsWith("equip#")) {
                    idPart = src.substring("equip#".length());
                } else if (src.startsWith("inventory#")) {
                    idPart = src.substring("inventory#".length());
                }
                if (idPart == null) continue;
                try {
                    long itemInstanceId = Long.parseLong(idPart);
                    itemDAO.moveInstanceToContainer(itemInstanceId, corpseId);
                } catch (NumberFormatException nfe) {
                    // ignore malformed source
                } catch (Exception ex) {
                    logger.warn("[DeathHandler] Failed to move item {} into corpse: {}", idPart, ex.getMessage(), ex);
                }
            }
        } catch (Exception e) {
            logger.warn("[DeathHandler] Error while moving equipped items to corpse: {}", e.getMessage(), e);
        }
    }

    /**
     * Process autogold, autoloot, and autosac for the killing player.
     */
    private void handleAutoPickup(Combatant killer, long corpseId, long goldAmount, int roomId) {
        Integer killerId = killer.getCharacterId();
        CharacterDAO charDao = DaoProvider.characters();
        ItemDAO itemDAO = DaoProvider.items();
        CharacterRecord killerRec = charDao.getCharacterById(killerId);

        if (killerRec == null) return;

        // Wrap autogold + autoloot in a single transaction to prevent gold/item loss
        final CharacterRecord rec = killerRec;
        TransactionManager.runInTransaction(() -> {
            // Autogold
            if (rec.autogold && goldAmount > 0) {
                long goldTaken = itemDAO.takeGoldContents(corpseId);
                if (goldTaken > 0) {
                    charDao.addGold(killerId, goldTaken);
                    sendToPlayer(killerId, "You receive " + goldTaken + " gold.");
                }
            }

            // Autoloot
            if (rec.autoloot) {
                List<ItemDAO.RoomItem> corpseContents = itemDAO.getItemsInContainer(corpseId);
                if (!corpseContents.isEmpty()) {
                    for (ItemDAO.RoomItem ri : corpseContents) {
                        itemDAO.moveInstanceToCharacter(ri.instance.instanceId, killerId);
                        String displayName = ri.instance.customName != null && !ri.instance.customName.isEmpty()
                                ? ri.instance.customName : ri.template.name;
                        sendToPlayer(killerId, "You get " + displayName + " from the corpse.");
                    }
                }
            }
        });

        // Autosac — sacrifice empty corpses for 1 XP (separate transaction since it's independent)
        killerRec = charDao.getCharacterById(killerId); // refresh
        if (killerRec != null && killerRec.autosac) {
            List<ItemDAO.RoomItem> remainingContents = itemDAO.getItemsInContainer(corpseId);
            long remainingGold = itemDAO.getGoldContents(corpseId);
            if (remainingContents.isEmpty() && remainingGold <= 0) {
                itemDAO.deleteInstance(corpseId);
                sendToPlayer(killerId, "You sacrifice the corpse.");
                com.example.tassmud.util.ExperienceService.awardFlatXp(
                        killerId, 1, msg -> sendToPlayer(killerId, msg));
            }
        }
    }

    // ── player death ───────────────────────────────────────────────────

    /**
     * Handle a player's death — create corpse, move items, deduct XP, teleport to recall.
     *
     * Death sequence:
     * 1. Create a corpse container in the death room with the player's name
     * 2. Move ALL equipped items into the corpse
     * 3. Move ALL inventory items into the corpse
     * 4. Move ALL gold into the corpse
     * 5. Deduct 250 XP (minimum 0, cannot lose a level)
     * 6. Teleport player to Mead-Gaard Inn (room 3041)
     * 7. Set HP to 1, MP to 0, MV to 0
     * 8. Set stance to SLEEPING
     */
    private void handlePlayerDeath(Combat combat, Combatant victim) {
        Integer characterId = victim.getCharacterId();
        if (characterId == null) return;

        int deathRoomId = combat.getRoomId();
        String playerName = victim.getName();

        CharacterDAO charDAO = DaoProvider.characters();
        ItemDAO itemDAO = DaoProvider.items();
        CharacterClassDAO classDAO = DaoProvider.classes();

        // Get player's gold before we clear it
        long playerGold = charDAO.getGold(characterId);

        // Wrap all death DB operations in a single transaction to prevent item/gold loss
        final int[] xpLostHolder = {0};
        final long[] corpseIdHolder = {-1};
        final int recallRoomId = 3041;

        TransactionManager.runInTransaction(() -> {
            // 1. Create the corpse in the death room (uses template 999)
            try {
                corpseIdHolder[0] = itemDAO.createCorpse(deathRoomId, playerName, playerGold);
            } catch (Exception e) {
                logger.warn("[DeathHandler] Failed to create corpse for {}: {}", playerName, e.getMessage(), e);
            }

            long corpseId = corpseIdHolder[0];

            // 2. Move ALL equipped items into the corpse
            if (corpseId > 0) {
                Map<Integer, Long> equipment = DaoProvider.equipment().getEquipmentMapByCharacterId(characterId);
                Set<Long> movedItemIds = new HashSet<>();
                for (Map.Entry<Integer, Long> entry : equipment.entrySet()) {
                    Long itemInstanceId = entry.getValue();
                    if (itemInstanceId != null && !movedItemIds.contains(itemInstanceId)) {
                        try {
                            itemDAO.moveInstanceToContainer(itemInstanceId, corpseId);
                            movedItemIds.add(itemInstanceId);
                        } catch (Exception e) {
                            logger.warn("[DeathHandler] Failed to move equipment to corpse: {}", e.getMessage(), e);
                        }
                    }
                }
                // Clear all equipment slots
                DaoProvider.equipment().clearAllEquipment(characterId);

                // 3. Move ALL inventory items into the corpse
                List<ItemDAO.RoomItem> inventory = itemDAO.getItemsByCharacter(characterId);
                for (ItemDAO.RoomItem item : inventory) {
                    try {
                        itemDAO.moveInstanceToContainer(item.instance.instanceId, corpseId);
                    } catch (Exception e) {
                        logger.warn("[DeathHandler] Failed to move inventory to corpse: {}", e.getMessage(), e);
                    }
                }

                // 4. Gold was already moved when corpse was created, just clear player's gold
                if (playerGold > 0) {
                    charDAO.setGold(characterId, 0);
                }
            }

            // 5. Deduct 250 XP (minimum 0, cannot lose a level)
            xpLostHolder[0] = classDAO.deductXpFromCurrentClass(characterId, 250);

            // 6. Teleport player to Mead-Gaard Inn (room 3041)
            charDAO.updateCharacterRoom(playerName, recallRoomId);

            // 7. Set HP to 1, MP to 0, MV to 0
            charDAO.setVitals(characterId, 1, 0, 0);
        });

        long corpseId = corpseIdHolder[0];
        int xpLost = xpLostHolder[0];

        if (corpseId > 0) {
            broadcastToRoom(deathRoomId, playerName + " has fallen! Their corpse lies on the ground.");
        }

        // Announce departure from death room (if different from recall room)
        if (deathRoomId != recallRoomId) {
            broadcastToRoom(deathRoomId, playerName + "'s spirit departs...");
        }

        // Announce arrival at recall room
        broadcastToRoom(recallRoomId, "The spirit of " + playerName + " materializes, barely clinging to life.");

        // Update the character's in-memory state
        GameCharacter victimChar = victim.getAsCharacter();
        if (victimChar != null) {
            victimChar.setHpCur(1);
            victimChar.setMpCur(0);
            victimChar.setMvCur(0);
            victimChar.setCurrentRoom(recallRoomId);
        }

        // 8. Set stance to SLEEPING (handled by ClientHandler session update)

        // Send death messages to the player
        sendToPlayer(characterId, "");
        sendToPlayer(characterId, "\u001B[1;31m*** YOU HAVE DIED ***\u001B[0m");
        sendToPlayer(characterId, "");
        sendToPlayer(characterId, "Your vision fades to black...");
        sendToPlayer(characterId, "");
        if (playerGold > 0) {
            sendToPlayer(characterId, "Your \u001B[33m" + playerGold + " gold\u001B[0m lies in your corpse.");
        }
        if (xpLost > 0) {
            sendToPlayer(characterId, "You have lost \u001B[1;31m" + xpLost + " experience\u001B[0m.");
        }
        sendToPlayer(characterId, "");
        sendToPlayer(characterId, "You awaken at the Mead-Gaard Inn, naked and penniless.");
        sendToPlayer(characterId, "Your possessions remain in your corpse at the place of your death.");
        sendToPlayer(characterId, "You fall into an exhausted sleep...");

        // Notify ClientHandler to set the player to SLEEPING stance
        ClientHandler.handlePlayerDeathStance(characterId);
    }
}
