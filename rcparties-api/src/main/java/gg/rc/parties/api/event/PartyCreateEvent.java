package gg.rc.parties.api.event;

import gg.rc.parties.api.Party;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired after a party of one has been created. Not cancellable. */
public class PartyCreateEvent extends PartyEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public PartyCreateEvent(@NotNull Party party) {
        super(party);
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
