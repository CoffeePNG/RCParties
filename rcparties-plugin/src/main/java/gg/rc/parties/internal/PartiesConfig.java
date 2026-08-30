package gg.rc.parties.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * A snapshot of config.yml. Read once on enable and on {@code /party admin reload}, so the
 * rest of the plugin never touches Bukkit configuration objects directly and stays unit
 * testable.
 */
public final class PartiesConfig {

    /** Defaults mirror the documented config.yml so a missing key is never fatal. */
    private static final int DEFAULT_MAX_PARTY_SIZE = 8;
    private static final int DEFAULT_INVITE_TTL_SECONDS = 60;
    private static final boolean DEFAULT_ALLOW_MEMBER_INVITES = true;

    private final int maxPartySize;
    private final long inviteTtlMillis;
    private final boolean allowMemberInvites;
    private final Map<String, String> messages;

    public PartiesConfig(int maxPartySize, int inviteTtlSeconds, boolean allowMemberInvites,
                         Map<String, String> messages) {
        this.maxPartySize = Math.max(1, maxPartySize);
        this.inviteTtlMillis = Math.max(1L, inviteTtlSeconds) * 1000L;
        this.allowMemberInvites = allowMemberInvites;
        this.messages = Map.copyOf(messages);
    }

    public static PartiesConfig from(FileConfiguration config) {
        Map<String, String> messages = new LinkedHashMap<>();
        ConfigurationSection section = config.getConfigurationSection("messages");
        if (section != null) {
            for (String key : section.getKeys(true)) {
                if (!section.isConfigurationSection(key)) {
                    messages.put(key, String.valueOf(section.get(key)));
                }
            }
        }
        return new PartiesConfig(
                config.getInt("max-party-size", DEFAULT_MAX_PARTY_SIZE),
                config.getInt("invite-ttl-seconds", DEFAULT_INVITE_TTL_SECONDS),
                config.getBoolean("allow-member-invites", DEFAULT_ALLOW_MEMBER_INVITES),
                messages);
    }

    /** Config with every documented default, used by tests and as a fallback. */
    public static PartiesConfig defaults() {
        return new PartiesConfig(DEFAULT_MAX_PARTY_SIZE, DEFAULT_INVITE_TTL_SECONDS,
                DEFAULT_ALLOW_MEMBER_INVITES, Map.of());
    }

    public int maxPartySize() {
        return maxPartySize;
    }

    public long inviteTtlMillis() {
        return inviteTtlMillis;
    }

    public boolean allowMemberInvites() {
        return allowMemberInvites;
    }

    /** Raw MiniMessage template for a key, or {@code fallback} when unset. */
    public String message(String key, String fallback) {
        return messages.getOrDefault(key, fallback);
    }
}
