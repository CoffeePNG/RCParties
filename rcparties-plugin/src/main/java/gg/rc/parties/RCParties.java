package gg.rc.parties;

import gg.rc.parties.api.PartyService;
import gg.rc.parties.command.PartyCommand;
import gg.rc.parties.internal.BukkitEventSink;
import gg.rc.parties.internal.Messages;
import gg.rc.parties.internal.PartiesConfig;
import gg.rc.parties.internal.PartyManager;
import gg.rc.parties.listener.ConsumerDisableListener;
import gg.rc.parties.listener.PlayerConnectionListener;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.logging.Level;
import net.republicraft.rcui.api.MessageBundle;
import net.republicraft.rcui.api.RCUI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * RCParties — a game-agnostic grouping primitive (RC-SPEC-PARTIES-001).
 *
 * <p>This plugin knows nothing about golf, racing, fishing, or betting. It answers
 * "who is grouped with whom", lets consumers hold a party together for the duration of an
 * activity, and publishes events. Everything else is a consumer's job.
 */
public final class RCParties extends JavaPlugin {

    private PartyManager manager;
    private Messages messages;

    /** The RCUI namespace this plugin's operator catalog lives under. */
    private static final String MESSAGE_NAMESPACE = "rcparties";

    @Override
    public void onEnable() {
        saveDefaultConfig();

        MessageBundle bundle;
        try {
            bundle = RCUI.messages(this).register(this, MESSAGE_NAMESPACE, "messages.yml");
        } catch (RuntimeException ex) {
            // RCUI is a required dependency, so a failed registration means something is
            // genuinely wrong. Running mute is worse than not running.
            getLogger().log(Level.SEVERE, "Could not register the RCUI message catalog", ex);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.messages = new Messages(bundle);

        PartiesConfig config = PartiesConfig.from(getConfig());
        warnAboutStaleMessageConfig();
        this.manager = new PartyManager(config, new BukkitEventSink(getServer().getPluginManager()));

        // Registered Vault-style so consumers resolve us without a hard plugin dependency.
        Bukkit.getServicesManager().register(PartyService.class, manager, this, ServicePriority.Normal);

        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(manager), this);
        getServer().getPluginManager().registerEvents(
                new ConsumerDisableListener(manager, getLogger(), getName()), this);

        PartyCommand command = new PartyCommand(manager, messages, this::reloadSettings);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        command.build(),
                        "Party grouping commands.",
                        PartyCommand.aliases()));

        getLogger().info("RCParties enabled (max size " + config.maxPartySize()
                + ", invite TTL " + (config.inviteTtlMillis() / 1000L) + "s).");
    }

    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregister(PartyService.class, manager);
        if (manager != null) {
            // Parties are in-memory only, so a shutdown dissolves them all (§1).
            manager.shutdown();
        }
    }

    /**
     * Re-reads config.yml and RCUI's catalog, backing {@code /party admin reload}, so
     * message edits apply without a restart. Existing parties keep the max-size they were
     * created with, since shrinking a party that is already formed would mean evicting
     * somebody.
     */
    public void reloadSettings() {
        reloadConfig();
        manager.setConfig(PartiesConfig.from(getConfig()));
        warnAboutStaleMessageConfig();
        RCUI.messages(this).reload();
    }

    /**
     * Wording moved to RCUI, but {@code saveDefaultConfig()} only writes when the file is
     * absent, so an in-place upgrade keeps the old block. Warn rather than delete —
     * silently dropping an operator's wording is the worst outcome.
     */
    private void warnAboutStaleMessageConfig() {
        if (getConfig().isConfigurationSection("messages")) {
            getLogger().warning("Message customisation has moved to RCUI. Your config.yml still has a"
                    + " 'messages' block; it is no longer read. Re-apply your wording in"
                    + " plugins/RCUI/messages/" + MESSAGE_NAMESPACE + ".yml, then delete the block.");
        }
    }

    /** The live service, for consumers that would rather hard-depend than use ServicesManager. */
    public PartyService partyService() {
        return manager;
    }
}
