package com.example.tassmud.model;

import java.util.Collections;
import java.util.List;

/**
 * Represents a shop menu tied to a specific mobile template.
 * A shopkeeper mob can sell items from this menu to players.
 */
public class Shop {
    
    private final int id;
    private final int mobTemplateId;
    private final List<Integer> itemTemplateIds;
    
    public Shop(int id, int mobTemplateId, List<Integer> itemTemplateIds) {
        this.id = id;
        this.mobTemplateId = mobTemplateId;
        this.itemTemplateIds = itemTemplateIds == null 
            ? Collections.emptyList() 
            : Collections.unmodifiableList(itemTemplateIds);
    }
    
    public int getId() {
        return id;
    }
    
    public int getMobTemplateId() {
        return mobTemplateId;
    }
    
    public List<Integer> getItemTemplateIds() {
        return itemTemplateIds;
    }
    
    @Override
    public String toString() {
        return "Shop{id=" + id + ", mobTemplateId=" + mobTemplateId + 
               ", items=" + itemTemplateIds.size() + "}";
    }
}
