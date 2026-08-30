package gg.rc.parties.api.event;

import gg.rc.parties.api.Party;
import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired before an invite is sent. Cancelling it drops the invite silently. */
public class PartyInviteEvent extends PartyEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID inviter;
    private final UUID invitee;
    private boolean cancelled;

    public PartyInviteEvent(@NotNull Party party, @NotNull UUID inviter, @NotNull UUID invitee) {
        super(party);
        this.inviter = inviter;
        this.invitee = invitee;
    }

    public @NotNull UUID getInviter() {
        return inviter;
    }

    public @NotNull UUID getInvitee() {
        return invitee;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
