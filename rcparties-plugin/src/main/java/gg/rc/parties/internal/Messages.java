package gg.rc.parties.internal;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.republicraft.rcui.api.MessageBundle;

/**
 * The single seam between RCParties and RCUI's catalog. Nothing else in the plugin touches
 * {@link MessageBundle} directly, which keeps two decisions in one place: how a value
 * becomes a placeholder, and which messages carry the RC prefix.
 *
 * <p><b>The trust boundary.</b> A {@link Component} argument is trusted and renders as
 * markup; anything else is inserted as literal text. Config-authored strings are meant to
 * render, but a player name must never be able to smuggle MiniMessage tags into a
 * broadcast — pass a raw name and it is escaped, pass a Component and you have said
 * explicitly that you trust it.
 */
public final class Messages {

    private final MessageBundle bundle;

    public Messages(MessageBundle bundle) {
        this.bundle = bundle;
    }

    /** Prefixed, for chat sent to a player. */
    public void send(Audience audience, String key, Object... placeholders) {
        audience.sendMessage(bundle.message(key, resolvers(placeholders)));
    }

    /** Prefixed, when the caller needs the component rather than sending it. */
    public Component message(String key, Object... placeholders) {
        return bundle.message(key, resolvers(placeholders));
    }

    /** Unprefixed, for list rows, action bars, and composition. */
    public Component component(String key, Object... placeholders) {
        return bundle.component(key, resolvers(placeholders));
    }

    /** Sends the wording for a refusal, so every command reports failures identically. */
    public void sendOutcome(Audience audience, PartyOutcome outcome) {
        if (outcome.isSuccess()) {
            return;
        }
        send(audience, outcome.messageKey());
    }

    /**
     * Converts alternating name/value pairs into resolvers, applying the trust boundary
     * described above.
     */
    private static TagResolver[] resolvers(Object... placeholders) {
        if (placeholders.length % 2 != 0) {
            throw new IllegalArgumentException("placeholders must be name/value pairs");
        }
        List<TagResolver> resolvers = new ArrayList<>(placeholders.length / 2);
        for (int i = 0; i < placeholders.length; i += 2) {
            String name = String.valueOf(placeholders[i]);
            Object value = placeholders[i + 1];
            resolvers.add(value instanceof Component component
                    ? Placeholder.component(name, component)
                    : Placeholder.unparsed(name, String.valueOf(value)));
        }
        return resolvers.toArray(new TagResolver[0]);
    }
}
