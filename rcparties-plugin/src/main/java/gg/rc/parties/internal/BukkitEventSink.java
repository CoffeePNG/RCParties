package gg.rc.parties.internal;

import gg.rc.parties.api.event.PartyEvent;
import gg.rc.parties.api.event.PartyInviteEvent;
import org.bukkit.plugin.PluginManager;

/** Publishes party events onto the real Bukkit event bus. */
public final class BukkitEventSink implements PartyEventSink {

    private final PluginManager pluginManager;

    public BukkitEventSink(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    @Override
    public void fire(PartyEvent event) {
        pluginManager.callEvent(event);
    }

    @Override
    public boolean fireCancellable(PartyInviteEvent event) {
        pluginManager.callEvent(event);
        return event.isCancelled();
    }
}
