package com.example.tassmud.effect;

import com.example.tassmud.net.ClientHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Effect handler for the {@code HOLY_AVENGER} effect type (id "1028").
 *
 * <p>While active, each time the paladin lands a damaging hit the bonus damage
 * escalates: 1×1d10 on the first hit, 2×1d10 on the second, 3×1d10 on the
 * third, etc.  The counter is reset whenever the effect expires or is replaced
 * by a new cast.
 *
 * <p>Combat integration: {@code CombatManager.tryHolyAvengerBonus()} is called
 * after every successful hit, exactly as {@code tryHolyWeaponSmite} is called.
 */
public class HolyAvengerEffect implements EffectHandler {

    private static final Logger logger = LoggerFactory.getLogger(HolyAvengerEffect.class);

    /**
     * Hit counter per active caster.  Key = casterId, value = number of hits
     * landed so far (starts at 0; incremented before computing bonus).
     */
    private static final ConcurrentHashMap<Integer, AtomicInteger> hitCounters =
            new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Static API used by CombatManager
    // -------------------------------------------------------------------------

    /** Returns {@code true} if the caster currently has Holy Avenger active. */
    public static boolean isActive(Integer charId) {
        return charId != null && hitCounters.containsKey(charId);
    }

    /**
     * Increments the hit counter for {@code charId} and returns the bonus holy
     * damage to deal: {@code hitNumber × 1d10} (where hitNumber starts at 1).
     *
     * @return bonus damage (&gt;= 1), or -1 if Holy Avenger is not active.
     */
    public static int computeAndIncrementBonus(Integer charId) {
        AtomicInteger counter = hitCounters.get(charId);
        if (counter == null) return -1;

        int hitNumber = counter.incrementAndGet(); // 1 on first hit, 2 on second, …
        int damage = 0;
        for (int i = 0; i < hitNumber; i++) {
            damage += ThreadLocalRandom.current().nextInt(10) + 1;
        }
        logger.debug("[holy avenger] charId={} hit #{} → {}d10 bonus = {}",
                charId, hitNumber, hitNumber, damage);
        return damage;
    }

    // -------------------------------------------------------------------------
    // EffectHandler
    // -------------------------------------------------------------------------

    @Override
    public EffectInstance apply(EffectDefinition def, Integer casterId, Integer targetId,
                                Map<String, String> extraParams) {
        if (casterId == null) return null;

        // Reset (or initialise) the hit counter for this cast.
        hitCounters.put(casterId, new AtomicInteger(0));

        long durationMs = (long) (def.getDurationSeconds() * 1000L);
        long nowMs      = System.currentTimeMillis();
        long expiresAt  = nowMs + durationMs;

        EffectInstance instance = new EffectInstance(
                UUID.randomUUID(),
                def.getId(),
                casterId,
                casterId,
                extraParams != null ? extraParams : Map.of(),
                nowMs,
                expiresAt,
                def.getPriority()
        );

        // Inform the caster
        ClientHandler.sendToCharacter(casterId,
                "\u001B[1;93mYou are suffused with the holy light of vengeance! "
                + "Each strike will burn brighter than the last!\u001B[0m");

        // Room announcement
        ClientHandler clientHandler = ClientHandler.charIdToSession.get(casterId);
        if (clientHandler != null) {
            ClientHandler.roomAnnounceFromActor(clientHandler.currentRoomId,
                    "Blinding holy fire erupts around " + clientHandler.playerName
                    + ", suffusing them with divine vengeance!",
                    casterId);
        }

        return instance;
    }

    @Override
    public void expire(EffectInstance instance) {
        Integer casterId = instance.getCasterId();
        hitCounters.remove(casterId);
        ClientHandler.sendToCharacter(casterId,
                "\u001B[33mThe holy light of vengeance fades from you.\u001B[0m");
    }
}
