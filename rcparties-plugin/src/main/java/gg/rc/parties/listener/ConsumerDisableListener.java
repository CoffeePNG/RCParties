package gg.rc.parties.listener;

import gg.rc.parties.internal.PartyManager;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;

/**
 * Defensive lock cleanup (§5). When a consumer plugin disables — reloaded, crashed, or
 * shut down mid-activity — it can no longer call {@code unlockActivity}, so its locks
 * would strand every party it was holding. We release them on its behalf.
 */
public final class ConsumerDisableListener implements Listener {

    private final PartyManager manager;
    private final Logger logger;
    private final String ownKey;

    public ConsumerDisableListener(PartyManager manager, Logger logger, String ownKey) {
        this.manager = manager;
        this.logger = logger;
        this.ownKey = ownKey;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        String key = event.getPlugin().getName();
        if (key.equalsIgnoreCase(ownKey)) {
            return; // our own disable is handled by onDisable(), which tears everything down
        }
        int released = manager.releaseLocksOf(key);
        if (released > 0) {
            logger.log(Level.INFO, "Released {0} stale activity lock(s) held by {1}.",
                    new Object[] {released, key});
        }
    }
}
