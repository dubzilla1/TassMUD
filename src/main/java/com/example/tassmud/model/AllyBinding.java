package com.example.tassmud.model;

/**
 * Represents a single NPC-ally-to-player relationship.
 *
 * <p>An {@code AllyBinding} is created when a mob becomes allied with a player
 * (via a skill, quest, event, or GM command) and destroyed when that relationship
 * ends.  All live bindings are managed by {@link com.example.tassmud.util.AllyManager}.
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *   create binding  ──►  mob alive, follows/defends owner
 *                             │
 *                   mob dies ─┤
 *                             │
 *              TEMPORARY ────►│  binding removed
 *              PERMANENT ────►│  binding kept; re-attaches on respawn
 * </pre>
 */
public class AllyBinding {

    // ── identity ──────────────────────────────────────────────────────

    /** Instance ID of the bound mob (Mobile.instanceId — long). */
    private final long mobInstanceId;

    /** Character ID of the owning player (GameCharacter.characterId — int). */
    private final int ownerCharacterId;

    /**
     * Template ID of the mob.  Used for PERMANENT allies to re-bind a newly
     * spawned instance to the same owner (mob spawned → same template → re-attach).
     */
    private final long mobTemplateId;

    // ── binding properties ────────────────────────────────────────────

    /** Current combat posture. Mutable — can be changed at runtime. */
    private AllyBehavior behavior;

    /** Controls what happens when the ally dies. */
    private final AllyPersistence persistence;

    /**
     * When {@code true} the ally will follow the owner between rooms.
     * When {@code false} (STAY) the ally remains in its current room.
     * Defaults to {@code true} on creation.
     */
    private boolean followsOwner;

    /**
     * When {@code true} the ally will obey {@code order} commands from its owner.
     * Some classes (Ranger, Warlock, Necromancer) enable this; a temporary city
     * guard would have {@code obeys = false}.
     */
    private boolean obeys;

    /**
     * Unix epoch ms at which this binding auto-expires, or {@code 0} for no expiry.
     * Used for timed buffs like a city guard escorting a player for 60 seconds.
     */
    private final long expiresAt;

    /** Unix epoch ms when the binding was created (for logging/display). */
    private final long boundAt;

    /**
     * True if this binding was created by the Ranger's Tame skill (companion).
     * Distinguishes tamed companions from undead thralls or escort NPCs.
     */
    private final boolean tamedCompanion;

    /** Player-given name for a tamed companion; null until the player names them. */
    private String companionName;

    // ── constructor ───────────────────────────────────────────────────

    public AllyBinding(long mobInstanceId,
                       int ownerCharacterId,
                       long mobTemplateId,
                       AllyBehavior behavior,
                       AllyPersistence persistence,
                       boolean followsOwner,
                       boolean obeys,
                       long expiresAt) {
        this(mobInstanceId, ownerCharacterId, mobTemplateId, behavior, persistence,
             followsOwner, obeys, expiresAt, false, null);
    }

    private AllyBinding(long mobInstanceId,
                        int ownerCharacterId,
                        long mobTemplateId,
                        AllyBehavior behavior,
                        AllyPersistence persistence,
                        boolean followsOwner,
                        boolean obeys,
                        long expiresAt,
                        boolean tamedCompanion,
                        String companionName) {
        this.mobInstanceId = mobInstanceId;
        this.ownerCharacterId = ownerCharacterId;
        this.mobTemplateId = mobTemplateId;
        this.behavior = behavior;
        this.persistence = persistence;
        this.followsOwner = followsOwner;
        this.obeys = obeys;
        this.expiresAt = expiresAt;
        this.boundAt = System.currentTimeMillis();
        this.tamedCompanion = tamedCompanion;
        this.companionName = companionName;
    }

    // ── convenience factories ─────────────────────────────────────────

