package com.example.tassmud.util;

import com.example.tassmud.model.RoomFlag;
import com.example.tassmud.model.Stance;
import com.example.tassmud.model.Weather;
import com.example.tassmud.net.ClientHandler;
import com.example.tassmud.persistence.CharacterDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.Set;

/**
 * Weather system for TassMUD.
 * 
 * Weather changes every 3 in-game hours (180 real seconds = 3 real minutes).
 * Weather transitions follow probability tables based on current weather.
 * Players outdoors (not in INDOORS rooms) and not sleeping receive notifications.
 * 
 * Weather affects:
 * - Call Lightning spell (requires STORMY or HURRICANE)
 * - Visibility (some weather reduces visibility)
 * - Future: movement speed, mob behavior, etc.
 */
public class WeatherService {
    private static final Logger logger = LoggerFactory.getLogger(WeatherService.class);
    
    // Singleton instance
    private static WeatherService instance;
    
    // 3 in-game hours = 180 in-game minutes = 180 real seconds
    private static final long WEATHER_CHANGE_INTERVAL_MS = 180_000;
    
    private final CharacterDAO dao;
    private final Random random = new Random();
    
    private volatile Weather currentWeather = Weather.CLEAR;
    private volatile boolean running = false;
    
    private WeatherService(TickService tickService, CharacterDAO dao) {
        this.dao = dao;
        loadWeather();
        
        // Schedule weather changes every 3 in-game hours (180 seconds)
        tickService.scheduleAtFixedRate("weather-service", this::tick, 
                WEATHER_CHANGE_INTERVAL_MS, WEATHER_CHANGE_INTERVAL_MS);
        this.running = true;
        
        logger.info("[WeatherService] Started. Current weather: {}", currentWeather.getDisplayName());
    }
    
    /**
     * Initialize the singleton instance.
     */
    public static synchronized WeatherService init(TickService tickService, CharacterDAO dao) {
        if (instance == null) {
            instance = new WeatherService(tickService, dao);
        }
        return instance;
    }
    
    /**
     * Get the singleton instance. Returns null if not initialized.
     */
    public static WeatherService getInstance() {
        return instance;
    }
    
    /**
     * Get the current weather.
     */
    public Weather getCurrentWeather() {
        return currentWeather;
    }
    
    /**
     * Get the current weather type as a string key.
     */
    public String getWeatherKey() {
        return currentWeather.getKey();
    }
    
    /**
     * Get a description of the current sky for display.
     */
    public String getSkyDescription() {
        return currentWeather.getSkyDescription();
    }
    
    /**
     * Check if the current weather has lightning (for Call Lightning spell).
     */
    public boolean hasLightning() {
        return currentWeather.hasLightning();
    }
    
    /**
     * Check if current weather is stormy (stormy or hurricane).
     */
    public boolean isStormy() {
        return currentWeather == Weather.STORMY || currentWeather == Weather.HURRICANE;
    }
    
    /**
     * Load persisted weather from database.
     */
    private void loadWeather() {
        String saved = dao.getSetting("weather.current");
        if (saved != null) {
            currentWeather = Weather.fromKey(saved);
        } else {
            currentWeather = Weather.CLEAR;
            persistWeather();
        }
    }
    
    /**
     * Persist current weather to database.
     */
    private void persistWeather() {
        dao.setSetting("weather.current", currentWeather.getKey());
    }
    
    /**
     * Weather tick - determine if weather changes and notify players.
     */
    private void tick() {
        if (!running) return;
        
        Weather oldWeather = currentWeather;
        Weather newWeather = calculateNextWeather(oldWeather);
        
        if (newWeather != oldWeather) {
            currentWeather = newWeather;
            persistWeather();
            
            String transition = getTransitionMessage(oldWeather, newWeather);
            broadcastWeatherChange(transition);
            
            logger.info("[WeatherService] Weather changed: {} -> {} ({})", 
                    oldWeather.getDisplayName(), newWeather.getDisplayName(), transition);
        } else {
            logger.debug("[WeatherService] Weather remains: {}", currentWeather.getDisplayName());
        }
    }
    
