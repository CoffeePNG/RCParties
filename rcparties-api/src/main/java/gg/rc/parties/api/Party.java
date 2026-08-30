package gg.rc.parties.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A read-only view of a party. Implementations returned by {@link PartyService} are
 * immutable snapshots: mutating a party is done through the service and the commands,
 * never by touching the object handed to a consumer.
 */
public interface Party {

    /** Stable for the party's lifetime. */
    UUID id();

    /** Exactly one leader, always also present in {@link #members()}. */
    UUID leader();

    /** Every member, including the leader. Unmodifiable. */
    Set<UUID> members();

    /** Epoch millis at which the party was created. */
    long createdAt();

    /** Maximum member count, taken from config when the party was created. */
    int maxSize();

    /** Plugin keys currently holding an activity lock. Unmodifiable. */
    Set<String> activityLocks();

    /** Free-form consumer tags. RCParties never reads these. Unmodifiable. */
    Map<String, String> metadata();

    default boolean isLeader(UUID player) {
        return leader().equals(player);
    }

    default boolean contains(UUID player) {
        return members().contains(player);
    }

    default int size() {
        return members().size();
    }

    default boolean isFull() {
        return size() >= maxSize();
    }

    default boolean isLocked() {
        return !activityLocks().isEmpty();
    }
}
