package gg.rc.parties.internal;

/**
 * Why a party operation succeeded or was refused. The manager returns these rather than
 * throwing or sending chat itself, which keeps it free of Bukkit and lets the command
 * layer own all player-facing wording.
 *
 * <p>Each refusal carries its RCUI catalog key. RCUI has no fallback — a key missing from
 * messages.yml throws {@code MissingMessageException} at send time — so MessagesTest
 * asserts the bundled catalog defines every key named here.
 */
public enum PartyOutcome {

    SUCCESS(null),

    /** The actor is already in a party and a player may only be in one. */
    ALREADY_IN_PARTY("error.already-in-party"),
    /** The actor isn't in a party at all. */
    NOT_IN_PARTY("error.not-in-party"),
    /** The target isn't a member of the actor's party. */
    TARGET_NOT_IN_PARTY("error.target-not-in-party"),
    /** The target is already in some party. */
    TARGET_ALREADY_IN_PARTY("error.target-already-in-party"),
    /** The operation requires the party leader. */
    NOT_LEADER("error.not-leader"),
    /** The party is at max-party-size. */
    PARTY_FULL("error.party-full"),
    /** An activity lock is held; the player must exit the activity first. */
    LOCKED("error.locked-cant-leave"),
    /** No matching invite, or it expired. */
    NO_INVITE("error.no-invite"),
    /** An invite to this player from this party is already outstanding. */
    INVITE_PENDING("error.invite-pending"),
    /** Config forbids non-leaders from inviting. */
    INVITES_LEADER_ONLY("error.invites-leader-only"),
    /** The player targeted themselves where that makes no sense. */
    SELF_TARGET("error.self-target"),
    /** A listener cancelled the event. */
    CANCELLED("error.cancelled");

    private final String messageKey;

    PartyOutcome(String messageKey) {
        this.messageKey = messageKey;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /** The RCUI catalog key describing this refusal, or null for {@link #SUCCESS}. */
    public String messageKey() {
        return messageKey;
    }
}
