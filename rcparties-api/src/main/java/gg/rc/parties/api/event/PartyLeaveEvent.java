package gg.rc.parties.api.event;

import gg.rc.parties.api.Party;
import java.util.UUID;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired after a member left, was kicked, or disconnected. The party snapshot is the
 * state <em>after</em> the removal; if the removal disbanded the party, a
 * {@link PartyDisbandEvent} follows.
 */
public class PartyLeaveEvent extends PartyEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /** Why the member is no longer in the party. */
    public enum Reason {
        /** The player ran {@code /party leave}. */
        LEFT,
        /** The leader kicked the player. */
        KICKED,
        /** The player disconnected (v1: disconnect means leave). */
        DISCONNECT,
        /** An administrator removed the player. */
        ADMIN
    }

    private final UUID player;
    private final Reason reason;

    public PartyLeaveEvent(@NotNull Party party, @NotNull UUID player, @NotNull Reason reason) {
        super(party);
        this.player = player;
        this.reason = reason;
    }

    public @NotNull UUID getPlayer() {
        return player;
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
