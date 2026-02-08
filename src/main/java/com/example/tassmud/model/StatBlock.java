package com.example.tassmud.model;

/**
 * Immutable value object holding the 10 core stat fields shared by
 * {@link GameCharacter}, {@link MobileTemplate}, and CharacterRecord.
 *
 * <p>Ability scores: str, dex, con, intel, wis, cha.
 * <br>Defensive stats: armor, fortitude, reflex, will.
 */
public record StatBlock(
    int str,
    int dex,
    int con,
    int intel,
    int wis,
    int cha,
    int armor,
    int fortitude,
    int reflex,
    int will
) {

    /** A zeroed-out stat block (all stats = 0). */
    public static final StatBlock ZERO = new StatBlock(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    /**
     * Fluent builder to avoid 10-param positional constructor ambiguity.
     */
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int str, dex, con, intel, wis, cha;
        private int armor, fortitude, reflex, will;

        public Builder str(int v)       { this.str = v; return this; }
        public Builder dex(int v)       { this.dex = v; return this; }
        public Builder con(int v)       { this.con = v; return this; }
        public Builder intel(int v)     { this.intel = v; return this; }
        public Builder wis(int v)       { this.wis = v; return this; }
        public Builder cha(int v)       { this.cha = v; return this; }
        public Builder armor(int v)     { this.armor = v; return this; }
        public Builder fortitude(int v) { this.fortitude = v; return this; }
        public Builder reflex(int v)    { this.reflex = v; return this; }
        public Builder will(int v)      { this.will = v; return this; }

        public StatBlock build() {
            return new StatBlock(str, dex, con, intel, wis, cha, armor, fortitude, reflex, will);
        }
    }
}
