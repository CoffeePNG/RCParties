package gg.rc.parties.api.event;

import gg.rc.parties.api.Party;
import org.bukkit.event.Event;
import org.jetbrains.annotations.NotNull;

/** Base class for every RCParties event. Carries the party the event concerns. */
public abstract class PartyEvent extends Event {

    private final Party party;

    protected PartyEvent(@NotNull Party party) {
        this.party = party;
    }

    /** A snapshot of the party as it stood when the event fired. */
    public @NotNull Party getParty() {
        return party;
    }
}
