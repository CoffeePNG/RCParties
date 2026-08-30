package gg.rc.parties.internal;

/**
 * Why a party operation succeeded or was refused. The manager returns these rather than
 * throwing or sending chat itself, which keeps it free of Bukkit and lets the command
 * layer own all player-facing wording.
 */
public enum PartyOutcome {

    SUCCESS(true),

    /** The actor is already in a party and a player may only be in one. */
    ALREADY_IN_PARTY(false),
    /** The actor isn't in a party at all. */
    NOT_IN_PARTY(false),
    /** The target isn't a member of the actor's party. */
    TARGET_NOT_IN_PARTY(false),
    /** The target is already in some party. */
    TARGET_ALREADY_IN_PARTY(false),
    /** The operation requires the party leader. */
    NOT_LEADER(false),
    /** The party is at max-party-size. */
    PARTY_FULL(false),
    /** An activity lock is held; the player must exit the activity first. */
    LOCKED(false),
    /** No matching invite, or it expired. */
    NO_INVITE(false),
    /** An invite to this player from this party is already outstanding. */
    INVITE_PENDING(false),
    /** Config forbids non-leaders from inviting. */
    INVITES_LEADER_ONLY(false),
    /** The player targeted themselves where that makes no sense. */
    SELF_TARGET(false),
    /** A listener cancelled the event. */
    CANCELLED(false);

    private final boolean success;

    PartyOutcome(boolean success) {
        this.success = success;
    }

    public boolean isSuccess() {
        return success;
    }
}
