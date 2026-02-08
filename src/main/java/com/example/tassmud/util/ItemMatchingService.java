package com.example.tassmud.util;

import com.example.tassmud.model.ItemTemplate;
import com.example.tassmud.persistence.ItemDAO;

import java.util.List;
import java.util.function.Function;

/**
 * Centralized item name/keyword resolution.
 * Replaces 12+ duplicated item matching loops across command handlers.
 *
 * <p>Matching priority (all variants):
 * <ol>
 *     <li>Exact name match (equalsIgnoreCase)</li>
 *     <li>Word match (any word in the name equals or starts with the search)</li>
 *     <li>Keyword match (equalsIgnoreCase or startsWith)</li>
 *     <li>Name starts with search</li>
 *     <li>Name contains search</li>
 * </ol>
 */
public final class ItemMatchingService {

    private ItemMatchingService() {} // utility class

    // ── RoomItem matching (instances in room / inventory / container) ──

    /**
     * Find a matching item from a list of room items using template name matching.
     * This is the canonical algorithm — exact &gt; word &gt; keyword &gt; prefix &gt; contains.
     */
    public static ItemDAO.RoomItem findMatchingItem(List<ItemDAO.RoomItem> items, String searchTerm) {
        return findMatchingItem(items, searchTerm, ri -> ri.template.name);
    }

    /**
     * Find a matching item with a custom name extractor.
     * Use this for display-name-aware matching (e.g. {@code ri -> ClientHandler.getItemDisplayName(ri)}).
     */
    public static ItemDAO.RoomItem findMatchingItem(List<ItemDAO.RoomItem> items, String searchTerm,
                                                     Function<ItemDAO.RoomItem, String> nameExtractor) {
        if (items == null || items.isEmpty() || searchTerm == null) return null;
        String searchLower = searchTerm.toLowerCase();

        // Priority 1: Exact name match
        for (ItemDAO.RoomItem ri : items) {
            String name = nameExtractor.apply(ri);
            if (name != null && name.equalsIgnoreCase(searchTerm)) return ri;
        }

        // Priority 2: Word match
        for (ItemDAO.RoomItem ri : items) {
            String name = nameExtractor.apply(ri);
            if (name != null) {
                for (String w : name.toLowerCase().split("\\s+")) {
                    if (w.equals(searchLower) || w.startsWith(searchLower)) return ri;
                }
            }
        }

        // Priority 3: Keyword match
        for (ItemDAO.RoomItem ri : items) {
            if (ri.template.keywords != null) {
                for (String kw : ri.template.keywords) {
                    if (kw.equalsIgnoreCase(searchLower) || kw.toLowerCase().startsWith(searchLower)) return ri;
                }
            }
        }

        // Priority 4: Name starts with search
        for (ItemDAO.RoomItem ri : items) {
            String name = nameExtractor.apply(ri);
            if (name != null && name.toLowerCase().startsWith(searchLower)) return ri;
        }

        // Priority 5: Name contains search
        for (ItemDAO.RoomItem ri : items) {
            String name = nameExtractor.apply(ri);
            if (name != null && name.toLowerCase().contains(searchLower)) return ri;
        }

        return null;
    }

    // ── ItemTemplate matching (shop buy — no instance, just templates) ──

    /**
     * Find a matching template from a list of item templates.
     * Same 5-priority algorithm but operates on templates directly.
     */
    public static ItemTemplate findMatchingTemplate(List<ItemTemplate> templates, String searchTerm) {
        if (templates == null || templates.isEmpty() || searchTerm == null) return null;
        String searchLower = searchTerm.toLowerCase();

        // Priority 1: Exact name match
        for (ItemTemplate t : templates) {
            if (t.name != null && t.name.equalsIgnoreCase(searchTerm)) return t;
        }

        // Priority 2: Word match
        for (ItemTemplate t : templates) {
            if (t.name != null) {
                for (String w : t.name.toLowerCase().split("\\s+")) {
                    if (w.equals(searchLower) || w.startsWith(searchLower)) return t;
                }
            }
        }

        // Priority 3: Keyword match
        for (ItemTemplate t : templates) {
            if (t.keywords != null) {
                for (String kw : t.keywords) {
                    if (kw.equalsIgnoreCase(searchLower) || kw.toLowerCase().startsWith(searchLower)) return t;
                }
            }
        }

        // Priority 4: Name starts with search
        for (ItemTemplate t : templates) {
            if (t.name != null && t.name.toLowerCase().startsWith(searchLower)) return t;
        }

        // Priority 5: Name contains search
        for (ItemTemplate t : templates) {
            if (t.name != null && t.name.toLowerCase().contains(searchLower)) return t;
        }

        return null;
    }
}
