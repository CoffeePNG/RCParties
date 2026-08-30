package gg.rc.parties.internal;

import gg.rc.parties.api.event.PartyEvent;
import gg.rc.parties.api.event.PartyInviteEvent;

/**
 * Where the manager publishes its events. In production this is the Bukkit event bus; in
 * tests it is a recorder, which is the whole reason the manager doesn't call
 * {@code Bukkit.getPluginManager()} itself.
 */
public interface PartyEventSink {

    void fire(PartyEvent event);

    /**
     * Fires a cancellable invite event.
     *
     * @return true if a listener cancelled it
     */
    boolean fireCancellable(PartyInviteEvent event);

    /** A sink that drops everything — useful for tests that don't care about events. */
    PartyEventSink NOOP = new PartyEventSink() {
        @Override
        public void fire(PartyEvent event) {
            // intentionally empty
        }

        @Override
        public boolean fireCancellable(PartyInviteEvent event) {
            return false;
        }
    };
}
