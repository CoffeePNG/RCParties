package gg.rc.parties.internal;

import gg.rc.parties.api.Invite;
import gg.rc.parties.api.Party;
import gg.rc.parties.api.PartyService;
import gg.rc.parties.api.event.PartyCreateEvent;
import gg.rc.parties.api.event.PartyDisbandEvent;
import gg.rc.parties.api.event.PartyInviteEvent;
import gg.rc.parties.api.event.PartyJoinEvent;
import gg.rc.parties.api.event.PartyLeaderChangeEvent;
import gg.rc.parties.api.event.PartyLeaveEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Owns all party state. In-memory only, main-thread only — no persistence and no locking,
 * because parties are meaningless across a restart (§1 of RC-SPEC-PARTIES-001) and every
 * caller is either a command or a listener on the server thread.
 *
 * <p>The single invariant everything else leans on: a player appears in at most one party,
 * enforced by keeping {@link #partyByPlayer} in lockstep with {@link #partiesById}.
 */
public final class PartyManager implements PartyService {

    private final Map<UUID, PartyImpl> partiesById = new HashMap<>();
    private final Map<UUID, UUID> partyByPlayer = new HashMap<>();

    /** invitee -> (partyId -> invite). A player may hold invites from several parties. */
    private final Map<UUID, Map<UUID, Invite>> invitesByInvitee = new HashMap<>();

    private final PartyEventSink events;
    private final LongSupplier clock;

    private volatile PartiesConfig config;

    public PartyManager(PartiesConfig config, PartyEventSink events) {
        this(config, events, System::currentTimeMillis);
    }

    /** Test seam: an injectable clock so invite TTLs don't need real waiting. */
    public PartyManager(PartiesConfig config, PartyEventSink events, LongSupplier clock) {
        this.config = config;
        this.events = events;
        this.clock = clock;
    }

    public void setConfig(PartiesConfig config) {
        this.config = config;
    }

    public PartiesConfig config() {
        return config;
    }

    // ---- PartyService ---------------------------------------------------

    @Override
    public Optional<Party> getParty(UUID player) {
        return Optional.ofNullable(livePartyOf(player)).map(PartyImpl::snapshot);
    }

    @Override
    public Optional<Party> getPartyById(UUID partyId) {
        return Optional.ofNullable(partiesById.get(partyId)).map(PartyImpl::snapshot);
    }

    @Override
    public boolean isInParty(UUID player) {
        return partyByPlayer.containsKey(player);
    }

    @Override
    public Set<UUID> getMembers(UUID partyId) {
        PartyImpl party = partiesById.get(partyId);
        return party == null ? Set.of() : Set.copyOf(party.members());
    }

    @Override
    public Party createParty(UUID leader) {
        if (isInParty(leader)) {
            throw new IllegalStateException("Player " + leader + " is already in a party");
        }
        return createPartyInternal(leader).snapshot();
    }

    @Override
    public void lockActivity(UUID partyId, String pluginKey) {
        requireKey(pluginKey);
        PartyImpl party = partiesById.get(partyId);
        if (party == null) {
            throw new IllegalArgumentException("No such party: " + partyId);
        }
        party.addLock(pluginKey);
    }

    @Override
    public void unlockActivity(UUID partyId, String pluginKey) {
        requireKey(pluginKey);
        PartyImpl party = partiesById.get(partyId);
        if (party != null) {
            party.removeLock(pluginKey);
        }
    }

    @Override
    public boolean isLocked(UUID partyId) {
        PartyImpl party = partiesById.get(partyId);
        return party != null && party.isLocked();
    }

    // ---- lifecycle (§3) -------------------------------------------------

    /** {@code /party create} — a party of one with the caller as leader. */
    public PartyOutcome create(UUID player) {
        if (isInParty(player)) {
            return PartyOutcome.ALREADY_IN_PARTY;
        }
        createPartyInternal(player);
        return PartyOutcome.SUCCESS;
    }

    /**
     * {@code /party invite} — sends a TTL invite, creating a party of one for the inviter
     * if they don't have one yet, which is what players expect from a bare invite.
     */
    public PartyOutcome invite(UUID inviter, UUID invitee) {
        if (inviter.equals(invitee)) {
            return PartyOutcome.SELF_TARGET;
        }
        if (isInParty(invitee)) {
            return PartyOutcome.TARGET_ALREADY_IN_PARTY;
        }

        PartyImpl party = livePartyOf(inviter);
        if (party == null) {
            party = createPartyInternal(inviter);
        } else if (!party.isLeader(inviter) && !config.allowMemberInvites()) {
            return PartyOutcome.INVITES_LEADER_ONLY;
        }

        if (party.isFull()) {
            return PartyOutcome.PARTY_FULL;
        }

        long now = clock.getAsLong();
        Map<UUID, Invite> pending = invitesByInvitee.computeIfAbsent(invitee, k -> new LinkedHashMap<>());
        Invite existing = pending.get(party.id());
        if (existing != null && !existing.isExpired(now)) {
            return PartyOutcome.INVITE_PENDING;
        }

        if (events.fireCancellable(new PartyInviteEvent(party.snapshot(), inviter, invitee))) {
            return PartyOutcome.CANCELLED;
        }

        pending.put(party.id(), new Invite(party.id(), inviter, invitee, now + config.inviteTtlMillis()));
        return PartyOutcome.SUCCESS;
    }

    /** {@code /party accept <leader>} — joins the party the named player belongs to. */
    public PartyOutcome accept(UUID invitee, UUID fromPlayer) {
        if (isInParty(invitee)) {
            return PartyOutcome.ALREADY_IN_PARTY;
        }
        Invite invite = takeInviteFrom(invitee, fromPlayer);
        if (invite == null) {
            return PartyOutcome.NO_INVITE;
        }
        PartyImpl party = partiesById.get(invite.partyId());
        if (party == null) {
            return PartyOutcome.NO_INVITE;
        }
        if (party.isFull()) {
            return PartyOutcome.PARTY_FULL;
        }

        party.addMember(invitee);
        partyByPlayer.put(invitee, party.id());
        // Any other outstanding invites are moot now that they're grouped.
        invitesByInvitee.remove(invitee);

        events.fire(new PartyJoinEvent(party.snapshot(), invitee));
        return PartyOutcome.SUCCESS;
    }

    /** {@code /party deny <leader>} — drops the invite without joining. */
    public PartyOutcome deny(UUID invitee, UUID fromPlayer) {
        return takeInviteFrom(invitee, fromPlayer) == null ? PartyOutcome.NO_INVITE : PartyOutcome.SUCCESS;
    }

    /** {@code /party leave} — refused while any activity lock is held. */
    public PartyOutcome leave(UUID player) {
        PartyImpl party = livePartyOf(player);
        if (party == null) {
            return PartyOutcome.NOT_IN_PARTY;
        }
        if (party.isLocked()) {
            return PartyOutcome.LOCKED;
        }
        removeMember(party, player, PartyLeaveEvent.Reason.LEFT);
        return PartyOutcome.SUCCESS;
    }

    /** {@code /party kick <player>} — leader only, refused while locked. */
    public PartyOutcome kick(UUID actor, UUID target) {
        PartyImpl party = livePartyOf(actor);
        if (party == null) {
            return PartyOutcome.NOT_IN_PARTY;
        }
        if (!party.isLeader(actor)) {
            return PartyOutcome.NOT_LEADER;
        }
        if (actor.equals(target)) {
            return PartyOutcome.SELF_TARGET;
        }
        if (!party.contains(target)) {
            return PartyOutcome.TARGET_NOT_IN_PARTY;
        }
        if (party.isLocked()) {
            return PartyOutcome.LOCKED;
        }
        removeMember(party, target, PartyLeaveEvent.Reason.KICKED);
        return PartyOutcome.SUCCESS;
    }

    /**
     * {@code /party promote <player>} — leader only. Not lock-guarded: the party's
     * membership doesn't change, so an in-progress activity is unaffected.
     */
    public PartyOutcome promote(UUID actor, UUID target) {
        PartyImpl party = livePartyOf(actor);
        if (party == null) {
            return PartyOutcome.NOT_IN_PARTY;
        }
        if (!party.isLeader(actor)) {
            return PartyOutcome.NOT_LEADER;
        }
        if (actor.equals(target)) {
            return PartyOutcome.SELF_TARGET;
        }
        if (!party.contains(target)) {
            return PartyOutcome.TARGET_NOT_IN_PARTY;
        }
        party.setLeader(target);
        events.fire(new PartyLeaderChangeEvent(party.snapshot(), actor, target));
        return PartyOutcome.SUCCESS;
    }

    /** {@code /party disband} — leader only, refused while any lock is held. */
    public PartyOutcome disband(UUID actor) {
        PartyImpl party = livePartyOf(actor);
        if (party == null) {
            return PartyOutcome.NOT_IN_PARTY;
        }
        if (!party.isLeader(actor)) {
            return PartyOutcome.NOT_LEADER;
        }
        if (party.isLocked()) {
            return PartyOutcome.LOCKED;
        }
        disbandInternal(party, PartyDisbandEvent.Reason.LEADER);
        return PartyOutcome.SUCCESS;
    }

    /** Disconnect is leave (§3, v1) — and it ignores activity locks, since the player is gone. */
    public void handleDisconnect(UUID player) {
        PartyImpl party = livePartyOf(player);
        if (party != null) {
            removeMember(party, player, PartyLeaveEvent.Reason.DISCONNECT);
        }
        invitesByInvitee.remove(player);
        // Invites this player sent are left to expire; their party may still honour them.
    }

    // ---- admin tooling (§4) ---------------------------------------------

    /** Force-disband, bypassing leadership and locks. */
    public PartyOutcome adminDisband(UUID partyId) {
        PartyImpl party = partiesById.get(partyId);
        if (party == null) {
            return PartyOutcome.NOT_IN_PARTY;
        }
        disbandInternal(party, PartyDisbandEvent.Reason.ADMIN);
        return PartyOutcome.SUCCESS;
    }

    /** Force-clears a stuck lock (§5). Returns true if a lock was actually released. */
    public boolean adminClearLocks(UUID partyId) {
        PartyImpl party = partiesById.get(partyId);
        if (party == null || !party.isLocked()) {
            return false;
        }
        party.clearLocks();
        return true;
    }

    /** Every live party, for {@code /party admin list}. */
    public List<Party> allParties() {
        List<Party> snapshots = new ArrayList<>(partiesById.size());
        for (PartyImpl party : partiesById.values()) {
            snapshots.add(party.snapshot());
        }
        return snapshots;
    }

    /**
     * Drops every lock held by one plugin key. Called when a consumer disables so a
     * crashed minigame can't strand a party forever (§5).
     *
     * @return how many parties were affected
     */
    public int releaseLocksOf(String pluginKey) {
        requireKey(pluginKey);
        int released = 0;
        for (PartyImpl party : partiesById.values()) {
            if (party.removeLock(pluginKey)) {
                released++;
            }
        }
        return released;
    }

    /** Sets a consumer metadata tag, or clears it when {@code value} is null. */
    public void setMetadata(UUID partyId, String key, String value) {
        PartyImpl party = partiesById.get(partyId);
        if (party == null) {
            return;
        }
        if (value == null) {
            party.mutableMetadata().remove(key);
        } else {
            party.mutableMetadata().put(key, value);
        }
    }

    /** Tears everything down on plugin disable, firing a disband per party. */
    public void shutdown() {
        for (PartyImpl party : List.copyOf(partiesById.values())) {
            disbandInternal(party, PartyDisbandEvent.Reason.SHUTDOWN);
        }
        partiesById.clear();
        partyByPlayer.clear();
        invitesByInvitee.clear();
    }

    // ---- invite queries used by the command layer -----------------------

    /** Outstanding, unexpired invites held by a player. Expired ones are pruned first. */
    public List<Invite> pendingInvites(UUID invitee) {
        Map<UUID, Invite> pending = invitesByInvitee.get(invitee);
        if (pending == null) {
            return List.of();
        }
        long now = clock.getAsLong();
        pending.values().removeIf(invite -> invite.isExpired(now));
        if (pending.isEmpty()) {
            invitesByInvitee.remove(invitee);
            return List.of();
        }
        return List.copyOf(pending.values());
    }

    // ---- internals ------------------------------------------------------

    private PartyImpl createPartyInternal(UUID leader) {
        PartyImpl party = new PartyImpl(UUID.randomUUID(), leader, clock.getAsLong(), config.maxPartySize());
        partiesById.put(party.id(), party);
        partyByPlayer.put(leader, party.id());
        events.fire(new PartyCreateEvent(party.snapshot()));
        return party;
    }

    private PartyImpl livePartyOf(UUID player) {
        UUID partyId = partyByPlayer.get(player);
        return partyId == null ? null : partiesById.get(partyId);
    }

    /**
     * Removes a member and applies the leader-transfer rules: leadership goes to the
     * longest-tenured remaining member, and an empty party disbands.
     */
    private void removeMember(PartyImpl party, UUID player, PartyLeaveEvent.Reason reason) {
        boolean wasLeader = party.isLeader(player);
        UUID successor = wasLeader ? party.longestTenuredExcluding(player) : null;

        party.removeMember(player);
        partyByPlayer.remove(player);

        if (party.members().isEmpty()) {
            // Fire the leave first so consumers see the departure before the disband.
            events.fire(new PartyLeaveEvent(party.snapshot(), player, reason));
            disbandInternal(party, PartyDisbandEvent.Reason.EMPTY);
            return;
        }

        if (wasLeader && successor != null) {
            party.setLeader(successor);
        }

        events.fire(new PartyLeaveEvent(party.snapshot(), player, reason));
        if (wasLeader && successor != null) {
            events.fire(new PartyLeaderChangeEvent(party.snapshot(), player, successor));
        }
    }

    private void disbandInternal(PartyImpl party, PartyDisbandEvent.Reason reason) {
        Party snapshot = party.snapshot();
        partiesById.remove(party.id());
        for (UUID member : snapshot.members()) {
            partyByPlayer.remove(member);
        }
        // Invites into a party that no longer exists can never be accepted.
        for (Map<UUID, Invite> pending : invitesByInvitee.values()) {
            pending.remove(party.id());
        }
        invitesByInvitee.values().removeIf(Map::isEmpty);

        events.fire(new PartyDisbandEvent(snapshot, reason));
    }

    /**
     * Finds and consumes the invite an invitee holds from a party {@code fromPlayer}
     * belongs to, which is how {@code /party accept <leader>} resolves a name to a party.
     */
    private Invite takeInviteFrom(UUID invitee, UUID fromPlayer) {
        Map<UUID, Invite> pending = invitesByInvitee.get(invitee);
        if (pending == null) {
            return null;
        }
        long now = clock.getAsLong();
        pending.values().removeIf(invite -> invite.isExpired(now));

        UUID targetPartyId = partyByPlayer.get(fromPlayer);
        Invite match = null;
        if (targetPartyId != null) {
            match = pending.remove(targetPartyId);
        }
        if (match == null) {
            // Fall back to matching the inviter directly, in case they have since left.
            for (Invite invite : List.copyOf(pending.values())) {
                if (invite.from().equals(fromPlayer)) {
                    match = pending.remove(invite.partyId());
                    break;
                }
            }
        }
        if (pending.isEmpty()) {
            invitesByInvitee.remove(invitee);
        }
        return match;
    }

    private static void requireKey(String pluginKey) {
        if (pluginKey == null || pluginKey.isBlank()) {
            throw new IllegalArgumentException("pluginKey must not be blank");
        }
    }

    /** Read-only view of the player -> party index, for admin inspection. */
    public Map<UUID, UUID> playerIndex() {
        return Collections.unmodifiableMap(partyByPlayer);
    }
}
