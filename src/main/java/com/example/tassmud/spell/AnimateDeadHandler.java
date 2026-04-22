package com.example.tassmud.spell;

import com.example.tassmud.model.AllyBehavior;
import com.example.tassmud.model.AllyBinding;
import com.example.tassmud.model.AllyPersistence;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileTemplate;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.net.commands.CommandContext;
import com.example.tassmud.persistence.CharacterDAO.CharacterRecord;
import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.persistence.ItemDAO;
import com.example.tassmud.util.AllyManager;
import com.example.tassmud.util.MobileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Implements the Animate Dead spell (id 321, OCCULT school).
 *
 * <p>Finds the first corpse in the caster's room, consumes it, and raises a
 * level-scaled undead minion that is bound to the caster as a DEFENDER ally
 * with a proficiency-based duration.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Validate caster has no existing summoned undead ally</li>
 *   <li>Find a corpse (template_id = 999) in the room</li>
 *   <li>Determine caster level and roll an {@link UndeadType}</li>
 *   <li>Build a {@link MobileTemplate} via {@link UndeadTemplateFactory}</li>
 *   <li>Spawn the mob, register in {@link MobileRegistry}</li>
 *   <li>Delete the corpse item</li>
 *   <li>Bind as ally in {@link AllyManager}</li>
 *   <li>Send messages to caster and room</li>
 * </ol>
 */
public final class AnimateDeadHandler {

    private static final Logger logger = LoggerFactory.getLogger(AnimateDeadHandler.class);

    /** Base duration: 1 minute. */
    private static final long BASE_DURATION_MS = 60_000L;
    /** Bonus per 10 proficiency: 1 minute. */
    private static final long BONUS_PER_10_PROF_MS = 60_000L;

    private AnimateDeadHandler() {}

    /**
     * Entry point called from {@link OccultSpellHandler}'s dispatch switch.
     *
     * @return true if the spell succeeded, false otherwise
     */
    public static boolean handle(Integer casterId, String args, SpellContext ctx) {
        return handleInternal(casterId, ctx, true, null, "animate_dead");
    }

    /**
     * Summon Skeleton variant: always summons a skeleton and does not require
     * a corpse in the room.
     */
    public static boolean handleSummonSkeleton(Integer casterId, String args, SpellContext ctx) {
        return handleInternal(casterId, ctx, false, UndeadType.SKELETON, "summon_skeleton");
    }

    /**
     * Army of the Dead: consumes one corpse and raises 5 random undead,
     * each lasting exactly 60 seconds (not proficiency-scaled).
     */
    public static boolean handleArmyOfTheDead(Integer casterId, String args, SpellContext ctx) {
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[army_of_the_dead] No context available");
            return false;
        }

        CommandContext cc = ctx.getCommandContext();
        int roomId = cc.currentRoomId;

        // --- Find and consume a single corpse ---
        List<ItemDAO.RoomItem> roomItems = DaoProvider.items().getItemsInRoom(roomId);
        ItemDAO.RoomItem corpse = null;
        for (ItemDAO.RoomItem ri : roomItems) {
            if (ri.instance.templateId == ItemDAO.CORPSE_TEMPLATE_ID) {
                corpse = ri;
                break;
            }
        }
        if (corpse == null) {
            cc.send("There is no corpse here to animate.");
            return false;
        }

        // --- Determine caster level ---
        CharacterRecord rec = DaoProvider.characters().findById(casterId);
        int casterLevel = 1;
        if (rec != null && rec.currentClassId != null) {
            casterLevel = DaoProvider.classes()
                    .getCharacterClassLevel(casterId, rec.currentClassId);
        }
        if (casterLevel < 1) casterLevel = 1;

        // --- Consume the corpse ---
        String corpseName = corpse.instance.customName != null
                ? corpse.instance.customName
                : "the corpse";
        DaoProvider.items().deleteInstance(corpse.instance.instanceId);

        // --- Announce the ritual ---
        cc.send("Dark energy erupts from " + corpseName
                + " as you shatter the veil between worlds!");
        ClientHandler.broadcastRoomMessage(roomId,
                cc.playerName + " tears open the veil between worlds — an army of the dead rises!",
                casterId);

        // --- Summon 5 random undead, fixed 60s duration ---
        long expiresAt = System.currentTimeMillis() + BASE_DURATION_MS;
        int summoned = 0;
        for (int i = 0; i < 5; i++) {
            UndeadType type = UndeadType.rollRandomType(casterLevel);
            int minionLevel = Math.max(1, casterLevel + type.getLevelOffset());
            MobileTemplate template = UndeadTemplateFactory.createTemplate(type, minionLevel);
            String originUuid = "army_of_the_dead_" + casterId + "_" + i + "_" + UUID.randomUUID();
            Mobile mob = DaoProvider.mobiles().spawnMobile(template, roomId, originUuid);
            if (mob == null) {
                logger.warn("[army_of_the_dead] Failed to spawn minion {} for caster {}", i + 1, casterId);
                continue;
            }
            MobileRegistry.getInstance().register(mob);

            AllyBinding binding = new AllyBinding(
                    mob.getInstanceId(),
                    casterId,
                    template.getId(),
                    AllyBehavior.DEFENDER,
                    AllyPersistence.TEMPORARY,
                    true,
                    true,
                    expiresAt
            );
            AllyManager.getInstance().bindAlly(binding);
            summoned++;
        }

