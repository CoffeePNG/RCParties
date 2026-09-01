package gg.rc.parties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.rc.parties.api.Party;
import gg.rc.parties.api.event.PartyCreateEvent;
import gg.rc.parties.api.event.PartyDisbandEvent;
import gg.rc.parties.api.event.PartyInviteEvent;
import gg.rc.parties.api.event.PartyJoinEvent;
import gg.rc.parties.api.event.PartyLeaderChangeEvent;
import gg.rc.parties.api.event.PartyLeaveEvent;
import gg.rc.parties.internal.PartiesConfig;
import gg.rc.parties.internal.PartyManager;
import gg.rc.parties.internal.PartyOutcome;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Rules from RC-SPEC-PARTIES-001 §3 and §5, exercised against the manager directly. */
class PartyManagerTest {

    private static final int TTL_SECONDS = 60;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();
    private final UUID dave = UUID.randomUUID();

    private RecordingEventSink events;
    private MutableClock clock;
    private PartyManager manager;

    @BeforeEach
    void setUp() {
        events = new RecordingEventSink();
        clock = new MutableClock();
        manager = newManager(8, true);
    }

    private PartyManager newManager(int maxSize, boolean allowMemberInvites) {
        return new PartyManager(
                new PartiesConfig(maxSize, TTL_SECONDS, allowMemberInvites), events, clock);
    }

    /** Puts the given players into one party led by the first. */
    private UUID groupUp(UUID leader, UUID... members) {
        assertTrue(manager.create(leader).isSuccess());
        for (UUID member : members) {
            assertEquals(PartyOutcome.SUCCESS, manager.invite(leader, member));
            assertEquals(PartyOutcome.SUCCESS, manager.accept(member, leader));
        }
        return manager.getParty(leader).orElseThrow().id();
    }

    @Nested
    @DisplayName("creation and the one-party-per-player invariant")
    class Creation {

        @Test
        void createMakesAPartyOfOneLedByTheCaller() {
            assertEquals(PartyOutcome.SUCCESS, manager.create(alice));

            Party party = manager.getParty(alice).orElseThrow();
            assertEquals(alice, party.leader());
            assertEquals(Set(alice), party.members());
            assertEquals(1, party.size());
            assertTrue(manager.isInParty(alice));
            assertEquals(1, events.count(PartyCreateEvent.class));
        }

        @Test
        void aPlayerCannotBeInTwoParties() {
            manager.create(alice);
            assertEquals(PartyOutcome.ALREADY_IN_PARTY, manager.create(alice));
            assertEquals(1, manager.allParties().size());
        }

        @Test
        void serviceCreatePartyThrowsWhenAlreadyGrouped() {
            manager.createParty(alice);
            assertThrows(IllegalStateException.class, () -> manager.createParty(alice));
        }

        @Test
        void partyIdsAreStableForTheLifetimeOfTheParty() {
            UUID partyId = groupUp(alice, bob);
            assertEquals(partyId, manager.getParty(alice).orElseThrow().id());

            manager.leave(bob);
            assertEquals(partyId, manager.getParty(alice).orElseThrow().id());
        }

        @Test
        void unknownLookupsAreEmptyRatherThanNull() {
            assertTrue(manager.getParty(alice).isEmpty());
            assertTrue(manager.getPartyById(UUID.randomUUID()).isEmpty());
            assertEquals(Set(), manager.getMembers(UUID.randomUUID()));
            assertFalse(manager.isLocked(UUID.randomUUID()));
        }
    }

    @Nested
    @DisplayName("invites")
    class Invites {

        @Test
        void invitingWithoutAPartyCreatesOneForTheInviter() {
            assertEquals(PartyOutcome.SUCCESS, manager.invite(alice, bob));

            assertTrue(manager.isInParty(alice));
            assertFalse(manager.isInParty(bob), "an invite alone does not group anyone");
            assertEquals(1, manager.pendingInvites(bob).size());
        }

        @Test
        void acceptJoinsTheInvitersParty() {
            manager.invite(alice, bob);
            assertEquals(PartyOutcome.SUCCESS, manager.accept(bob, alice));

            Party party = manager.getParty(bob).orElseThrow();
            assertEquals(alice, party.leader());
            assertEquals(Set(alice, bob), party.members());
            assertEquals(bob, events.last(PartyJoinEvent.class).getPlayer());
        }

