package com.example.tassmud.model;

/**
 * Weather types for the game world.
 * Weather affects visibility, certain spells (like Call Lightning), and immersion.
 * 
 * Weather transitions happen every 3 in-game hours (180 real seconds).
 * Special weather types are rare and have unique effects.
 */
public enum Weather {
    // Standard weather patterns
    CLEAR("clear", "Clear Skies", false),
    PARTLY_CLOUDY("partly_cloudy", "Partly Cloudy", false),
    OVERCAST("overcast", "Overcast", false),
    WINDY("windy", "Windy", false),
    RAINY("rainy", "Rainy", false),
    STORMY("stormy", "Stormy", false),
    
    // Special weather patterns (rare, with unique effects)
    SNOWY("snowy", "Snowy", true),
    HURRICANE("hurricane", "Hurricane", true),
    EARTHQUAKE("earthquake", "Earthquake", true),
    VOLCANIC_ASH("volcanic_ash", "Volcanic Ash", true);

    private final String key;
    private final String displayName;
    private final boolean special;

    Weather(String key, String displayName, boolean special) {
        this.key = key;
        this.displayName = displayName;
        this.special = special;
    }

    public String getKey() {
        return key;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Check if this is a special (rare) weather pattern.
     */
    public boolean isSpecial() {
        return special;
    }

    /**
     * Check if this weather allows lightning-based spells (stormy weather).
     */
    public boolean hasLightning() {
        return this == STORMY || this == HURRICANE;
    }

    /**
     * Check if this weather reduces visibility.
     */
    public boolean reducesVisibility() {
        return this == RAINY || this == STORMY || this == SNOWY || 
               this == HURRICANE || this == VOLCANIC_ASH;
    }

    /**
     * Get a description of the current sky/weather for the 'look' command.
     * These are static observations, not transitions.
     */
    public String getSkyDescription() {
        switch (this) {
            case CLEAR:
                return "The sky above is a brilliant, crystal-clear blue. Not a cloud mars the endless expanse.";
            case PARTLY_CLOUDY:
                return "Fluffy white clouds drift lazily across an otherwise blue sky, their shadows dancing over the land below.";
            case OVERCAST:
                return "A thick blanket of grey clouds covers the sky from horizon to horizon, muting the light.";
            case WINDY:
                return "Strong gusts whip across the land, sending dust and leaves spiraling through the air. Clouds race overhead.";
            case RAINY:
                return "Rain falls in steady sheets from the leaden sky, soaking everything below. Puddles form in every depression.";
            case STORMY:
                return "Dark thunderclouds churn overhead, split by brilliant flashes of lightning. Thunder rumbles ominously.";
            case SNOWY:
                return "Snowflakes drift down from a pale grey sky, blanketing the world in pristine white silence.";
            case HURRICANE:
                return "Violent winds howl with terrifying fury as rain lashes horizontally. The sky is a churning maelstrom of black clouds.";
            case EARTHQUAKE:
                return "The sky seems ordinary, but the ground trembles with deep, unsettling vibrations. Dust rises from the shaking earth.";
            case VOLCANIC_ASH:
                return "An eerie twilight pervades as volcanic ash drifts down like grey snow. The air tastes of sulfur and char.";
            default:
                return "The weather is unremarkable.";
        }
    }

    /**
     * Parse weather from a string key.
     */
    public static Weather fromKey(String key) {
        if (key == null) return CLEAR;
        for (Weather w : values()) {
            if (w.key.equalsIgnoreCase(key) || w.name().equalsIgnoreCase(key)) {
                return w;
            }
        }
        return CLEAR;
    }
}