        cc.send(summoned + " undead servants rise to fight for you — for 60 seconds!");
        logger.info("[army_of_the_dead] {} summoned {} undead in room {} - expires in 60s",
                cc.playerName, summoned, roomId);

        return true;
    }

    private static boolean handleInternal(Integer casterId,
                                          SpellContext ctx,
                                          boolean requireCorpse,
                                          UndeadType forcedType,
                                          String originPrefix) {
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[{}] No context available", originPrefix);
            return false;
        }

        CommandContext cc = ctx.getCommandContext();

        // --- 1. Find a corpse in the room if required --------------------------
        int roomId = cc.currentRoomId;
        ItemDAO.RoomItem corpse = null;
        if (requireCorpse) {
            List<ItemDAO.RoomItem> roomItems = DaoProvider.items().getItemsInRoom(roomId);
            for (ItemDAO.RoomItem ri : roomItems) {
                if (ri.instance.templateId == ItemDAO.CORPSE_TEMPLATE_ID) {
                    corpse = ri;
                    break;
                }
            }
            if (corpse == null) {
                cc.send("There is no corpse here to animate.");
                return false;
            }
        }

        // --- 2. Determine caster level ----------------------------------------
        CharacterRecord rec = DaoProvider.characters().findById(casterId);
        int casterLevel = 1;
        if (rec != null && rec.currentClassId != null) {
            casterLevel = DaoProvider.classes()
                    .getCharacterClassLevel(casterId, rec.currentClassId);
        }
        if (casterLevel < 1) casterLevel = 1;

        // --- 3. Roll undead type and compute minion level ----------------------
        UndeadType type = forcedType != null ? forcedType : UndeadType.rollRandomType(casterLevel);
        int minionLevel = Math.max(1, casterLevel + type.getLevelOffset());

        // --- 4. Build template and spawn mob -----------------------------------
        MobileTemplate template = UndeadTemplateFactory.createTemplate(type, minionLevel);
        String originUuid = originPrefix + "_" + casterId + "_" + UUID.randomUUID();
        Mobile mob = DaoProvider.mobiles().spawnMobile(template, roomId, originUuid);
        if (mob == null) {
            logger.error("[{}] Failed to spawn undead for caster {}", originPrefix, casterId);
            if (requireCorpse) {
                cc.send("The dark magic fizzles. The corpse remains lifeless.");
            } else {
                cc.send("The summoning falters and dissipates into shadow.");
            }
            return false;
        }
        MobileRegistry.getInstance().register(mob);

        // --- 5. Consume the corpse if this variant requires one ----------------
        if (corpse != null) {
            DaoProvider.items().deleteInstance(corpse.instance.instanceId);
        }

        // --- 6. Bind as ally with proficiency-based duration -------------------
        int proficiency = ctx.getProficiency();
        long durationMs = BASE_DURATION_MS + (proficiency / 10) * BONUS_PER_10_PROF_MS;
        long expiresAt = System.currentTimeMillis() + durationMs;

        AllyBinding binding = new AllyBinding(
                mob.getInstanceId(),
                casterId,
                template.getId(),
                AllyBehavior.DEFENDER,
                AllyPersistence.TEMPORARY,
                true,   // followsOwner
                true,   // obeys
                expiresAt
        );
        AllyManager.getInstance().bindAlly(binding);

        // --- 7. Messages -------------------------------------------------------
        String mobName = mob.getName();
        int durationSec = (int) (durationMs / 1000);

        if (requireCorpse && corpse != null) {
            String corpseName = corpse.instance.customName != null
                ? corpse.instance.customName
                : "the corpse";
            cc.send("Dark energy crackles as " + corpseName + " convulses and rises as "
                + mobName + "!");
            ClientHandler.broadcastRoomMessage(roomId,
                cc.playerName + " raises " + corpseName + " as " + mobName + "!",
                casterId);
        } else {
            cc.send("You tear open a rift to the spirit realm and summon " + mobName + "!");
            ClientHandler.broadcastRoomMessage(roomId,
                cc.playerName + " summons " + mobName + " from the spirit realm!",
                casterId);
        }
        cc.send("It will serve you for " + durationSec + " seconds.");

        logger.info("[{}] {} summoned {} (level {}, type {}) in room {} - expires in {}s",
            originPrefix, cc.playerName, mobName, minionLevel, type.name(), roomId, durationSec);

        return true;
    }
}