        @Test
        void denyDropsTheInviteWithoutJoining() {
            manager.invite(alice, bob);
            assertEquals(PartyOutcome.SUCCESS, manager.deny(bob, alice));

            assertFalse(manager.isInParty(bob));
            assertEquals(List.of(), manager.pendingInvites(bob));
            assertEquals(PartyOutcome.NO_INVITE, manager.accept(bob, alice));
        }

        @Test
        void invitesExpireAfterTheConfiguredTtl() {
            manager.invite(alice, bob);
            clock.advanceSeconds(TTL_SECONDS + 1);

            assertEquals(List.of(), manager.pendingInvites(bob));
            assertEquals(PartyOutcome.NO_INVITE, manager.accept(bob, alice));
            assertFalse(manager.isInParty(bob));
        }

        @Test
        void anInviteIsStillValidOnTheTickBeforeItExpires() {
            manager.invite(alice, bob);
            clock.advanceSeconds(TTL_SECONDS - 1);

            assertEquals(PartyOutcome.SUCCESS, manager.accept(bob, alice));
        }

        @Test
        void aDuplicateInviteIsRefusedButCanBeResentOnceItExpires() {
            manager.invite(alice, bob);
            assertEquals(PartyOutcome.INVITE_PENDING, manager.invite(alice, bob));

            clock.advanceSeconds(TTL_SECONDS + 1);
            assertEquals(PartyOutcome.SUCCESS, manager.invite(alice, bob));
        }

        @Test
        void aPlayerMayHoldInvitesFromSeveralPartiesAndPicksOne() {
            manager.invite(alice, carol);
            manager.invite(bob, carol);
            assertEquals(2, manager.pendingInvites(carol).size());

            assertEquals(PartyOutcome.SUCCESS, manager.accept(carol, bob));
            assertEquals(bob, manager.getParty(carol).orElseThrow().leader());
            assertEquals(List.of(), manager.pendingInvites(carol),
                    "joining clears every other outstanding invite");
        }

        @Test
        void youCannotInviteYourself() {
            assertEquals(PartyOutcome.SELF_TARGET, manager.invite(alice, alice));
        }

        @Test
        void youCannotInviteSomeoneAlreadyGrouped() {
            manager.create(bob);
            assertEquals(PartyOutcome.TARGET_ALREADY_IN_PARTY, manager.invite(alice, bob));
        }

        @Test
        void acceptingWhileAlreadyGroupedIsRefused() {
            manager.invite(alice, bob);
            manager.create(bob);
            assertEquals(PartyOutcome.ALREADY_IN_PARTY, manager.accept(bob, alice));
        }

        @Test
        void memberInvitesAreConfigGated() {
            manager = newManager(8, false);
            groupUp(alice, bob);

            assertEquals(PartyOutcome.INVITES_LEADER_ONLY, manager.invite(bob, carol));
            assertEquals(PartyOutcome.SUCCESS, manager.invite(alice, carol));
        }

        @Test
        void memberInvitesAreAllowedByDefault() {
            groupUp(alice, bob);
            assertEquals(PartyOutcome.SUCCESS, manager.invite(bob, carol));
            assertEquals(PartyOutcome.SUCCESS, manager.accept(carol, bob));
            assertEquals(alice, manager.getParty(carol).orElseThrow().leader(),
                    "an invite from a member still joins the inviter's party, not a new one");
        }

        @Test
        void aCancelledInviteEventStopsTheInvite() {
            events.cancelInvitesWhen(event -> true);
            assertEquals(PartyOutcome.CANCELLED, manager.invite(alice, bob));

            assertEquals(List.of(), manager.pendingInvites(bob));
            assertEquals(1, events.count(PartyInviteEvent.class));
        }

        @Test
        void invitesAreRefusedWhenThePartyIsFull() {
            manager = newManager(2, true);
            groupUp(alice, bob);

            assertEquals(PartyOutcome.PARTY_FULL, manager.invite(alice, carol));
        }

        @Test
        void anInviteSentBeforeThePartyFilledUpIsRefusedOnAccept() {
            manager = newManager(2, true);
            manager.create(alice);
            manager.invite(alice, bob);
            manager.invite(alice, carol);

            assertEquals(PartyOutcome.SUCCESS, manager.accept(bob, alice));
            assertEquals(PartyOutcome.PARTY_FULL, manager.accept(carol, alice));
            assertFalse(manager.isInParty(carol));
        }

        @Test
        void invitesIntoADisbandedPartyCannotBeAccepted() {
            manager.invite(alice, bob);
            manager.disband(alice);

            assertEquals(List.of(), manager.pendingInvites(bob));
            assertEquals(PartyOutcome.NO_INVITE, manager.accept(bob, alice));
        }
    }

