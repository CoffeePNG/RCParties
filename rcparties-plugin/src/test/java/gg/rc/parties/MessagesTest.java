package gg.rc.parties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.rc.parties.internal.Messages;
import gg.rc.parties.internal.PartyOutcome;
import java.util.LinkedHashMap;
import java.util.Map;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.republicraft.rcui.api.MessageBundle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The seam in front of RCUI, and specifically the trust boundary it exists to enforce:
 * a Component argument is trusted markup, anything else is literal text.
 */
class MessagesTest {

    /** Minimal stand-in for RCUI's bundle: renders templates, records what was sent. */
    private static final class FakeBundle implements MessageBundle {
        private final Map<String, String> templates = new LinkedHashMap<>();
        private final java.util.List<Component> sent = new java.util.ArrayList<>();
        private static final String PREFIX = "<gray>[RC] </gray>";

        FakeBundle put(String key, String template) {
            templates.put(key, template);
            return this;
        }

        @Override
        public String namespace() {
            return "rcparties";
        }

        @Override
        public Component component(String key, TagResolver... arguments) {
            String template = templates.get(key);
            if (template == null) {
                // RCUI throws for an unknown key rather than falling back; mirror that.
                throw new IllegalStateException("missing message: " + key);
            }
            return MiniMessage.miniMessage().deserialize(template, arguments);
        }

        @Override
        public Component message(String key, TagResolver... arguments) {
            return MiniMessage.miniMessage().deserialize(PREFIX).append(component(key, arguments));
        }

        @Override
        public void broadcast(String key, TagResolver... arguments) {
            sent.add(message(key, arguments));
        }
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    @DisplayName("a player name cannot smuggle MiniMessage tags into a broadcast")
    void stringPlaceholdersAreInsertedLiterally() {
        Messages messages = new Messages(new FakeBundle().put("greet", "<green>Hi <player></green>"));

        String rendered = plain(messages.component("greet", "player", "<red>evil</red>"));

        assertTrue(rendered.contains("<red>evil</red>"),
                "a raw string must be inserted as literal text, not parsed: " + rendered);
    }

    @Test
    @DisplayName("a Component placeholder is trusted and renders as markup")
    void componentPlaceholdersRender() {
        Messages messages = new Messages(new FakeBundle().put("greet", "<green>Hi <player></green>"));
        Component trusted = Component.text("Steve", NamedTextColor.GOLD);

        Component rendered = messages.component("greet", "player", trusted);

        assertEquals("Hi Steve", plain(rendered));
        assertTrue(rendered.toString().contains("gold"),
                "a Component argument should keep its own styling: " + rendered);
    }

    @Test
    @DisplayName("non-string values are stringified rather than rejected")
    void numbersAndObjectsAreStringified() {
        Messages messages = new Messages(new FakeBundle().put("count", "<gray>(<size>/<max>)</gray>"));

        assertEquals("(3/8)", plain(messages.component("count", "size", 3, "max", 8)));
    }

    @Test
    @DisplayName("message() is prefixed and component() is not")
    void prefixingFollowsTheRcuiContract() {
        Messages messages = new Messages(new FakeBundle().put("hi", "<green>hello</green>"));

        assertTrue(plain(messages.message("hi")).startsWith("[RC] "), "message() must carry the prefix");
        assertEquals("hello", plain(messages.component("hi")), "component() must be unprefixed");
    }

    @Test
    @DisplayName("every refusal resolves to a key the bundle can render")
    void everyOutcomeHasARenderableKey() {
        FakeBundle bundle = new FakeBundle();
        for (PartyOutcome outcome : PartyOutcome.values()) {
            if (!outcome.isSuccess()) {
                bundle.put(outcome.messageKey(), "<red>refused</red>");
            }
        }
        Messages messages = new Messages(bundle);

        for (PartyOutcome outcome : PartyOutcome.values()) {
            if (outcome.isSuccess()) {
                continue;
            }
            assertEquals("[RC] refused", plain(messages.message(outcome.messageKey())),
                    outcome + " did not render");
        }
    }

    @Test
    @DisplayName("a successful outcome sends nothing at all")
    void successSendsNothing() {
        // The bundle knows no keys, so any attempt to render one throws.
        Messages messages = new Messages(new FakeBundle());

        assertDoesNotThrow(() -> messages.sendOutcome(Audience.empty(), PartyOutcome.SUCCESS),
                "SUCCESS must not try to render a message");
    }

    @Test
    @DisplayName("odd placeholder arguments are rejected loudly")
    void oddPlaceholderCountIsRejected() {
        Messages messages = new Messages(new FakeBundle().put("k", "<red>x</red>"));

        assertThrows(IllegalArgumentException.class, () -> messages.component("k", "player"));
    }

    @Test
    @DisplayName("a null placeholder value does not blow up")
    void nullPlaceholderValuesAreSafe() {
        Messages messages = new Messages(new FakeBundle().put("greet", "<green>Hi <player></green>"));

        String rendered = plain(messages.component("greet", "player", null));
        assertFalse(rendered.isBlank(), "a null value should render as text, not throw");
    }
}
