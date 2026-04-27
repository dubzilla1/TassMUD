package com.example.tassmud.model;

import java.util.Locale;
import java.util.Set;

/**
 * Broad category of a mobile, used by class skills and abilities that target
 * specific creature types (e.g. Ranger's Favored Enemy, Druid wild empathy,
 * paladin smite — all depend on knowing what kind of creature a mob is).
 *
 * <h3>How the value is resolved (in priority order):</h3>
 * <ol>
 *   <li>Explicit {@code mob_type:} field in the YAML mobile definition.</li>
 *   <li>Keyword inference: if the mob's name / keywords contain any of the
 *       words associated with a type, that type is inferred automatically.</li>
 *   <li>{@link #HUMANOID} — the safe default for anything unclassified.</li>
 * </ol>
 *
 * <h3>Extending:</h3>
 * Add a new enum constant with its keyword set. No other code needs to change
 * unless a skill explicitly checks that type by name.
 */
public enum MobType {

    // ── Natural creatures ─────────────────────────────────────────────────────

    /** Mundane non-magical animals (wolves, bears, hawks, fish…). */
    ANIMAL(Set.of(
        "wolf", "wolves", "bear", "boar", "stag", "deer", "elk", "moose",
        "rabbit", "hare", "squirrel", "fox", "badger", "ferret",
        "hawk", "eagle", "falcon", "owl", "raven", "crow", "sparrow",
        "snake", "serpent", "viper", "cobra", "python",
        "rat", "mouse", "bat", "weasel", "otter", "beaver",
        "cat", "dog", "horse", "pony", "mule", "donkey", "cow", "pig",
        "shark", "fish", "eel", "crab", "lobster",
        "lion", "tiger", "leopard", "panther", "cheetah",
        "gorilla", "ape", "monkey", "baboon",
        "crocodile", "alligator", "lizard", "gecko", "turtle", "tortoise",
        "beetle", "spider", "scorpion", "centipede", "wasp", "bee"
    )),

    /**
     * Beasts with innate magical qualities (griffon, basilisk, pegasus…).
     * Natural-ish but touched by magic.
     */
    MAGICAL_BEAST(Set.of(
        "griffon", "gryphon", "basilisk", "cockatrice", "manticore",
        "wyvern", "pegasus", "unicorn", "hippogriff",
        "peryton", "owlbear", "displacer", "blink dog", "phase spider",
        "bulette", "rust monster", "roper", "darkmantle",
        "giant", "ettercap", "ankheg", "carrion crawler",
        "purple worm", "remorhaz", "shambling mound",
        "hydra", "chimera"
    )),

    /** All true dragons (including drakes and wyrms). */
    DRAGON(Set.of(
        "dragon", "drake", "wyrm", "dracolich", "wyvern",
        "dragonling", "dragonet", "dragonspawn", "draconian",
        "chromatic", "metallic"
    )),

    // ── Humanoids & peoples ───────────────────────────────────────────────────

    /**
     * Standard bipedal humanoid races and their direct variants.
     * This is also the default type when nothing else matches.
     */
    HUMANOID(Set.of(
        "human", "elf", "dwarf", "halfling", "gnome", "half-elf", "half-orc",
        "orc", "goblin", "hobgoblin", "bugbear", "kobold",
        "gnoll", "lizardman", "lizard man", "troglodyte",
        "man", "woman", "boy", "girl", "villager", "peasant",
        "guard", "soldier", "warrior", "knight", "thief", "rogue", "mage",
        "merchant", "shopkeeper", "innkeeper", "blacksmith", "farmer",
        "priest", "cleric", "druid", "bard", "ranger", "paladin",
        "bandit", "brigand", "pirate", "sailor", "adventurer"
    )),

    /** Giants and giant-kin (hill giant, stone giant, ogre, troll…). */
    GIANT(Set.of(
        "giant", "ogre", "troll", "ettin", "cyclops", "titan",
        "hill giant", "stone giant", "frost giant", "fire giant",
        "cloud giant", "storm giant"
    )),

    // ── Undead ────────────────────────────────────────────────────────────────

    /** All forms of undead (corporeal and incorporeal). */
    UNDEAD(Set.of(
        "skeleton", "zombie", "ghoul", "ghast", "wight", "wraith",
        "specter", "spectre", "shadow", "vampire", "lich", "mummy",
        "revenant", "death knight", "banshee", "poltergeist",
        "bone golem", "skeletal", "undead", "risen", "restless"
    )),

    // ── Planar / outsiders ────────────────────────────────────────────────────

    /** Demons, devils, and other denizens of the lower planes. */
    FIEND(Set.of(
        "demon", "devil", "imp", "succubus", "incubus", "balor",
        "pit fiend", "infernal", "hellhound", "hell hound",
        "nightmare", "quasit", "vrock", "glabrezu", "marilith",
        "erinye", "erinyes", "barbed devil", "bone devil", "ice devil"
    )),