    @Nested
    @DisplayName("leave, kick, promote, disband")
    class Departure {

        @Test
        void leavingRemovesOnlyThatMember() {
            groupUp(alice, bob, carol);
            assertEquals(PartyOutcome.SUCCESS, manager.leave(bob));

            assertFalse(manager.isInParty(bob));
            assertEquals(Set(alice, carol), manager.getParty(alice).orElseThrow().members());
            assertEquals(PartyLeaveEvent.Reason.LEFT, events.last(PartyLeaveEvent.class).getReason());
        }

        @Test
        void leadershipPassesToTheLongestTenuredRemainingMember() {
            groupUp(alice, bob, carol);
            assertEquals(PartyOutcome.SUCCESS, manager.leave(alice));

            Party party = manager.getParty(bob).orElseThrow();
            assertEquals(bob, party.leader(), "bob joined before carol");
            assertEquals(Set(bob, carol), party.members());

            PartyLeaderChangeEvent change = events.last(PartyLeaderChangeEvent.class);
            assertEquals(alice, change.getPreviousLeader());
            assertEquals(bob, change.getNewLeader());
        }

        @Test
        void tenureIsJoinOrderNotRejoinOrder() {
            groupUp(alice, bob, carol);
            manager.leave(bob);
            manager.invite(alice, bob);
            manager.accept(bob, alice);

            manager.leave(alice);
            assertEquals(carol, manager.getParty(carol).orElseThrow().leader(),
                    "carol has been in the party continuously since before bob rejoined");
        }

        @Test
        void thePartyDisbandsWhenTheLastMemberLeaves() {
            UUID partyId = groupUp(alice, bob);
            manager.leave(bob);
            manager.leave(alice);

            assertTrue(manager.getPartyById(partyId).isEmpty());
            assertFalse(manager.isInParty(alice));
            assertEquals(PartyDisbandEvent.Reason.EMPTY, events.last(PartyDisbandEvent.class).getReason());
        }

        @Test
        void aSoloPartyDisbandsOnLeave() {
            manager.create(alice);
            assertEquals(PartyOutcome.SUCCESS, manager.leave(alice));

            assertEquals(List.of(), manager.allParties());
            assertEquals(1, events.count(PartyDisbandEvent.class));
        }

        @Test
        void theDisbandSnapshotStillCarriesTheDepartedMembers() {
            groupUp(alice, bob);
            events.clear();
            manager.disband(alice);

            Party snapshot = events.last(PartyDisbandEvent.class).getParty();
            assertEquals(Set(alice, bob), snapshot.members(),
                    "consumers need the membership to clean up per-player state");
        }

        @Test
        void onlyTheLeaderMayKickPromoteOrDisband() {
            groupUp(alice, bob, carol);

            assertEquals(PartyOutcome.NOT_LEADER, manager.kick(bob, carol));
            assertEquals(PartyOutcome.NOT_LEADER, manager.promote(bob, carol));
            assertEquals(PartyOutcome.NOT_LEADER, manager.disband(bob));
        }

        @Test
        void kickRemovesTheTarget() {
            groupUp(alice, bob);
            assertEquals(PartyOutcome.SUCCESS, manager.kick(alice, bob));

            assertFalse(manager.isInParty(bob));
            assertEquals(PartyLeaveEvent.Reason.KICKED, events.last(PartyLeaveEvent.class).getReason());
        }

        @Test
        void kickingAStrangerOrYourselfIsRefused() {
            groupUp(alice, bob);
            assertEquals(PartyOutcome.TARGET_NOT_IN_PARTY, manager.kick(alice, dave));
            assertEquals(PartyOutcome.SELF_TARGET, manager.kick(alice, alice));
        }

        @Test
        void promoteTransfersLeadershipAndLeavesMembershipAlone() {
            groupUp(alice, bob);
            assertEquals(PartyOutcome.SUCCESS, manager.promote(alice, bob));

            Party party = manager.getParty(alice).orElseThrow();
            assertEquals(bob, party.leader());
            assertEquals(Set(alice, bob), party.members());
            assertEquals(PartyOutcome.NOT_LEADER, manager.promote(alice, bob),
                    "the old leader has no authority now");
        }

        @Test
        void disbandRemovesEveryoneAtOnce() {
            UUID partyId = groupUp(alice, bob, carol);
            assertEquals(PartyOutcome.SUCCESS, manager.disband(alice));

            assertTrue(manager.getPartyById(partyId).isEmpty());
            assertFalse(manager.isInParty(alice));
            assertFalse(manager.isInParty(bob));
            assertFalse(manager.isInParty(carol));
            assertEquals(PartyDisbandEvent.Reason.LEADER, events.last(PartyDisbandEvent.class).getReason());
        }

