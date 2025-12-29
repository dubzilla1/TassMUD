package com.example.tassmud.model;

public enum Stat {
    STRENGTH,
    DEXTERITY,
    CONSTITUTION,
    INTELLIGENCE,
    WISDOM,
    CHARISMA,

    HP_MAX,
    HP_CURRENT,
    MP_MAX,
    MP_CURRENT,
    MV_MAX,
    MV_CURRENT,

    ARMOR,
    FORTITUDE,
    REFLEX,
    WILL
    ,
    // Combat and spell bonuses (modifiable via Modifier system)
    ATTACK_HIT_BONUS,
    ATTACK_DAMAGE_BONUS,
    SPELL_HIT_BONUS,
    SPELL_DAMAGE_BONUS,
    MELEE_DAMAGE_REDUCTION,
    RANGED_DAMAGE_REDUCTION,
    SPELL_DAMAGE_REDUCTION,
    
    /** Reduces the natural roll needed for a critical hit (default 0, so crit on 20; -1 means crit on 19+) */
    CRITICAL_THRESHOLD_BONUS
}
