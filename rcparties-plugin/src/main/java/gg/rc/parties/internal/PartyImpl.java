package gg.rc.parties.internal;

import gg.rc.parties.api.Party;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The mutable, in-memory party. Only {@link PartyManager} mutates one; everything handed
 * to a consumer goes through {@link #snapshot()} so nobody can reach in and change state
 * behind the manager's back.
 *
 * <p>Membership is a {@link LinkedHashSet} on purpose: insertion order <em>is</em> join
 * order, which is what "longest-tenured remaining member" means when the leader leaves.
 */
final class PartyImpl implements Party {

    private final UUID id;
    private final long createdAt;
    private final int maxSize;
    private final Set<UUID> members = new LinkedHashSet<>();
    private final Set<String> activityLocks = new LinkedHashSet<>();
    private final Map<String, String> metadata = new LinkedHashMap<>();

    private UUID leader;

    PartyImpl(UUID id, UUID leader, long createdAt, int maxSize) {
        this.id = id;
        this.leader = leader;
        this.createdAt = createdAt;
        this.maxSize = maxSize;
        this.members.add(leader);
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public UUID leader() {
        return leader;
    }

    @Override
    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    @Override
    public long createdAt() {
        return createdAt;
    }

    @Override
    public int maxSize() {
        return maxSize;
    }

    @Override
    public Set<String> activityLocks() {
        return Collections.unmodifiableSet(activityLocks);
    }

    @Override
    public Map<String, String> metadata() {
        return Collections.unmodifiableMap(metadata);
    }

    // ---- mutation, manager-only ----------------------------------------

    void addMember(UUID player) {
        members.add(player);
    }

    void removeMember(UUID player) {
        members.remove(player);
    }

    void setLeader(UUID player) {
        this.leader = player;
    }

    boolean addLock(String pluginKey) {
        return activityLocks.add(pluginKey);
    }

    boolean removeLock(String pluginKey) {
        return activityLocks.remove(pluginKey);
    }

    void clearLocks() {
        activityLocks.clear();
    }

    Map<String, String> mutableMetadata() {
        return metadata;
    }

    /**
     * The member who has been in the party longest, excluding {@code exclude}.
     * Returns null when nobody else remains.
     */
    UUID longestTenuredExcluding(UUID exclude) {
        for (UUID member : members) {
            if (!member.equals(exclude)) {
                return member;
            }
        }
        return null;
    }

    /**
     * An immutable copy safe to hand to consumers and to attach to an event, which may be
     * read after the live party has moved on (or been disbanded).
     */
    Party snapshot() {
        return new PartySnapshot(
                id,
                leader,
                Set.copyOf(members),
                createdAt,
                maxSize,
                Set.copyOf(activityLocks),
                Map.copyOf(metadata));
    }

    private record PartySnapshot(
            UUID id,
            UUID leader,
            Set<UUID> members,
            long createdAt,
            int maxSize,
            Set<String> activityLocks,
            Map<String, String> metadata)
            implements Party {}
}
