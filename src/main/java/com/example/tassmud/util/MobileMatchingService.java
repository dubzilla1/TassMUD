package com.example.tassmud.util;

import com.example.tassmud.model.Mobile;
import com.example.tassmud.model.MobileTemplate;
import com.example.tassmud.persistence.DaoProvider;
import com.example.tassmud.persistence.MobileDAO;

import java.util.List;

/**
 * Centralized mobile name/keyword resolution.
 * Replaces 8+ duplicated matching loops across combat, rogue, spell, and GM handlers.
 *
 * <p>Matching priority (standard mode):
 * <ol>
 *     <li>Name {@code startsWith} search</li>
 *     <li>ShortDesc {@code startsWith} search</li>
 *     <li>Template keyword match</li>
 * </ol>
 *
 * <p>Fuzzy mode uses {@code contains} instead of {@code startsWith} (for GM commands).
 */
public final class MobileMatchingService {

    private MobileMatchingService() {} // utility class

    /**
     * Find a living mobile in a room by name/shortDesc/keyword using startsWith matching.
     *
     * @param roomId the room to search
     * @param search the search term (will be lowercased)
     * @return the matched mobile, or null if none found
     */
    public static Mobile findInRoom(int roomId, String search) {
        if (search == null || search.isBlank()) return null;
        List<Mobile> mobs = MobileRegistry.getInstance().getByRoom(roomId);
        return findInList(mobs, search, false);
    }

    /**
     * Find a living mobile in a room using fuzzy (contains) matching.
     * Used by GM commands that need broader matching.
     */
    public static Mobile findInRoomFuzzy(int roomId, String search) {
        if (search == null || search.isBlank()) return null;
        List<Mobile> mobs = MobileRegistry.getInstance().getByRoom(roomId);
        return findInList(mobs, search, true);
    }

    /**
     * Find a living mobile from a list by name/shortDesc/keyword.
     *
     * @param mobs   the list of mobiles to search
     * @param search the search term (will be lowercased)
     * @param fuzzy  if true, use contains instead of startsWith
     * @return the matched mobile, or null if none found
     */
    public static Mobile findInList(List<Mobile> mobs, String search, boolean fuzzy) {
        if (mobs == null || mobs.isEmpty() || search == null || search.isBlank()) return null;
        String searchLower = search.trim().toLowerCase();

        // Pass 1: name and shortDesc
        for (Mobile m : mobs) {
            if (m.isDead()) continue;
            if (matches(m.getName(), searchLower, fuzzy)) return m;
            if (matches(m.getShortDesc(), searchLower, fuzzy)) return m;
        }

        // Pass 2: template keyword
        MobileDAO mobDao = DaoProvider.mobiles();
        for (Mobile m : mobs) {
            if (m.isDead()) continue;
            MobileTemplate mt = mobDao.getTemplateById(m.getTemplateId());
            if (mt != null && mt.matchesKeyword(searchLower)) return m;
        }

        return null;
    }

    private static boolean matches(String value, String searchLower, boolean fuzzy) {
        if (value == null) return false;
        String lower = value.toLowerCase();
        return fuzzy ? lower.contains(searchLower) : lower.startsWith(searchLower);
    }
}
