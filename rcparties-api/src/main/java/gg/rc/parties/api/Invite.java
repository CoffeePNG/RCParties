package gg.rc.parties.api;

import java.util.UUID;

/**
 * A pending, time-limited invitation to join a party.
 *
 * @param partyId   the party being joined
 * @param from      the inviter
 * @param to        the invitee
 * @param expiresAt epoch millis after which the invite is no longer valid
 */
public record Invite(UUID partyId, UUID from, UUID to, long expiresAt) {

    public boolean isExpired(long now) {
        return now >= expiresAt;
    }
}
