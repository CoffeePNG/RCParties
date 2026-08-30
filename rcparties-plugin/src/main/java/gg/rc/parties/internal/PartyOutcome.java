package gg.rc.parties.internal;

/**
 * Why a party operation succeeded or was refused. The manager returns these rather than
 * throwing or sending chat itself, which keeps it free of Bukkit and lets the command
 * layer own all player-facing wording.
 */
public enum PartyOutcome {

    SUCCESS(null, null),

    /** The actor is already in a party and a player may only be in one. */
    ALREADY_IN_PARTY("already-in-party", "<red>You are already in a party. Leave it first.</red>"),
    /** The actor isn't in a party at all. */
    NOT_IN_PARTY("not-in-party", "<red>You are not in a party.</red>"),
    /** The target isn't a member of the actor's party. */
    TARGET_NOT_IN_PARTY("target-not-in-party", "<red>That player is not in your party.</red>"),
    /** The target is already in some party. */
    TARGET_ALREADY_IN_PARTY("target-already-in-party", "<red>That player is already in a party.</red>"),
    /** The operation requires the party leader. */
    NOT_LEADER("not-leader", "<red>Only the party leader can do that.</red>"),
    /** The party is at max-party-size. */
    PARTY_FULL("party-full", "<red>That party is full.</red>"),
    /** An activity lock is held; the player must exit the activity first. */
    LOCKED("locked-cant-leave", "<red>Leave your current activity before leaving the party.</red>"),
    /** No matching invite, or it expired. */
    NO_INVITE("no-invite", "<red>You have no pending invite from that player.</red>"),
    /** An invite to this player from this party is already outstanding. */
    INVITE_PENDING("invite-pending", "<red>That player already has a pending invite from your party.</red>"),
    /** Config forbids non-leaders from inviting. */
    INVITES_LEADER_ONLY("invites-leader-only", "<red>Only the party leader can invite players.</red>"),
    /** The player targeted themselves where that makes no sense. */
    SELF_TARGET("self-target", "<red>You cannot target yourself.</red>"),
    /** A listener cancelled the event. */
    CANCELLED("cancelled", "<red>That action was blocked.</red>");

    private final String messageKey;
    private final String defaultMessage;

    PartyOutcome(String messageKey, String defaultMessage) {
        this.messageKey = messageKey;
        this.defaultMessage = defaultMessage;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /** The {@code messages.<key>} entry describing this refusal, or null for {@link #SUCCESS}. */
    public String messageKey() {
        return messageKey;
    }

    /** Built-in wording used when the key is absent from config.yml. Null for {@link #SUCCESS}. */
    public String defaultMessage() {
        return defaultMessage;
    }
}
