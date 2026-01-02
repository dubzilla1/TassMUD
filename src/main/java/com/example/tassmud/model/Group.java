package com.example.tassmud.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a player group/party.
 * Groups allow players to cooperate, share experience, and communicate privately.
 */
public class Group {
    
    /** Unique identifier for this group */
    private final long groupId;
    
    /** Character ID of the group leader */
    private volatile int leaderId;
    
    /** Character IDs of all members (including leader) */
    private final Set<Integer> memberIds = ConcurrentHashMap.newKeySet();
    
    /** Character IDs with pending invitations to join this group */
    private final Set<Integer> pendingInvites = ConcurrentHashMap.newKeySet();
    
    /** Character IDs of members who are following the leader */
    private final Set<Integer> followers = ConcurrentHashMap.newKeySet();
    
    /** Timestamp when group was created */
    private final long createdAt;
    
    /**
     * Create a new group with the specified leader.
     */
    public Group(long groupId, int leaderId) {
        this.groupId = groupId;
        this.leaderId = leaderId;
        this.memberIds.add(leaderId);
        this.createdAt = System.currentTimeMillis();
    }
    
    // ========== Getters ==========
    
    public long getGroupId() { return groupId; }
    
    public int getLeaderId() { return leaderId; }
    
    public Set<Integer> getMemberIds() { 
        return Collections.unmodifiableSet(new HashSet<>(memberIds)); 
    }
    
    public Set<Integer> getPendingInvites() { 
        return Collections.unmodifiableSet(new HashSet<>(pendingInvites)); 
    }
    
    public Set<Integer> getFollowers() {
        return Collections.unmodifiableSet(new HashSet<>(followers));
    }
    
    public long getCreatedAt() { return createdAt; }
    
    public int getMemberCount() { return memberIds.size(); }
    
    public boolean isLeader(int characterId) { return leaderId == characterId; }
    
    public boolean isMember(int characterId) { return memberIds.contains(characterId); }
    
    public boolean hasPendingInvite(int characterId) { return pendingInvites.contains(characterId); }
    
    public boolean isFollowing(int characterId) { return followers.contains(characterId); }
    
    // ========== Membership Management ==========
    
    /**
     * Add a pending invitation for a character to join this group.
     * @return true if invitation was added, false if already invited or already a member
     */
    public boolean invite(int characterId) {
        if (memberIds.contains(characterId)) {
            return false; // Already a member
        }
        return pendingInvites.add(characterId);
    }
    
    /**
     * Cancel a pending invitation.
     * @return true if invitation was removed
     */
    public boolean cancelInvite(int characterId) {
        return pendingInvites.remove(characterId);
    }
    
    /**
     * Accept a pending invitation and add character as member.
     * @return true if character joined, false if no pending invite
     */
    public boolean acceptInvite(int characterId) {
        if (!pendingInvites.remove(characterId)) {
            return false; // No pending invite
        }
        memberIds.add(characterId);
        return true;
    }
    
    /**
     * Decline a pending invitation.
     * @return true if invitation was declined/removed
     */
    public boolean declineInvite(int characterId) {
        return pendingInvites.remove(characterId);
    }
    
    /**
     * Remove a member from the group.
     * Cannot remove the leader - use transferLeadership or disband instead.
     * @return true if member was removed
     */
    public boolean removeMember(int characterId) {
        if (characterId == leaderId) {
            return false; // Cannot remove leader
        }
        followers.remove(characterId); // Stop following if they were
        return memberIds.remove(characterId);
    }
    
    /**
     * Transfer leadership to another member.
     * @return true if leadership was transferred
     */
    public boolean transferLeadership(int newLeaderId) {
        if (!memberIds.contains(newLeaderId)) {
            return false; // Not a member
        }
        if (newLeaderId == leaderId) {
            return false; // Already leader
        }
        // New leader can't be following themselves
        followers.remove(newLeaderId);
        this.leaderId = newLeaderId;
        return true;
    }
    
    // ========== Follow Management ==========
    
    /**
     * Start following the leader.
     * @return true if now following, false if already following or is leader
     */
    public boolean startFollowing(int characterId) {
        if (characterId == leaderId) {
            return false; // Leader can't follow themselves
        }
        if (!memberIds.contains(characterId)) {
            return false; // Not a member
        }
        return followers.add(characterId);
    }
    
    /**
     * Stop following the leader.
     * @return true if stopped following
     */
    public boolean stopFollowing(int characterId) {
        return followers.remove(characterId);
    }
    
    // ========== Utility ==========
    
    /**
     * Check if the group should be disbanded (only leader remains or empty).
     */
    public boolean shouldDisband() {
        return memberIds.size() <= 1;
    }
    
    /**
     * Clear all pending invites.
     */
    public void clearInvites() {
        pendingInvites.clear();
    }
    
    /**
     * Clear all followers.
     */
    public void clearFollowers() {
        followers.clear();
    }
    
    @Override
    public String toString() {
        return "Group[id=" + groupId + ", leader=" + leaderId + 
               ", members=" + memberIds.size() + ", invites=" + pendingInvites.size() + "]";
    }
}
