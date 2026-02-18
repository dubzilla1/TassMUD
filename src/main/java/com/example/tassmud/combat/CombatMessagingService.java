package com.example.tassmud.combat;

import com.example.tassmud.net.ClientHandler;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Formats and dispatches combat-related messages: damage verbs, round
 * results, prompts, HP syncing.
 *
 * Extracted from CombatManager to isolate presentation/messaging concerns
 * from combat orchestration.
 */
public class CombatMessagingService {

    // ── damage verb table ──────────────────────────────────────────────

    /**
     * Damage verb thresholds and their corresponding verbs.
     * Each entry is: [maxDamage, singularVerb, pluralVerb]
     * Sorted by damage threshold ascending.
     */
    private static final Object[][] DAMAGE_VERBS = {
        {0,   "miss",           "misses"},
        {1,   "scratch",        "scratches"},
        {2,   "graze",          "grazes"},
        {3,   "hit",            "hits"},
        {5,   "injure",         "injures"},
        {8,   "wound",          "wounds"},
        {13,  "maul",           "mauls"},
        {20,  "maim",           "maims"},
        {30,  "DEVASTATE",      "DEVASTATES"},
        {40,  "DECIMATE",       "DECIMATES"},
        {50,  "*MUTILATE*",     "*MUTILATES*"},
        {65,  "*DESTROY*",      "*DESTROYS*"},
        {80,  "**EVISCERATE**", "**EVISCERATES**"},
        {100, "**DISEMBOWEL**", "**DISEMBOWELS**"},
        {125, "***MASSACRE***", "***MASSACRES***"},
        {150, "***ANNIHILATE***", "***ANNIHILATES***"},
        {175, "==**DEMOLISH**==", "==**DEMOLISHES**=="},
        {200, "==**ERADICATE**==", "==**ERADICATES**=="},
    };

    /** Default verb for damage over max threshold */
    private static final String DAMAGE_VERB_MAX_SINGULAR = "--==**ATOMIZE**==--";
    private static final String DAMAGE_VERB_MAX_PLURAL = "--==**ATOMIZES**==--";

    // ── instance state ─────────────────────────────────────────────────

    private final BiConsumer<Integer, String> playerMessageCallback;
    private final BiConsumer<Integer, String> roomMessageCallback;
    private final Consumer<Integer> playerPromptCallback;

    public CombatMessagingService(BiConsumer<Integer, String> playerMessageCallback,
                                  BiConsumer<Integer, String> roomMessageCallback,
                                  Consumer<Integer> playerPromptCallback) {
        this.playerMessageCallback = playerMessageCallback;
        this.roomMessageCallback = roomMessageCallback;
        this.playerPromptCallback = playerPromptCallback;
    }

    // ── static utility ─────────────────────────────────────────────────

    /**
     * Get the damage verb for a given damage amount.
     *
     * @param damage   The damage dealt
     * @param singular True for singular form ("I hit"), false for plural ("attack hits")
     * @return The appropriate damage verb
     */
    public static String getDamageVerb(int damage, boolean singular) {
        for (Object[] entry : DAMAGE_VERBS) {
            int threshold = (Integer) entry[0];
            if (damage <= threshold) {
                String verb = singular ? (String) entry[1] : (String) entry[2];
                return com.example.tassmud.util.Colors.dmgVerb(verb, damage);
            }
        }
        // Damage exceeds all thresholds
        String verb = singular ? DAMAGE_VERB_MAX_SINGULAR : DAMAGE_VERB_MAX_PLURAL;
        return com.example.tassmud.util.Colors.dmgVerb(verb, 201);
    }

    // ── low-level messaging helpers ────────────────────────────────────

    public void sendToPlayer(Integer characterId, String message) {
        if (playerMessageCallback != null && characterId != null) {
            playerMessageCallback.accept(characterId, message);
        }
    }

    public void sendPromptToPlayer(Integer characterId) {
        if (playerPromptCallback != null && characterId != null) {
            playerPromptCallback.accept(characterId);
        }
    }

    public void sendPromptsToPlayers(Combat combat) {
        for (Combatant combatant : combat.getPlayerCombatants()) {
            if (combatant.isActive() && combatant.isAlive()) {
                sendPromptToPlayer(combatant.getCharacterId());
            }
        }
    }

    /** Send prompts to all surviving players (used at end of combat when isActive is false). */
    public void sendPromptsToSurvivingPlayers(Combat combat) {
        for (Combatant combatant : combat.getPlayerCombatants()) {
            if (combatant.isAlive()) {
                sendPromptToPlayer(combatant.getCharacterId());
            }
        }
    }

