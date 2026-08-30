package gg.rc.parties.api;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The public surface of RCParties, registered with the Bukkit {@code ServicesManager}
 * so consumers can resolve it without a hard plugin dependency:
 *
 * <pre>{@code
 * RegisteredServiceProvider<PartyService> rsp =
 *         Bukkit.getServicesManager().getRegistration(PartyService.class);
 * if (rsp != null) {
 *     PartyService parties = rsp.getProvider();
 * }
 * }</pre>
 *
 * <p>RCParties knows nothing about what consumers do with a party. It only answers
 * "who is grouped with whom" and "is this group busy".
 *
 * <p>All methods are main-thread only unless stated otherwise.
 */
public interface PartyService {

    /** The party the given player belongs to, if any. */
    Optional<Party> getParty(UUID player);

    /** The party with the given id, if it still exists. */
    Optional<Party> getPartyById(UUID partyId);

    /** Whether the player is currently in a party (a solo party of one still counts). */
    boolean isInParty(UUID player);

    /**
     * Members of the given party, or an empty set if the party does not exist.
     * Never null, always unmodifiable.
     */
    Set<UUID> getMembers(UUID partyId);

    /**
     * Creates a party of one with the given player as leader.
     *
     * @throws IllegalStateException if the player is already in a party
     */
    Party createParty(UUID leader);

    // ---- activity locks -------------------------------------------------

    /**
     * Marks the party as busy on behalf of {@code pluginKey}. While any lock is held,
     * leave/kick/disband are refused. Re-locking with the same key is a no-op.
     *
     * @throws IllegalArgumentException if the party does not exist or the key is blank
     */
    void lockActivity(UUID partyId, String pluginKey);

    /** Releases this plugin's lock. Unlocking a lock that isn't held is a no-op. */
    void unlockActivity(UUID partyId, String pluginKey);

    /** Whether any consumer currently holds a lock on the party. */
    boolean isLocked(UUID partyId);
}
