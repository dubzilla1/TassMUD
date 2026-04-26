package com.example.tassmud.spell;

import com.example.tassmud.model.AllyBehavior;
import com.example.tassmud.model.AllyBinding;
import com.example.tassmud.model.AllyPersistence;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileTemplate;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.net.commands.CommandContext;
import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.util.AllyManager;
import com.example.tassmud.util.MobileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Implements the Divine Fury spell (id 151, DIVINE school).
 *
 * <p>Summons one of three angels — Deva, Planetar, or Solar — chosen at
 * random, to fight as a DEFENDER ally for 60 seconds.  The angel is bound
 * to the caster through {@link AllyManager} and despawned automatically by
 * {@link com.example.tassmud.util.AllyManager#sweepExpiredBindings()}.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Pick a random angel template (790 Deva / 791 Planetar / 792 Solar)</li>
 *   <li>Load the {@link MobileTemplate} from the DB</li>
 *   <li>Spawn a {@link Mobile} instance in the caster's room</li>
 *   <li>Register in {@link MobileRegistry}</li>
 *   <li>Bind as timed DEFENDER ally (60 s fixed)</li>
 *   <li>Send flavor messages to caster and room</li>
 * </ol>
 */
public final class DivineFuryHandler {

    private static final Logger logger = LoggerFactory.getLogger(DivineFuryHandler.class);

    /** Fixed ally duration: 60 seconds. */
    private static final long DURATION_MS = 60_000L;

    /** Template IDs for Deva, Planetar, Solar (in ascending power order). */
    private static final int[] ANGEL_TEMPLATE_IDS = { 790, 791, 792 };

    private static final String[] ANGEL_ARTICLE = { "a", "a", "a" };

    private DivineFuryHandler() {}

    /**
     * Entry point called from {@link DivineSpellHandler}'s dispatch switch.
     *
     * @return true if the spell succeeded, false otherwise
     */
    public static boolean handle(Integer casterId, String args, SpellContext ctx) {
        if (ctx == null || ctx.getCommandContext() == null) {
            logger.warn("[divine fury] No spell context provided");
            return false;
        }

        CommandContext cc = ctx.getCommandContext();

        if (casterId == null) {
            cc.send("No caster for Divine Fury.");
            return false;
        }

        int roomId = cc.currentRoomId;

        // --- 1. Pick random angel type -----------------------------------------
        int idx = ThreadLocalRandom.current().nextInt(ANGEL_TEMPLATE_IDS.length);
        int templateId = ANGEL_TEMPLATE_IDS[idx];

        // --- 2. Load template from DB ------------------------------------------
        MobileTemplate template = DaoProvider.mobiles().getTemplateById(templateId);
        if (template == null) {
            logger.error("[divine fury] Angel template {} not found in DB", templateId);
            cc.send("The heavens do not answer your call.");
            return false;
        }

        // --- 3. Spawn the angel ------------------------------------------------
        String originUuid = "divine_fury_" + casterId + "_" + UUID.randomUUID();
        Mobile angel = DaoProvider.mobiles().spawnMobile(template, roomId, originUuid);
        if (angel == null) {
            logger.error("[divine fury] Failed to spawn angel template {} for caster {}", templateId, casterId);
            cc.send("The celestial summons falters and dissipates.");
            return false;
        }
        MobileRegistry.getInstance().register(angel);

        // --- 4. Bind as timed DEFENDER ally ------------------------------------
        long expiresAt = System.currentTimeMillis() + DURATION_MS;
        AllyBinding binding = new AllyBinding(
                angel.getInstanceId(),
                casterId,
                templateId,
                AllyBehavior.DEFENDER,
                AllyPersistence.TEMPORARY,
                true,   // followsOwner
                true,   // obeys
                expiresAt
        );
        AllyManager.getInstance().bindAlly(binding);

        // --- 5. Messages -------------------------------------------------------
        String angelName = angel.getName();
        cc.send("\u001b[1;93mA blinding column of divine light descends from the heavens!\u001b[0m");
        cc.send(angelName + " materialises before you, ready to fight by your side!");
        cc.send("The celestial will serve you for 60 seconds.");

        ClientHandler.broadcastRoomMessage(roomId,
                cc.playerName + "'s cry is answered by the heavens — " + angelName
                + " descends to fight alongside them!",
                casterId);

        logger.info("[divine fury] {} summoned {} (template {}) in room {} — expires in 60s",
                cc.playerName, angelName, templateId, roomId);

        return true;
    }
}
