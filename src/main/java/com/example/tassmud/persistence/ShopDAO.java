package com.example.tassmud.persistence;

import com.example.tassmud.model.Shop;

import java.io.InputStream;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Data access object for shop menus.
 * Shops are loaded from YAML and cached in memory (no database persistence).
 */
public class ShopDAO {
    
    private static final Logger logger = Logger.getLogger(ShopDAO.class.getName());
    
    // Cached shops by mob template ID for fast lookup
    private static final Map<Integer, Shop> shopsByMobTemplateId = new HashMap<>();
    private static boolean loaded = false;
    
    /**
     * Load shops from the YAML resource file.
     * This is called during server startup.
     */
    @SuppressWarnings("unchecked")
    public static void loadFromYamlResource(String resourcePath) {
        try (InputStream in = ShopDAO.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                logger.info("No shops.yaml found at " + resourcePath);
                loaded = true;
                return;
            }
            
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            Map<String, Object> root = yaml.load(in);
            if (root == null) {
                loaded = true;
                return;
            }
            
            Object shopsObj = root.get("shops");
            if (!(shopsObj instanceof List)) {
                loaded = true;
                return;
            }
            
            List<Map<String, Object>> shopList = (List<Map<String, Object>>) shopsObj;
            int count = 0;
            
            for (Map<String, Object> shopData : shopList) {
                int id = getInt(shopData, "id", -1);
                if (id < 0) continue;
                
                int mobTemplateId = getInt(shopData, "mob_template_id", -1);
                if (mobTemplateId < 0) continue;
                
                List<Integer> itemIds = new ArrayList<>();
                Object itemsObj = shopData.get("items");
                if (itemsObj instanceof List<?> list) {
                    for (Object itemId : list) {
                        if (itemId instanceof Number number) {
                            itemIds.add(number.intValue());
                        }
                    }
                }
                
                Shop shop = new Shop(id, mobTemplateId, itemIds);
                shopsByMobTemplateId.put(mobTemplateId, shop);
                count++;
            }
            
            loaded = true;
            logger.info("Loaded " + count + " shop(s) from " + resourcePath);
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to load shops from " + resourcePath, e);
            loaded = true;
        }
    }
    
    /**
     * Get the shop for a specific mob template ID.
     * @return the Shop, or null if no shop exists for this mob
     */
    public Shop getShopByMobTemplateId(int mobTemplateId) {
        ensureLoaded();
        return shopsByMobTemplateId.get(mobTemplateId);
    }
    
    /**
     * Get all shops for a list of mob template IDs.
     * @return list of shops (empty if none found)
     */
    public List<Shop> getShopsForMobTemplateIds(Collection<Integer> mobTemplateIds) {
        ensureLoaded();
        List<Shop> result = new ArrayList<>();
        for (Integer mobId : mobTemplateIds) {
            Shop shop = shopsByMobTemplateId.get(mobId);
            if (shop != null) {
                result.add(shop);
            }
        }
        return result;
    }
    
    /**
     * Get all item template IDs available for sale from the given shops.
     * @return set of unique item template IDs
     */
    public Set<Integer> getAllItemIds(List<Shop> shops) {
        Set<Integer> itemIds = new LinkedHashSet<>();
        for (Shop shop : shops) {
            itemIds.addAll(shop.getItemTemplateIds());
        }
        return itemIds;
    }
    
    /**
     * Check if an item is sold by any of the given shops.
     */
    public boolean isItemSoldByShops(int itemTemplateId, List<Shop> shops) {
        for (Shop shop : shops) {
            if (shop.getItemTemplateIds().contains(itemTemplateId)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get all loaded shops.
     */
    public Collection<Shop> getAllShops() {
        ensureLoaded();
        return Collections.unmodifiableCollection(shopsByMobTemplateId.values());
    }
    
    /**
     * Ensure shops are loaded (lazy loading).
     */
    private void ensureLoaded() {
        if (!loaded) {
            loadFromYamlResource("/data/shops.yaml");
        }
    }
    
    /**
     * Reload shops from YAML (useful for hot-reloading).
     */
    public void reload() {
        shopsByMobTemplateId.clear();
        loaded = false;
        ensureLoaded();
    }
    
    // Helper methods for parsing YAML values
    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number number) {
            return number.intValue();
        }
        return defaultValue;
    }
}
