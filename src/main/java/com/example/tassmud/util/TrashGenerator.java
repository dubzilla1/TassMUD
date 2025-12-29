package com.example.tassmud.util;

import java.util.Random;

/**
 * Generates comically absurd trash items using a Mad Libs-style approach.
 * 
 * Format: "A <adjective> <noun> <suffix>"
 * 
 * Examples:
 * - A moldy sandwich covered in glitter
 * - A suspicious sock that whispers secrets
 * - A deflated balloon full of regrets
 */
public class TrashGenerator {
    
    private static final Random RNG = new Random();
    
    // ============================================================
    // ADJECTIVES - Describing the state/quality of the trash
    // ============================================================
    private static final String[] ADJECTIVES = {
        // Condition adjectives
        "rusty", "moldy", "crusty", "dusty", "musty", "grimy", "slimy",
        "cracked", "broken", "bent", "dented", "squished", "flattened",
        "soggy", "damp", "dripping", "oozing", "leaking", "sticky",
        "tattered", "frayed", "ripped", "torn", "shredded", "mangled",
        "faded", "stained", "discolored", "yellowed", "blackened",
        
        // Size adjectives
        "tiny", "minuscule", "microscopic", "enormous", "gigantic", "oversized",
        "lopsided", "crooked", "wonky", "misshapen", "lumpy", "bulging",
        
        // Smell adjectives
        "smelly", "stinky", "pungent", "aromatic", "fragrant", "rancid",
        "putrid", "fetid", "noxious", "eye-watering", "nostril-burning",
        
        // Emotional/personality adjectives
        "sad", "lonely", "forgotten", "abandoned", "neglected", "forlorn",
        "suspicious", "questionable", "dubious", "sketchy", "mysterious",
        "cursed", "haunted", "possessed", "enchanted", "bewildered",
        "confused", "disappointed", "regretful", "apologetic", "embarrassed",
        
        // Temperature adjectives
        "lukewarm", "tepid", "slightly-warm", "room-temperature", "inexplicably-cold",
        
        // Texture adjectives
        "fuzzy", "furry", "hairy", "bristly", "prickly", "scratchy",
        "gooey", "gelatinous", "rubbery", "leathery", "crusty", "flaky",
        
        // Color adjectives
        "off-white", "beige", "puce", "mauve", "chartreuse", "taupe",
        "vaguely-green", "suspiciously-brown", "unnaturally-orange",
        
        // Absurd adjectives
        "slightly-sentient", "vaguely-threatening", "mildly-radioactive",
        "possibly-alive", "definitely-haunted", "somewhat-magical",
        "inexplicably-vibrating", "faintly-glowing", "quietly-humming",
        "occasionally-screaming", "perpetually-damp", "eternally-sticky"
    };
    
    // ============================================================
    // NOUNS - The base trash item
    // ============================================================
    private static final String[] NOUNS = {
        // Clothing items
        "sock", "shoe", "boot", "sandal", "slipper", "hat", "cap", "bonnet",
        "glove", "mitten", "scarf", "belt", "suspenders", "underwear",
        
        // Kitchen items
        "spoon", "fork", "spork", "ladle", "spatula", "whisk", "rolling pin",
        "pot", "pan", "lid", "bowl", "plate", "cup", "mug", "teapot",
        "bottle", "jar", "container", "tupperware", "colander",
        
        // Office items
        "stapler", "paperclip", "rubber band", "pencil", "pen", "eraser",
        "notebook", "folder", "binder", "tape dispenser", "hole puncher",
        
        // Household items
        "broom", "mop", "bucket", "sponge", "brush", "dustpan",
        "candle", "candlestick", "lamp", "lightbulb", "doorknob",
        "picture frame", "vase", "flowerpot", "umbrella", "pillow",
        
        // Toys and games
        "doll", "teddy bear", "yo-yo", "spinning top", "puzzle piece",
        "dice", "playing card", "marble", "bouncy ball", "action figure",
        
        // Food items (expired/inedible)
        "sandwich", "muffin", "cupcake", "cookie", "cracker", "pretzel",
        "banana peel", "apple core", "cheese wheel", "bread loaf", "pickle",
        
        // Random objects
        "wheel", "cog", "spring", "button", "zipper", "buckle", "thimble",
        "bobbin", "spool", "cork", "stopper", "plug", "widget", "doohickey",
        "thingamajig", "whatchamacallit", "gizmo", "contraption",
        
        // Nature items
        "rock", "pebble", "stick", "twig", "leaf", "acorn", "pinecone",
        "seashell", "feather", "bone", "tooth", "claw",
        
        // Containers
        "box", "crate", "bag", "sack", "pouch", "envelope", "package",
        
        // Musical
        "kazoo", "whistle", "tambourine", "triangle", "harmonica",
        
        // Absurd items
        "mystery lump", "unidentified blob", "thing", "object", "item",
        "artifact", "relic", "specimen", "sample", "exhibit"
    };
    
