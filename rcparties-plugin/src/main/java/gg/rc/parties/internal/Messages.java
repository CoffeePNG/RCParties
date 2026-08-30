package gg.rc.parties.internal;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Renders the configurable MiniMessage strings from config.yml. Every player-facing string
 * in the plugin goes through here, so server owners can reword all of it and nothing is
 * hardcoded past the built-in fallback.
 */
public final class Messages {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private volatile PartiesConfig config;

    public Messages(PartiesConfig config) {
        this.config = config;
    }

    public void setConfig(PartiesConfig config) {
        this.config = config;
    }

    /**
     * Renders {@code messages.<key>} with {@code <placeholder>} substitutions.
     *
     * @param placeholders alternating name/value pairs, e.g. {@code "player", "Steve"}
     */
    public Component render(String key, String fallback, String... placeholders) {
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("placeholders must be name/value pairs");
        }
        List<TagResolver> resolvers = new ArrayList<>(placeholders.length / 2);
        for (int i = 0; i < placeholders.length; i += 2) {
            resolvers.add(Placeholder.unparsed(placeholders[i], placeholders[i + 1]));
        }
        return miniMessage.deserialize(config.message(key, fallback), TagResolver.resolver(resolvers));
    }

    /** Player-facing wording for a refusal, so every command reports failures identically. */
    public Component forOutcome(PartyOutcome outcome) {
        if (outcome.isSuccess()) {
            return Component.empty();
        }
        return render(outcome.messageKey(), outcome.defaultMessage());
    }
}