    /**
     * Calculate the next weather based on transition probabilities.
     */
    private Weather calculateNextWeather(Weather current) {
        double roll = random.nextDouble();
        
        switch (current) {
            case CLEAR:
                // 50% remain clear, 25% partly cloudy, 25% overcast
                if (roll < 0.50) return Weather.CLEAR;
                if (roll < 0.75) return Weather.PARTLY_CLOUDY;
                return Weather.OVERCAST;
                
            case PARTLY_CLOUDY:
                // 50% remain partly cloudy, 25% clear, 25% overcast
                if (roll < 0.50) return Weather.PARTLY_CLOUDY;
                if (roll < 0.75) return Weather.CLEAR;
                return Weather.OVERCAST;
                
            case OVERCAST:
                // 25% remain overcast, 25% partly cloudy, 25% windy, 25% rainy
                if (roll < 0.25) return Weather.OVERCAST;
                if (roll < 0.50) return Weather.PARTLY_CLOUDY;
                if (roll < 0.75) return Weather.WINDY;
                return Weather.RAINY;
                
            case WINDY:
                // 25% overcast, 25% remain windy, 25% rainy, 25% stormy
                if (roll < 0.25) return Weather.OVERCAST;
                if (roll < 0.50) return Weather.WINDY;
                if (roll < 0.75) return Weather.RAINY;
                return Weather.STORMY;
                
            case RAINY:
                // 25% overcast, 25% remain rainy, 25% windy, 25% stormy
                if (roll < 0.25) return Weather.OVERCAST;
                if (roll < 0.50) return Weather.RAINY;
                if (roll < 0.75) return Weather.WINDY;
                return Weather.STORMY;
                
            case STORMY:
                // 25% remain stormy, 25% windy, 25% rainy, 25% special
                if (roll < 0.25) return Weather.STORMY;
                if (roll < 0.50) return Weather.WINDY;
                if (roll < 0.75) return Weather.RAINY;
                return rollSpecialWeather();
                
            // Special weather always transitions back to normal
            case SNOWY:
            case HURRICANE:
            case EARTHQUAKE:
            case VOLCANIC_ASH:
                // 50% partly cloudy, 50% clear
                if (roll < 0.50) return Weather.PARTLY_CLOUDY;
                return Weather.CLEAR;
                
            default:
                return Weather.CLEAR;
        }
    }
    
    /**
     * Roll for a random special weather type.
     */
    private Weather rollSpecialWeather() {
        double roll = random.nextDouble();
        if (roll < 0.25) return Weather.SNOWY;
        if (roll < 0.50) return Weather.HURRICANE;
        if (roll < 0.75) return Weather.EARTHQUAKE;
        return Weather.VOLCANIC_ASH;
    }
    
    /**
     * Get an artistic transition message for a weather change.
     */
    private String getTransitionMessage(Weather from, Weather to) {
        // Special handling for transitions TO special weather
        if (to.isSpecial()) {
            return getSpecialWeatherArrival(to);
        }
        
        // Special handling for transitions FROM special weather
        if (from.isSpecial()) {
            return getSpecialWeatherDeparture(from, to);
        }
        
        // Standard transitions
        switch (from) {
            case CLEAR:
                switch (to) {
                    case PARTLY_CLOUDY:
                        return "Wisps of cloud begin to drift across the previously clear sky, casting fleeting shadows upon the land.";
                    case OVERCAST:
                        return "With surprising swiftness, clouds gather and spread until they blanket the entire sky in grey.";
                    default:
                        return getGenericTransition(to);
                }
                
            case PARTLY_CLOUDY:
                switch (to) {
                    case CLEAR:
                        return "The scattered clouds dissolve into nothingness, leaving behind a pristine, brilliant blue sky.";
                    case OVERCAST:
                        return "The clouds thicken and merge overhead, swallowing the last patches of blue sky.";
                    default:
                        return getGenericTransition(to);
                }
                
            case OVERCAST:
                switch (to) {
                    case PARTLY_CLOUDY:
                        return "Gaps appear in the cloud cover, allowing shafts of golden sunlight to pierce through.";
                    case WINDY:
                        return "A chill wind rises, tugging at clothing and hair as the grey clouds begin to race across the sky.";
                    case RAINY:
                        return "The first fat droplets of rain begin to fall, quickly building to a steady downpour.";
                    default:
                        return getGenericTransition(to);
                }
                
            case WINDY:
                switch (to) {
                    case OVERCAST:
                        return "The fierce winds begin to calm, leaving behind a still, oppressive blanket of grey clouds.";
                    case RAINY:
                        return "The howling wind brings rain with it, driving the droplets nearly sideways through the air.";
                    case STORMY:
                        return "Lightning splits the sky as the wind's fury transforms into a full tempest. Thunder crashes overhead!";
                    default:
                        return getGenericTransition(to);
                }
                
            case RAINY:
                switch (to) {
                    case OVERCAST:
                        return "The rain tapers off to a drizzle, then stops entirely, though the clouds remain thick overhead.";
                    case WINDY:
                        return "A powerful gust tears through the rain, scattering the droplets and heralding stronger winds to come.";
                    case STORMY:
                        return "A blinding flash illuminates the rain-soaked world as thunder booms! The storm has truly begun.";
                    default:
                        return getGenericTransition(to);
                }
                
            case STORMY:
                switch (to) {
                    case WINDY:
                        return "The lightning fades and thunder grows distant as the storm passes, leaving only fierce winds in its wake.";
                    case RAINY:
                        return "The thunder rolls away into the distance, but the rain continues to fall in heavy sheets.";
                    default:
                        return getGenericTransition(to);
                }
                
            default:
                return getGenericTransition(to);
        }
    }
    
