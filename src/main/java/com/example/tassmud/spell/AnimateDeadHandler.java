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
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[animate dead] No context available");
            return false;
        }

        CommandContext cc = ctx.getCommandContext();

        // --- 1. Find a corpse in the room -------------------------------------
        int roomId = cc.currentRoomId;
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

        // --- 2. Determine caster level ----------------------------------------
        CharacterRecord rec = DaoProvider.characters().findById(casterId);
        int casterLevel = 1;
        if (rec != null && rec.currentClassId != null) {
            casterLevel = DaoProvider.classes()
                    .getCharacterClassLevel(casterId, rec.currentClassId);
        }
        if (casterLevel < 1) casterLevel = 1;

        // --- 3. Roll undead type and compute minion level ----------------------
        UndeadType type = UndeadType.rollRandomType(casterLevel);
        int minionLevel = Math.max(1, casterLevel + type.getLevelOffset());

        // --- 4. Build template and spawn mob -----------------------------------
        MobileTemplate template = UndeadTemplateFactory.createTemplate(type, minionLevel);
        String originUuid = "animate_dead_" + casterId + "_" + UUID.randomUUID();
        Mobile mob = DaoProvider.mobiles().spawnMobile(template, roomId, originUuid);
        if (mob == null) {
            logger.error("[animate dead] Failed to spawn undead for caster {}", casterId);
            cc.send("The dark magic fizzles. The corpse remains lifeless.");
            return false;
        }
        MobileRegistry.getInstance().register(mob);

        // --- 5. Consume the corpse --------------------------------------------
        DaoProvider.items().deleteInstance(corpse.instance.instanceId);

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
        String corpseName = corpse.instance.customName != null
                ? corpse.instance.customName
                : "the corpse";
        String mobName = mob.getName();
        int durationSec = (int) (durationMs / 1000);

        cc.send("Dark energy crackles as " + corpseName + " convulses and rises as "
                + mobName + "!");
        cc.send("It will serve you for " + durationSec + " seconds.");
        ClientHandler.broadcastRoomMessage(roomId,
                cc.playerName + " raises " + corpseName + " as " + mobName + "!",
                casterId);

        logger.info("[animate dead] {} raised {} (level {}, type {}) in room {} — expires in {}s",
                cc.playerName, mobName, minionLevel, type.name(), roomId, durationSec);

        return true;
    }
}
