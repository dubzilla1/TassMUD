package com.example.tassmud.util.mob;

import com.example.tassmud.combat.CombatManager;
import com.example.tassmud.model.Mobile;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.util.MobileRegistry;
import com.example.tassmud.util.TickService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * Periodic service that fires out-of-combat mobile specials (~every 4 seconds).
 * <p>
 * Combat specials are handled inline by {@code CombatManager.selectMobileCommand()}.
 * This service handles behaviours that should fire even when the mob is not in
 * combat (e.g. {@code spec_fido}, {@code spec_janitor}, {@code spec_mayor}).
 */
public class MobileSpecialService {

    private static final Logger logger = LoggerFactory.getLogger(MobileSpecialService.class);
    private static final long TICK_INTERVAL_MS = 4_000;

    private static final MobileSpecialService INSTANCE = new MobileSpecialService();

    private MobileSpecialService() {}

    public static MobileSpecialService getInstance() {
        return INSTANCE;
    }

    public void initialize(TickService tickService) {
        tickService.scheduleAtFixedRate("mob-specials-oc",
                this::tick, TICK_INTERVAL_MS, TICK_INTERVAL_MS);
        logger.info("MobileSpecialService: initialized (interval={}ms)", TICK_INTERVAL_MS);
    }

    private void tick() {
        Collection<Mobile> mobs = MobileRegistry.getInstance().getAll();
        if (mobs.isEmpty()) return;

        MobileSpecialRegistry registry = MobileSpecialRegistry.getInstance();
        CombatManager combatManager = CombatManager.getInstance();

        for (Mobile mob : mobs) {
            if (mob.isDead()) continue;
            String specFun = mob.getSpecFun();
            if (specFun == null) continue;
            // Skip — combat specials are handled by CombatManager tick
            if (combatManager.isInCombat(mob)) continue;

            MobileSpecialHandler handler = registry.get(specFun);
            if (handler == null) continue;

            Integer roomId = mob.getCurrentRoom();
            if (roomId == null) continue;

            MobileSpecialContext ctx = new MobileSpecialContext(
                    null,   // no active combat
                    roomId,
                    (charId, msg) -> ClientHandler.sendToCharacter(charId, msg),
                    (rid, msg) -> ClientHandler.broadcastRoomMessage(rid, msg),
                    combatManager
            );

            try {
                handler.trigger(mob, ctx);
            } catch (Exception e) {
                logger.warn("MobileSpecialService: error in '{}' for mob {} (instance {}): {}",
                        specFun, mob.getName(), mob.getInstanceId(), e.getMessage(), e);
            }
        }
    }
}
