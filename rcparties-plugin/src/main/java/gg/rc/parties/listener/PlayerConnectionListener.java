package gg.rc.parties.listener;

import gg.rc.parties.internal.PartyManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** v1 rule: disconnect means leave the party (§3). Grace-period rejoin is a later refinement. */
public final class PlayerConnectionListener implements Listener {

    private final PartyManager manager;

    public PlayerConnectionListener(PartyManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        manager.handleDisconnect(event.getPlayer().getUniqueId());
    }
}
