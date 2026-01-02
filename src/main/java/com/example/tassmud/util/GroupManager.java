package com.example.tassmud.util;

import com.example.tassmud.model.Group;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Singleton manager for all active player groups.
 * Handles group creation, lookup, and lifecycle management.
 */
public class GroupManager {
    
    private static final Logger logger = LoggerFactory.getLogger(GroupManager.class);
    
    private static final GroupManager INSTANCE = new GroupManager();
    
    /** All active groups by group ID */
    private final Map<Long, Group> groupsById = new ConcurrentHashMap<>();
    
    /** Quick lookup: character ID -> their current group */
    private final Map<Integer, Group> characterToGroup = new ConcurrentHashMap<>();
    
    /** Quick lookup: character ID -> group they have pending invite to */
    private final Map<Integer, Group> pendingInvitesByCharacter = new ConcurrentHashMap<>();
    
    /** Counter for generating unique group IDs */
    private final AtomicLong nextGroupId = new AtomicLong(1);
    
    private GroupManager() {
        // Singleton
    }
    
    public static GroupManager getInstance() {
        return INSTANCE;
    }
    
    // ========== Group Creation ==========
    
    /**
     * Create a new group with the specified character as leader.
     * @return the new group, or empty if character is already in a group
     */
    public Optional<Group> createGroup(int leaderId) {
        // Check if already in a group
        if (characterToGroup.containsKey(leaderId)) {
            logger.debug("Cannot create group: character {} already in a group", leaderId);
            return Optional.empty();
        }
        
        long groupId = nextGroupId.getAndIncrement();
        Group group = new Group(groupId, leaderId);
        
        groupsById.put(groupId, group);
        characterToGroup.put(leaderId, group);
        
        logger.info("Created group {} with leader {}", groupId, leaderId);
        return Optional.of(group);
    }
    
    // ========== Group Lookup ==========
    
    /**
     * Get a group by its ID.
     */
    public Optional<Group> getGroupById(long groupId) {
        return Optional.ofNullable(groupsById.get(groupId));
    }
    
    /**
     * Get the group a character is currently in.
     */
    public Optional<Group> getGroupForCharacter(int characterId) {
        return Optional.ofNullable(characterToGroup.get(characterId));
    }
    
    /**
     * Get a group that a character has a pending invite to.
     */
    public Optional<Group> getPendingInviteGroup(int characterId) {
        return Optional.ofNullable(pendingInvitesByCharacter.get(characterId));
    }
    
    /**
     * Check if character is in any group.
     */
    public boolean isInGroup(int characterId) {
        return characterToGroup.containsKey(characterId);
    }
    
    /**
     * Check if character has a pending invite.
     */
    public boolean hasPendingInvite(int characterId) {
        return pendingInvitesByCharacter.containsKey(characterId);
    }
    
    /**
     * Get all active groups.
     */
    public Collection<Group> getAllGroups() {
        return groupsById.values();
    }
    
    // ========== Invitation Management ==========
    
    /**
     * Invite a character to a group.
     * @return true if invitation was sent
     */
    public boolean inviteToGroup(Group group, int inviteeId) {
        // Check if invitee is already in a group
        if (characterToGroup.containsKey(inviteeId)) {
            logger.debug("Cannot invite: character {} already in a group", inviteeId);
            return false;
        }
        
        // Check if invitee already has a pending invite (to any group)
        if (pendingInvitesByCharacter.containsKey(inviteeId)) {
            logger.debug("Cannot invite: character {} already has pending invite", inviteeId);
            return false;
        }
        
        if (group.invite(inviteeId)) {
            pendingInvitesByCharacter.put(inviteeId, group);
            logger.debug("Invited character {} to group {}", inviteeId, group.getGroupId());
            return true;
        }
        return false;
    }
    
    /**
     * Accept a pending invitation.
     * @return true if character joined the group
     */
    public boolean acceptInvite(int characterId) {
        Group group = pendingInvitesByCharacter.remove(characterId);
        if (group == null) {
            return false;
        }
        
        if (group.acceptInvite(characterId)) {
            characterToGroup.put(characterId, group);
            logger.info("Character {} joined group {}", characterId, group.getGroupId());
            return true;
        }
        return false;
    }
    
