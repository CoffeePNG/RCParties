package gg.rc.parties.api.event;

import gg.rc.parties.api.Party;
import java.util.UUID;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired after leadership transferred, whether by promotion or by the leader departing. */
public class PartyLeaderChangeEvent extends PartyEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID previousLeader;
    private final UUID newLeader;

    public PartyLeaderChangeEvent(@NotNull Party party, @NotNull UUID previousLeader, @NotNull UUID newLeader) {
        super(party);
        this.previousLeader = previousLeader;
        this.newLeader = newLeader;
    }

    public @NotNull UUID getPreviousLeader() {
        return previousLeader;
    }

    public @NotNull UUID getNewLeader() {
        return newLeader;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