        @Test
        void operationsWithoutAPartyAreRefused() {
            assertEquals(PartyOutcome.NOT_IN_PARTY, manager.leave(alice));
            assertEquals(PartyOutcome.NOT_IN_PARTY, manager.kick(alice, bob));
            assertEquals(PartyOutcome.NOT_IN_PARTY, manager.promote(alice, bob));
            assertEquals(PartyOutcome.NOT_IN_PARTY, manager.disband(alice));
        }
    }

    @Nested
    @DisplayName("activity locks (§5)")
    class ActivityLocks {

        @Test
        void aLockBlocksLeaveKickAndDisband() {
            UUID partyId = groupUp(alice, bob);
            manager.lockActivity(partyId, "RCPuttPutt");

            assertTrue(manager.isLocked(partyId));
            assertEquals(PartyOutcome.LOCKED, manager.leave(bob));
            assertEquals(PartyOutcome.LOCKED, manager.kick(alice, bob));
            assertEquals(PartyOutcome.LOCKED, manager.disband(alice));
            assertEquals(Set(alice, bob), manager.getMembers(partyId));
        }

        @Test
        void promoteIsNotBlockedByALock() {
            UUID partyId = groupUp(alice, bob);
            manager.lockActivity(partyId, "RCPuttPutt");

            assertEquals(PartyOutcome.SUCCESS, manager.promote(alice, bob),
                    "membership is unchanged, so an in-progress activity is unaffected");
        }

        @Test
        void unlockingReleasesTheGuard() {
            UUID partyId = groupUp(alice, bob);
            manager.lockActivity(partyId, "RCPuttPutt");
            manager.unlockActivity(partyId, "RCPuttPutt");

            assertFalse(manager.isLocked(partyId));
            assertEquals(PartyOutcome.SUCCESS, manager.leave(bob));
        }

        @Test
        void locksAreKeyedPerPluginSoConsumersCannotClobberEachOther() {
            UUID partyId = groupUp(alice, bob);
            manager.lockActivity(partyId, "RCPuttPutt");
            manager.lockActivity(partyId, "RCRacing");

            manager.unlockActivity(partyId, "RCPuttPutt");
            assertTrue(manager.isLocked(partyId), "RCRacing still holds a lock");
            assertEquals(Set("RCRacing"), manager.getPartyById(partyId).orElseThrow().activityLocks());

            manager.unlockActivity(partyId, "RCRacing");
            assertFalse(manager.isLocked(partyId));
        }

        @Test
        void lockingTwiceWithTheSameKeyIsIdempotent() {
            UUID partyId = groupUp(alice, bob);
            manager.lockActivity(partyId, "RCPuttPutt");
            manager.lockActivity(partyId, "RCPuttPutt");
            manager.unlockActivity(partyId, "RCPuttPutt");

            assertFalse(manager.isLocked(partyId), "one unlock releases one key, however often it was set");
        }

        @Test
        void unlockingSomethingNeverLockedIsANoOp() {
            UUID partyId = groupUp(alice, bob);
            manager.unlockActivity(partyId, "RCFishing");
            manager.unlockActivity(UUID.randomUUID(), "RCFishing");
            assertFalse(manager.isLocked(partyId));
        }

        @Test
        void lockingAnUnknownPartyOrABlankKeyIsRejected() {
            UUID partyId = groupUp(alice, bob);
            assertThrows(IllegalArgumentException.class,
                    () -> manager.lockActivity(UUID.randomUUID(), "RCPuttPutt"));
            assertThrows(IllegalArgumentException.class, () -> manager.lockActivity(partyId, " "));
            assertThrows(IllegalArgumentException.class, () -> manager.lockActivity(partyId, null));
        }

        @Test
        void disconnectBypassesTheLockBecauseThePlayerIsAlreadyGone() {
            UUID partyId = groupUp(alice, bob);
            manager.lockActivity(partyId, "RCPuttPutt");

            manager.handleDisconnect(bob);
            assertFalse(manager.isInParty(bob));
            assertEquals(Set(alice), manager.getMembers(partyId));
            assertEquals(PartyLeaveEvent.Reason.DISCONNECT, events.last(PartyLeaveEvent.class).getReason());
        }