    // ============================================================
    // SUFFIX PATTERNS - What makes it truly ridiculous
    // ============================================================
    
    // "made of <material>"
    private static final String[] MATERIALS = {
        "toothpicks", "paperclips", "rubber bands", "string cheese",
        "disappointment", "broken dreams", "good intentions", "pure spite",
        "recycled homework", "melted crayons", "dried pasta", "pocket lint",
        "old newspapers", "bubble gum", "duct tape", "cardboard",
        "mystery meat", "solidified gravy", "compressed dust bunnies",
        "fossilized snacks", "crystallized tears", "hardened oatmeal",
        "petrified cheese", "ancient bubblegum", "forgotten promises"
    };
    
    // "full of <contents>"
    private static final String[] CONTENTS = {
        "holes", "regrets", "smaller holes", "expired coupons",
        "lost buttons", "forgotten memories", "spare change", "lint",
        "mystery crumbs", "ancient raisins", "fossilized cheerios",
        "dust", "cobwebs", "broken promises", "shattered dreams",
        "bad decisions", "unread emails", "unpaid bills", "loose threads",
        "miscellaneous goo", "suspicious liquid", "unidentifiable chunks",
        "tiny spiders", "old receipts", "random seeds", "pocket sand"
    };
    
    // "covered in <coating>"
    private static final String[] COATINGS = {
        "glitter", "boogers", "mysterious stains", "dust", "cobwebs",
        "sticky residue", "dried ketchup", "ancient mustard", "old jam",
        "questionable goop", "unidentified slime", "dried tears",
        "crayon marks", "coffee rings", "grease spots", "fingerprints",
        "nose prints", "paw prints", "suspicious smudges", "unknown powder",
        "crystallized sugar", "dried syrup", "ancient honey", "mold spores"
    };
    
    // "that <action>"
    private static final String[] ACTIONS = {
        "whispers secrets", "judges you silently", "hums tunelessly",
        "vibrates slightly", "glows faintly", "smells of despair",
        "radiates disappointment", "exudes mild anxiety", "oozes slowly",
        "makes concerning noises", "seems to be watching", "feels warm",
        "defies gravity slightly", "exists reluctantly", "persists stubbornly",
        "refuses to be thrown away", "keeps reappearing", "moves when unobserved",
        "attracts flies", "repels happiness", "absorbs light",
        "tastes like nostalgia", "sounds like regret", "feels like Monday",
        "smells like your aunt's house", "reminds you of homework"
    };
    
    // "from <origin>"
    private static final String[] ORIGINS = {
        "a haunted garage sale", "the bottom of a very old bag",
        "someone's questionable collection", "a forgotten dimension",
        "the back of a mysterious drawer", "under someone's childhood bed",
        "a wizard's junk pile", "a dragon's rejected hoard",
        "the Island of Misfit Toys", "a parallel universe",
        "the future", "the distant past", "an alternate timeline",
        "a goblin's pocket", "an ogre's belly button",
        "the lost and found of destiny", "a discount dimension",
        "the clearance section of reality", "somewhere you don't want to know"
    };
    
    // "with <feature>"
    private static final String[] FEATURES = {
        "googly eyes", "unnecessary teeth", "vestigial handles",
        "an inexplicable odor", "a faint heartbeat", "residual magic",
        "emotional baggage", "trust issues", "abandonment issues",
        "a mysterious inscription", "strange markings", "tiny footprints",
        "bite marks", "claw scratches", "burn marks", "water damage",
        "a name tag that says 'Gerald'", "a 'best before 1847' sticker",
        "a warranty that expired centuries ago", "assembly instructions in an unknown language"
    };
    
    // "once belonging to <owner>"
    private static final String[] OWNERS = {
        "a very forgetful wizard", "someone's disappointed grandmother",
        "a retired adventurer", "a goblin hoarder", "a confused merchant",
        "someone who clearly gave up", "an optimistic pessimist",
        "a collector of bad decisions", "a professional disappointment",
        "the world's worst treasure hunter", "a dragon with poor taste",
        "an ogre's sophisticated cousin", "a troll art critic",
        "someone named 'Gerald'", "your future self", "nobody important"
    };
    