    /**
     * Get arrival message for special weather events.
     */
    private String getSpecialWeatherArrival(Weather special) {
        switch (special) {
            case SNOWY:
                return "The temperature plummets suddenly as the rain transforms into swirling snowflakes. A white blanket begins to settle over the world.";
            case HURRICANE:
                return "The wind rises to a deafening shriek as the sky turns an ominous green-black. A hurricane descends upon the land with terrifying fury!";
            case EARTHQUAKE:
                return "A deep rumble rises from the earth itself. The ground heaves and shudders violently - earthquake! Brace yourselves!";
            case VOLCANIC_ASH:
                return "The sky darkens to an unnatural twilight as volcanic ash begins to rain down. The air grows thick with the acrid smell of sulfur.";
            default:
                return "The weather takes an unusual turn...";
        }
    }
    
    /**
     * Get departure message for special weather events returning to normal.
     */
    private String getSpecialWeatherDeparture(Weather from, Weather to) {
        switch (from) {
            case SNOWY:
                if (to == Weather.CLEAR) {
                    return "The snowfall ceases and the clouds part, revealing a brilliant sun that begins to melt the white blanket.";
                } else {
                    return "The snow tapers off, though clouds still linger above the white-dusted landscape.";
                }
            case HURRICANE:
                if (to == Weather.CLEAR) {
                    return "As suddenly as it arrived, the hurricane passes. An almost eerie calm settles as blue sky emerges.";
                } else {
                    return "The hurricane's fury diminishes, leaving behind scattered clouds and an unsettled atmosphere.";
                }
            case EARTHQUAKE:
                if (to == Weather.CLEAR) {
                    return "The tremors finally subside. Dust settles and an almost unnatural stillness pervades under clear skies.";
                } else {
                    return "The ground grows still at last, though the air remains hazy with dust beneath the clouded sky.";
                }
            case VOLCANIC_ASH:
                if (to == Weather.CLEAR) {
                    return "Fresh winds sweep away the volcanic ash, revealing blue skies once more. Only a fine grey dust remains.";
                } else {
                    return "The ashfall lessens as winds begin to clear the air, though the sky remains overcast.";
                }
            default:
                return getGenericTransition(to);
        }
    }
    
    /**
     * Generic transition message when no specific one is defined.
     */
    private String getGenericTransition(Weather to) {
        switch (to) {
            case CLEAR:
                return "The weather clears, revealing a beautiful blue sky.";
            case PARTLY_CLOUDY:
                return "Scattered clouds now drift across the sky.";
            case OVERCAST:
                return "Grey clouds spread across the sky, blocking out the sun.";
            case WINDY:
                return "A strong wind picks up, whipping across the land.";
            case RAINY:
                return "Rain begins to fall from the sky.";
            case STORMY:
                return "Thunder rumbles as a storm rolls in.";
            default:
                return "The weather changes.";
        }
    }
    
    /**
     * Broadcast a weather change message to all eligible players.
     * Players must be:
     * - Outdoors (not in a room with INDOORS flag)
     * - Not sleeping
     */
    private void broadcastWeatherChange(String message) {
        for (java.util.Map.Entry<Integer, ClientHandler> entry : ClientHandler.charIdToSession.entrySet()) {
            Integer charId = entry.getKey();
            ClientHandler session = entry.getValue();
            
            if (session == null || charId == null) continue;
            
            Integer roomId = session.currentRoomId;
            if (roomId == null) continue;
            
            // Check if player is sleeping
            Stance stance = RegenerationService.getInstance().getPlayerStance(charId);
            if (stance != null && stance.isAsleep()) {
                continue; // Skip sleeping players
            }
            
            // Check if room is indoors
            Set<RoomFlag> flags = dao.getRoomFlags(roomId);
            if (flags != null && flags.contains(RoomFlag.INDOORS)) {
                continue; // Skip players indoors
            }
            
            // Send the weather notification
            session.sendRaw("");
            session.sendRaw("\u001B[36m" + message + "\u001B[0m"); // Cyan color for weather
            session.sendRaw("");
        }
    }
    
    /**
     * Force a weather change (GM command).
     */
    public void setWeather(Weather newWeather) {
        Weather oldWeather = currentWeather;
        currentWeather = newWeather;
        persistWeather();
        
        if (oldWeather != newWeather) {
            String transition = getTransitionMessage(oldWeather, newWeather);
            broadcastWeatherChange(transition);
            logger.info("[WeatherService] Weather forced: {} -> {}", 
                    oldWeather.getDisplayName(), newWeather.getDisplayName());
        }
    }
    
    /**
     * Shutdown the weather service.
     */
    public void shutdown() {
        this.running = false;
    }
}