        @Test
        void releaseLocksOfClearsOneConsumersLocksEverywhere() {
            UUID first = groupUp(alice, bob);
            UUID second = groupUp(carol, dave);
            manager.lockActivity(first, "RCPuttPutt");
            manager.lockActivity(second, "RCPuttPutt");
            manager.lockActivity(second, "RCRacing");

            assertEquals(2, manager.releaseLocksOf("RCPuttPutt"));
            assertFalse(manager.isLocked(first));
            assertTrue(manager.isLocked(second), "RCRacing's lock survives RCPuttPutt disabling");
        }
    }

    @Nested
    @DisplayName("admin tooling and shutdown")
    class Admin {

        @Test
        void adminDisbandIgnoresLeadershipAndLocks() {
            UUID partyId = groupUp(alice, bob);
            manager.lockActivity(partyId, "RCPuttPutt");

            assertEquals(PartyOutcome.SUCCESS, manager.adminDisband(partyId));
            assertTrue(manager.getPartyById(partyId).isEmpty());
            assertEquals(PartyDisbandEvent.Reason.ADMIN, events.last(PartyDisbandEvent.class).getReason());
        }

        @Test
        void adminClearLocksFreesAStuckParty() {
            UUID partyId = groupUp(alice, bob);
            manager.lockActivity(partyId, "RCPuttPutt");

            assertTrue(manager.adminClearLocks(partyId));
            assertFalse(manager.adminClearLocks(partyId), "nothing left to clear");
            assertEquals(PartyOutcome.SUCCESS, manager.leave(bob));
        }

        @Test
        void metadataIsStoredVerbatimAndNeverInterpreted() {
            UUID partyId = groupUp(alice, bob);
            manager.setMetadata(partyId, "rcputtputt:course", "back-nine");

            assertEquals(Map.of("rcputtputt:course", "back-nine"),
                    manager.getPartyById(partyId).orElseThrow().metadata());

            manager.setMetadata(partyId, "rcputtputt:course", null);
            assertEquals(Map.of(), manager.getPartyById(partyId).orElseThrow().metadata());
        }

        @Test
        void shutdownDissolvesEveryParty() {
            groupUp(alice, bob);
            groupUp(carol, dave);
            events.clear();

            manager.shutdown();

            assertEquals(List.of(), manager.allParties());
            assertFalse(manager.isInParty(alice));
            assertEquals(2, events.count(PartyDisbandEvent.class));
            assertEquals(PartyDisbandEvent.Reason.SHUTDOWN, events.last(PartyDisbandEvent.class).getReason());
        }
    }

    @Nested
    @DisplayName("snapshots handed to consumers")
    class Snapshots {

        @Test
        void snapshotsDoNotChangeUnderTheConsumer() {
            groupUp(alice, bob);
            Party snapshot = manager.getParty(alice).orElseThrow();

            manager.leave(bob);

            assertEquals(Set(alice, bob), snapshot.members(), "the snapshot is a point-in-time copy");
            assertEquals(Set(alice), manager.getParty(alice).orElseThrow().members());
        }

        @Test
        void snapshotCollectionsAreImmutable() {
            UUID partyId = groupUp(alice, bob);
            manager.lockActivity(partyId, "RCPuttPutt");
            manager.setMetadata(partyId, "k", "v");
            Party snapshot = manager.getPartyById(partyId).orElseThrow();

            assertThrows(UnsupportedOperationException.class, () -> snapshot.members().add(carol));
            assertThrows(UnsupportedOperationException.class, () -> snapshot.activityLocks().add("x"));
            assertThrows(UnsupportedOperationException.class, () -> snapshot.metadata().put("k", "v2"));
        }

        @Test
        void memberSetIsUnmodifiableAndReflectsHelpers() {
            manager = newManager(2, true);
            groupUp(alice, bob);
            Party party = manager.getParty(alice).orElseThrow();

            assertTrue(party.isFull());
            assertTrue(party.isLeader(alice));
            assertFalse(party.isLeader(bob));
            assertTrue(party.contains(bob));
            assertFalse(party.contains(carol));
            assertFalse(party.isLocked());
        }

        @Test
        void theServiceViewAgreesWithTheCommandView() {
            UUID partyId = groupUp(alice, bob);
            assertSame(manager, manager);
            assertEquals(manager.getParty(alice).orElseThrow().id(), partyId);
            assertEquals(manager.getMembers(partyId), manager.getPartyById(partyId).orElseThrow().members());
            assertEquals(Map.of(alice, partyId, bob, partyId), manager.playerIndex());
        }
    }

    private static java.util.Set<Object> Set(Object... items) {
        return java.util.Set.of(items);
    }
}