    /**
     * Result of trash generation.
     */
    public static class GeneratedTrash {
        public final String name;
        public final String description;
        
        public GeneratedTrash(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }
    
    /**
     * Generate a random piece of comical trash.
     */
    public static GeneratedTrash generate() {
        String adjective = pick(ADJECTIVES);
        String noun = pick(NOUNS);
        String suffix = generateSuffix();
        
        // Build the name: "A <adjective> <noun>"
        // Handle "an" vs "a" based on adjective starting with vowel
        String article = startsWithVowelSound(adjective) ? "An" : "A";
        String name = article + " " + adjective + " " + noun;
        
        // Build the description with the suffix
        String description = name + " " + suffix + ".";
        
        // Sometimes add an extra observation
        if (RNG.nextInt(100) < 30) {
            description += " " + generateObservation();
        }
        
        return new GeneratedTrash(name, description);
    }
    
    /**
     * Generate a random suffix for the trash description.
     */
    private static String generateSuffix() {
        int pattern = RNG.nextInt(8);
        
        switch (pattern) {
            case 0: return "made of " + pick(MATERIALS);
            case 1: return "full of " + pick(CONTENTS);
            case 2: return "covered in " + pick(COATINGS);
            case 3: return "that " + pick(ACTIONS);
            case 4: return "from " + pick(ORIGINS);
            case 5: return "with " + pick(FEATURES);
            case 6: return "once belonging to " + pick(OWNERS);
            default:
                // Combo suffix: pick two different patterns
                int p1 = RNG.nextInt(7);
                int p2 = (p1 + 1 + RNG.nextInt(6)) % 7;
                return getSuffixByPattern(p1) + " and " + getSuffixByPattern(p2);
        }
    }
    
    private static String getSuffixByPattern(int pattern) {
        switch (pattern) {
            case 0: return "made of " + pick(MATERIALS);
            case 1: return "full of " + pick(CONTENTS);
            case 2: return "covered in " + pick(COATINGS);
            case 3: return "that " + pick(ACTIONS);
            case 4: return "from " + pick(ORIGINS);
            case 5: return "with " + pick(FEATURES);
            case 6: return "once belonging to " + pick(OWNERS);
            default: return "of unknown origin";
        }
    }
    
    /**
     * Generate an extra observation about the trash.
     */
    private static String generateObservation() {
        String[] observations = {
            "You're not sure why you picked this up.",
            "It seems to be staring at you.",
            "You feel slightly worse for having looked at it.",
            "It smells exactly like you expected.",
            "Someone probably wants this back. Probably.",
            "This definitely counts as treasure. Definitely.",
            "Your mother would be so proud.",
            "A collector might pay... actually, no.",
            "It has a certain charm. A very certain, specific charm.",
            "You can't shake the feeling it's judging you.",
            "It vibrates with potential. Or maybe that's just mold.",
            "Legend speaks of this... wait, no it doesn't.",
            "The craftsmanship is... present.",
            "It's exactly as disappointing as it looks.",
            "Somewhere, someone is missing this. They shouldn't be.",
            "It exudes an aura of mild inconvenience.",
            "You've made worse decisions. Probably.",
            "This belongs in a museum. A very bad museum.",
            "It's worth its weight in... nothing, really.",
            "The previous owner clearly had impeccable taste. Clearly."
        };
        return observations[RNG.nextInt(observations.length)];
    }
    
    /**
     * Check if a word starts with a vowel sound (for a/an selection).
     */
    private static boolean startsWithVowelSound(String word) {
        if (word == null || word.isEmpty()) return false;
        char first = Character.toLowerCase(word.charAt(0));
        return first == 'a' || first == 'e' || first == 'i' || first == 'o' || first == 'u';
    }
    
    /**
     * Pick a random element from an array.
     */
    private static String pick(String[] array) {
        return array[RNG.nextInt(array.length)];
    }
    
    /**
     * Generate multiple unique trash items (for testing/fun).
     */
    public static void main(String[] args) {
        org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(TrashGenerator.class);
        logger.info("=== TRASH GENERATOR TEST ===\n");
        for (int i = 0; i < 20; i++) {
            GeneratedTrash trash = generate();
            logger.info(trash.name);
            logger.info("  {}", trash.description);
            logger.info("");
        }
    }
}