    /**
     * Create a temporary, passive escort binding (e.g. city guard, lost NPC).
     * Does not follow the owner; does not obey orders; expires with death.
     */
    public static AllyBinding temporary(long mobInstanceId, int ownerCharacterId, long mobTemplateId) {
        return new AllyBinding(mobInstanceId, ownerCharacterId, mobTemplateId,
                AllyBehavior.PASSIVE, AllyPersistence.TEMPORARY, true, false, 0L);
    }

    /**
     * Create a DEFENDER binding that auto-defends the owner and persists across death.
     * Follows the owner and obeys orders (e.g. summoned pet, undead thrall).
     */
    public static AllyBinding permanentDefender(long mobInstanceId, int ownerCharacterId, long mobTemplateId) {
        return new AllyBinding(mobInstanceId, ownerCharacterId, mobTemplateId,
                AllyBehavior.DEFENDER, AllyPersistence.PERMANENT, true, true, 0L);
    }

    /**
     * Create a tamed companion binding for the Ranger's Tame skill.
     * DEFENDER (joins combat), TEMPORARY (companion dies for real), follows and obeys.
     * Call {@link #setCompanionName(String)} once the player provides a name.
     */
    public static AllyBinding tamedCompanion(long mobInstanceId, int ownerCharacterId, long mobTemplateId) {
        return new AllyBinding(mobInstanceId, ownerCharacterId, mobTemplateId,
                AllyBehavior.DEFENDER, AllyPersistence.TEMPORARY, true, true, 0L,
                true, null);
    }

    // ── state queries ─────────────────────────────────────────────────

    /** Returns {@code true} if this binding has passed its expiry timestamp. */
    public boolean isExpired() {
        return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
    }

    /**
     * Returns {@code true} if this ally should flee to join its owner when the
     * owner flees from combat.  PASSIVE/DEFENDER allies that are PERMANENT flee
     * with their owner; TEMPORARY allies stay behind.
     */
    public boolean shouldFleeWithOwner() {
        return persistence == AllyPersistence.PERMANENT;
    }

    /**
     * Returns {@code true} if the binding should be kept after the ally dies
     * (for PERMANENT allies awaiting respawn).
     */
    public boolean shouldSurviveDeath() {
        return persistence == AllyPersistence.PERMANENT;
    }

    /** Returns {@code true} if this ally should auto-join combat when the owner enters combat. */
    public boolean shouldAutoDefend() {
        return behavior == AllyBehavior.DEFENDER || behavior == AllyBehavior.HUNTER;
    }

    // ── accessors ─────────────────────────────────────────────────────

    public long getMobInstanceId() {
        return mobInstanceId;
    }

    public int getOwnerCharacterId() {
        return ownerCharacterId;
    }

    public long getMobTemplateId() {
        return mobTemplateId;
    }

    public AllyBehavior getBehavior() {
        return behavior;
    }

    /** Change the ally's combat posture at runtime (e.g. via {@code order} command). */
    public void setBehavior(AllyBehavior behavior) {
        this.behavior = behavior;
    }

    public AllyPersistence getPersistence() {
        return persistence;
    }

    public boolean isFollowsOwner() {
        return followsOwner;
    }

    /** Toggle follow/stay mode, e.g. in response to {@code order <ally> stay}. */
    public void setFollowsOwner(boolean followsOwner) {
        this.followsOwner = followsOwner;
    }

    public boolean isObeys() {
        return obeys;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public long getBoundAt() {
        return boundAt;
    }

    /** Returns true if this ally was created by the Ranger's Tame skill. */
    public boolean isTamedCompanion() {
        return tamedCompanion;
    }

    /** Returns the player-given companion name, or null if not yet named. */
    public String getCompanionName() {
        return companionName;
    }

    /** Set the player-given companion name after the player enters it. */
    public void setCompanionName(String name) {
        this.companionName = name;
    }

    @Override
    public String toString() {
        return "AllyBinding{mobInstanceId=" + mobInstanceId
                + ", owner=" + ownerCharacterId
                + ", behavior=" + behavior
                + ", persistence=" + persistence
                + ", follows=" + followsOwner
                + ", obeys=" + obeys
                + "}";
    }
}