    /** Angels, celestials, and creatures from the upper planes. */
    CELESTIAL(Set.of(
        "angel", "celestial", "archon", "solar", "planetar", "deva",
        "couatl", "pegasus", "unicorn", "hollyphant", "lammasu", "shedu"
    )),

    /** Fey creatures from the Feywild / faerie realm. */
    FEY(Set.of(
        "fey", "faerie", "fairy", "pixie", "sprite", "brownie", "hag",
        "satyr", "dryad", "nymph", "nixie", "leprechaun", "grig",
        "quickling", "green hag", "sea hag", "night hag", "annis"
    )),

    // ── Constructs, plants, oozes ─────────────────────────────────────────────

    /** Golems and other magical constructs. */
    CONSTRUCT(Set.of(
        "golem", "automaton", "animated", "homunculus",
        "iron golem", "stone golem", "clay golem", "flesh golem",
        "shield guardian", "retriever"
    )),

    /** Fungi, treants, and other plant-based creatures. */
    PLANT(Set.of(
        "treant", "shambler", "myconid", "fungus", "vine",
        "plant", "thorn", "moss", "algae", "blight"
    )),

    /** Oozes, slimes, puddings, and jellies. */
    OOZE(Set.of(
        "ooze", "slime", "pudding", "jelly", "blob",
        "ochre jelly", "black pudding", "gelatinous",
        "gray ooze", "green slime"
    )),

    // ── Aberrations ───────────────────────────────────────────────────────────

    /** Aberrations from the Far Realm or otherwise mind-breaking origins. */
    ABERRATION(Set.of(
        "mind flayer", "illithid", "beholder", "aboleth", "chuul",
        "grell", "otyugh", "nothic", "gibbering", "star spawn",
        "aberration", "aberrant"
    ));

    // ── Implementation ────────────────────────────────────────────────────────

    private final Set<String> inferenceKeywords;

    MobType(Set<String> inferenceKeywords) {
        this.inferenceKeywords = inferenceKeywords;
    }

    /**
     * Parse a YAML / DB string into a {@code MobType}, ignoring case.
     * Returns {@code null} if the string does not match any known type.
     */
    public static MobType fromString(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return MobType.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Infer a mob type from a name and/or keyword list.
     * Checks every {@code MobType} in declaration order; the first that has any
     * matching keyword wins.  Evaluation order matters: DRAGON is checked before
     * MAGICAL_BEAST so that a "wyvern" lands on DRAGON.
     *
     * @param name     the mob's display name (may be {@code null})
     * @param keywords the mob's targeting keywords (may be {@code null})
     * @return the inferred type, or {@link #HUMANOID} if nothing matches
     */
    public static MobType infer(String name, java.util.List<String> keywords) {
        // Build a combined lowercase token set from name words + keywords
        java.util.Set<String> tokens = new java.util.HashSet<>();
        if (name != null) {
            for (String word : name.toLowerCase(Locale.ROOT).split("\\s+")) {
                tokens.add(word);
            }
        }
        if (keywords != null) {
            for (String kw : keywords) {
                if (kw != null) tokens.add(kw.toLowerCase(Locale.ROOT));
            }
        }

        for (MobType type : MobType.values()) {
            for (String keyword : type.inferenceKeywords) {
                if (tokens.contains(keyword)) {
                    return type;
                }
            }
        }
        return HUMANOID;
    }

    // ── Convenience category checks ───────────────────────────────────────────

    /** True for ANIMAL and MAGICAL_BEAST — creatures found in the natural world. */
    public boolean isNaturalCreature() {
        return this == ANIMAL || this == MAGICAL_BEAST;
    }

    /** True for types that live in the wild and can be tracked / calmed by nature classes. */
    public boolean isWildlife() {
        return this == ANIMAL || this == MAGICAL_BEAST || this == DRAGON;
    }

    /** True for any outsider from a non-material plane. */
    public boolean isPlanar() {
        return this == FIEND || this == CELESTIAL || this == FEY;
    }

    /** True for types that are typically immune to poison and disease. */
    public boolean isImmuneToPoison() {
        return this == UNDEAD || this == CONSTRUCT || this == OOZE || this == PLANT;
    }

    /** True for types that are typically immune to sleep and charm effects. */
    public boolean isImmuneToCharm() {
        return this == UNDEAD || this == CONSTRUCT || this == PLANT || this == ABERRATION;
    }

    /** True for types that have no mind and are immune to mind-affecting effects. */
    public boolean isMindles() {
        return this == CONSTRUCT || this == OOZE || this == PLANT;
    }

    /** Human-readable display name (capitalised enum name). */
    public String displayName() {
        String n = name();
        return n.charAt(0) + n.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