    public void broadcastToRoom(int roomId, String message) {
        if (roomMessageCallback != null) {
            roomMessageCallback.accept(roomId, message);
        }
    }

    /**
     * Sync a player combatant's HP/MP/MV to the database.
     * This ensures the prompt and game state reflect combat damage.
     */
    public void syncPlayerHpToDatabase(Combatant player) {
        if (!player.isPlayer()) return;
        com.example.tassmud.model.GameCharacter c = player.getAsCharacter();
        if (c == null) return;

        com.example.tassmud.persistence.CharacterDAO dao =
                com.example.tassmud.persistence.DaoProvider.characters();
        dao.saveCharacterStateByName(
                c.getName(),
                c.getHpCur(),
                c.getMpCur(),
                c.getMvCur(),
                c.getCurrentRoom()
        );
    }

    // ── combat result broadcast ────────────────────────────────────────

    /**
     * Format and broadcast a {@link CombatResult} to the combat room.
     * Handles all result types (HIT, MISS, DEATH, HEAL, etc.) and sends
     * debug info and Insight-effect HP display as needed.
     */
    public void broadcastCombatResult(Combat combat, CombatResult result) {
        String attackerName = result.getAttacker() != null ? result.getAttacker().getName() : "Someone";
        String targetName  = result.getTarget()   != null ? result.getTarget().getName()   : "something";

        String message;
        switch (result.getType()) {
            case HIT:
                message = "%s's attack %s %s!".formatted(
                    attackerName, getDamageVerb(result.getDamage(), false), targetName);
                break;
            case CRITICAL_HIT:
                message = "CRITICAL! %s's attack %s %s!".formatted(
                    attackerName, getDamageVerb(result.getDamage(), false), targetName);
                break;
            case MISS:
                message = "%s's attack %s %s!".formatted(
                    attackerName, getDamageVerb(0, false), targetName);
                break;
            case SHRUGGED_OFF:
                message = "%s's melee attack is shrugged off by %s!".formatted(
                    attackerName, targetName);
                break;
            case DODGED:
                message = "%s's ranged attack is dodged by %s!".formatted(
                    attackerName, targetName);
                break;
            case RESISTED:
                message = "%s's magical attack is resisted by %s!".formatted(
                    attackerName, targetName);
                break;
            case BLOCKED:
                message = "%s's attack is blocked by %s!".formatted(
                    attackerName, targetName);
                break;
            case PARRIED:
                message = "%s's attack is parried by %s!".formatted(
                    attackerName, targetName);
                break;
            case DEFLECTED:
                message = "%s's ranged attack is deflected by %s!".formatted(
                    attackerName, targetName);
                break;
            case HEAL:
                message = "%s heals %s for %d HP!".formatted(
                    attackerName, targetName, result.getHealing());
                break;
            case DEATH:
                // Show the killing blow damage before the death message
                message = "%s's attack %s %s!".formatted(
                    attackerName, getDamageVerb(result.getDamage(), false), targetName);
                broadcastToRoom(combat.getRoomId(), message);
                combat.logEvent(message);

                // Send debug info for the killing blow
                String deathDebugInfo = result.getDebugInfo();
                if (deathDebugInfo != null && !deathDebugInfo.isEmpty()) {
                    ClientHandler.sendDebugToRoom(combat.getRoomId(), deathDebugInfo);
                }

                // Death itself is handled separately in DeathHandler
                return;
            default:
                message = result.getRoomMessage();
                if (message == null) return;
        }

        broadcastToRoom(combat.getRoomId(), message);
        combat.logEvent(message);

        // Send debug info to players with debug channel enabled
        String debugInfo = result.getDebugInfo();
        if (debugInfo != null && !debugInfo.isEmpty()) {
            ClientHandler.sendDebugToRoom(combat.getRoomId(), debugInfo);
        }

        // Show target's HP to attacker if they're a player with Insight effect
        if (result.getAttacker() != null && result.getAttacker().isPlayer() &&
                result.getAttacker().getCharacterId() != null && result.getTarget() != null) {
            Integer attackerId = result.getAttacker().getCharacterId();
            if (com.example.tassmud.effect.EffectRegistry.hasInsight(attackerId)) {
                Combatant target = result.getTarget();
                String hpMsg = "  %s: %d/%d HP".formatted(
                    target.getName(), target.getHpCurrent(), target.getHpMax());
                sendToPlayer(attackerId, hpMsg);
            }
        }
    }
}
