package gg.rc.parties.api.event;

import gg.rc.parties.api.Party;
import java.util.UUID;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired after a member joined. Not cancellable — cancel the invite instead. */
public class PartyJoinEvent extends PartyEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;

    public PartyJoinEvent(@NotNull Party party, @NotNull UUID player) {
        super(party);
        this.player = player;
    }

    public @NotNull UUID getPlayer() {
        return player;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
