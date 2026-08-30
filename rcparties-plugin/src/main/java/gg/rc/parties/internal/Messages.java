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
        return switch (outcome) {
            case SUCCESS -> Component.empty();
            case ALREADY_IN_PARTY -> render("already-in-party",
                    "<red>You are already in a party. Leave it first.</red>");
            case NOT_IN_PARTY -> render("not-in-party", "<red>You are not in a party.</red>");
            case TARGET_NOT_IN_PARTY -> render("target-not-in-party",
                    "<red>That player is not in your party.</red>");
            case TARGET_ALREADY_IN_PARTY -> render("target-already-in-party",
                    "<red>That player is already in a party.</red>");
            case NOT_LEADER -> render("not-leader", "<red>Only the party leader can do that.</red>");
            case PARTY_FULL -> render("party-full", "<red>That party is full.</red>");
            case LOCKED -> render("locked-cant-leave",
                    "<red>Leave your current activity before leaving the party.</red>");
            case NO_INVITE -> render("no-invite", "<red>You have no pending invite from that player.</red>");
            case INVITE_PENDING -> render("invite-pending",
                    "<red>That player already has a pending invite from your party.</red>");
            case INVITES_LEADER_ONLY -> render("invites-leader-only",
                    "<red>Only the party leader can invite players.</red>");
            case SELF_TARGET -> render("self-target", "<red>You cannot target yourself.</red>");
            case CANCELLED -> render("cancelled", "<red>That action was blocked.</red>");
        };
    }
}