    /**
     * Decline a pending invitation.
     * @return true if invitation was declined
     */
    public boolean declineInvite(int characterId) {
        Group group = pendingInvitesByCharacter.remove(characterId);
        if (group == null) {
            return false;
        }
        
        group.declineInvite(characterId);
        logger.debug("Character {} declined invite to group {}", characterId, group.getGroupId());
        return true;
    }
    
    /**
     * Cancel a pending invitation (by the group/leader).
     * @return true if invitation was cancelled
     */
    public boolean cancelInvite(Group group, int inviteeId) {
        if (group.cancelInvite(inviteeId)) {
            pendingInvitesByCharacter.remove(inviteeId);
            logger.debug("Cancelled invite for character {} to group {}", inviteeId, group.getGroupId());
            return true;
        }
        return false;
    }
    
    // ========== Membership Management ==========
    
    /**
     * Remove a member from their group (leave or kick).
     * @return true if member was removed
     */
    public boolean removeMember(int characterId) {
        Group group = characterToGroup.get(characterId);
        if (group == null) {
            return false;
        }
        
        // If leader is leaving, need to handle specially
        if (group.isLeader(characterId)) {
            // Disband the group if leader leaves
            return disbandGroup(group);
        }
        
        if (group.removeMember(characterId)) {
            characterToGroup.remove(characterId);
            logger.info("Character {} left group {}", characterId, group.getGroupId());
            
            // Check if group should be disbanded (only leader remains)
            if (group.shouldDisband()) {
                disbandGroup(group);
            }
            return true;
        }
        return false;
    }
    
    /**
     * Kick a member from a group (leader action).
     * @return true if member was kicked
     */
    public boolean kickMember(Group group, int characterId) {
        if (group.isLeader(characterId)) {
            return false; // Can't kick the leader
        }
        
        if (group.removeMember(characterId)) {
            characterToGroup.remove(characterId);
            logger.info("Character {} was kicked from group {}", characterId, group.getGroupId());
            return true;
        }
        return false;
    }
    
    /**
     * Transfer group leadership to another member.
     * @return true if leadership was transferred
     */
    public boolean transferLeadership(Group group, int newLeaderId) {
        if (group.transferLeadership(newLeaderId)) {
            logger.info("Group {} leadership transferred to {}", group.getGroupId(), newLeaderId);
            return true;
        }
        return false;
    }
    
    // ========== Group Disbanding ==========
    
    /**
     * Disband a group, removing all members.
     * @return true if group was disbanded
     */
    public boolean disbandGroup(Group group) {
        if (group == null) {
            return false;
        }
        
        long groupId = group.getGroupId();
        
        // Remove all members from character lookup
        for (int memberId : group.getMemberIds()) {
            characterToGroup.remove(memberId);
        }
        
        // Remove any pending invites
        for (int inviteeId : group.getPendingInvites()) {
            pendingInvitesByCharacter.remove(inviteeId);
        }
        group.clearInvites();
        group.clearFollowers();
        
        // Remove group
        groupsById.remove(groupId);
        
        logger.info("Disbanded group {}", groupId);
        return true;
    }
    
    // ========== Follow Management ==========
    
    /**
     * Start a character following their group leader.
     * @return true if now following
     */
    public boolean startFollowing(int characterId) {
        Group group = characterToGroup.get(characterId);
        if (group == null) {
            return false;
        }
        return group.startFollowing(characterId);
    }
    
    /**
     * Stop a character from following their group leader.
     * @return true if stopped following
     */
    public boolean stopFollowing(int characterId) {
        Group group = characterToGroup.get(characterId);
        if (group == null) {
            return false;
        }
        return group.stopFollowing(characterId);
    }
    
    /**
     * Check if a character is following their leader.
     */
    public boolean isFollowing(int characterId) {
        Group group = characterToGroup.get(characterId);
        return group != null && group.isFollowing(characterId);
    }
    
    // ========== Cleanup ==========
    
    /**
     * Handle player logout - remove from group or disband if leader.
     */
    public void handlePlayerLogout(int characterId) {
        // Clear any pending invites for this character
        Group inviteGroup = pendingInvitesByCharacter.remove(characterId);
        if (inviteGroup != null) {
            inviteGroup.declineInvite(characterId);
        }
        
        // Remove from any group they're in
        removeMember(characterId);
    }
    
    /**
     * Clear all groups (for testing or server shutdown).
     */
    public void clearAll() {
        groupsById.clear();
        characterToGroup.clear();
        pendingInvitesByCharacter.clear();
        nextGroupId.set(1);
        logger.info("Cleared all groups");
    }
}
