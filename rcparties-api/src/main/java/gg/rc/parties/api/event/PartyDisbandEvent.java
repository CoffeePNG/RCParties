package gg.rc.parties.api.event;

import gg.rc.parties.api.Party;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired after a party dissolved. The snapshot carries the members it had at the moment
 * it was dissolved, so consumers can clean up per-member state.
 */
public class PartyDisbandEvent extends PartyEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /** Why the party dissolved. */
    public enum Reason {
        /** The leader ran {@code /party disband}. */
        LEADER,
        /** The last member left, so nothing remained to lead. */
        EMPTY,
        /** An administrator force-disbanded it. */
        ADMIN,
        /** RCParties is shutting down. */
        SHUTDOWN
    }

    private final Reason reason;

    public PartyDisbandEvent(@NotNull Party party, @NotNull Reason reason) {
        super(party);
        this.reason = reason;
    }

    public @NotNull Reason getReason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
