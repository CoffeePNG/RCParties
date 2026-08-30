package gg.rc.parties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.rc.parties.internal.Messages;
import gg.rc.parties.internal.PartiesConfig;
import gg.rc.parties.internal.PartyOutcome;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.yaml.snakeyaml.Yaml;

/**
 * A malformed MiniMessage tag only blows up when a player triggers that exact message, and
 * no console command can reach those paths. These tests parse every string we ship — both
 * the config.yml defaults and the hardcoded fallbacks — so a broken tag fails the build
 * instead of a player's command.
 */
class MessagesTest {

    /** Every placeholder any message may use, so no tag is left unresolved during the check. */
    private static final String[] ALL_PLACEHOLDERS = {
        "player", "Steve", "leader", "Alex", "seconds", "60", "size", "3", "max", "8", "locks", "RCPuttPutt"
    };

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> shippedMessages() {
        try (InputStream in = MessagesTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertNotNull(in, "config.yml is missing from the plugin resources");
            Map<String, Object> root = new Yaml().load(in);
            Object messages = root.get("messages");
            assertTrue(messages instanceof Map, "config.yml has no messages section");

            Map<String, String> flat = new LinkedHashMap<>();
            ((Map<String, Object>) messages).forEach((key, value) -> flat.put(key, String.valueOf(value)));
            return flat;
        } catch (Exception e) {
            throw new AssertionError("could not read config.yml", e);
        }
    }

    @Test
    @DisplayName("every message shipped in config.yml is valid MiniMessage")
    void shippedMessagesParse() {
        Map<String, String> shipped = shippedMessages();
        assertFalse(shipped.isEmpty(), "config.yml should ship messages");

        Messages messages = new Messages(new PartiesConfig(8, 60, true, shipped));
        for (String key : shipped.keySet()) {
            Component rendered = assertDoesNotThrow(
                    () -> messages.render(key, "<red>fallback</red>", ALL_PLACEHOLDERS),
                    "messages." + key + " is not valid MiniMessage");
            assertFalse(plain(rendered).isBlank(), "messages." + key + " rendered to nothing");
        }
    }

    @Test
    @DisplayName("config.yml has an entry for every refusal the manager can return")
    void shippedMessagesCoverEveryOutcome() {
        Map<String, String> shipped = shippedMessages();

        for (PartyOutcome outcome : PartyOutcome.values()) {
            if (outcome.isSuccess()) {
                continue;
            }
            assertTrue(shipped.containsKey(outcome.messageKey()),
                    "config.yml is missing messages." + outcome.messageKey()
                            + ", the wording for " + outcome);
        }
    }

    @ParameterizedTest
    @EnumSource(PartyOutcome.class)
    @DisplayName("every outcome renders from its built-in fallback without a config")
    void everyOutcomeRendersFromFallback(PartyOutcome outcome) {
        Messages messages = new Messages(PartiesConfig.defaults());

        Component rendered = assertDoesNotThrow(() -> messages.forOutcome(outcome),
                outcome + " has a malformed built-in message");
        if (outcome != PartyOutcome.SUCCESS) {
            assertFalse(plain(rendered).isBlank(), outcome + " rendered to nothing");
        }
    }

    @Test
    @DisplayName("placeholders are substituted rather than left as literal tags")
    void placeholdersAreSubstituted() {
        Messages messages = new Messages(new PartiesConfig(8, 60, true,
                Map.of("greet", "<green>Hi <player>, <size>/<max></green>")));

        String rendered = plain(messages.render("greet", "unused", ALL_PLACEHOLDERS));

        assertTrue(rendered.contains("Steve"), "expected the player placeholder to resolve: " + rendered);
        assertTrue(rendered.contains("3/8"), "expected size/max to resolve: " + rendered);
        assertFalse(rendered.contains("<player>"), "placeholder left unresolved: " + rendered);
    }

    @Test
    @DisplayName("player-supplied names cannot inject MiniMessage markup")
    void placeholderValuesAreNotParsedAsMarkup() {
        Messages messages = new Messages(new PartiesConfig(8, 60, true,
                Map.of("greet", "<green>Hi <player></green>")));

        // A player named with markup must not be able to smuggle tags into other players' chat.
        String rendered = plain(messages.render("greet", "unused", "player", "<red>evil</red>"));

        assertTrue(rendered.contains("<red>evil</red>"),
                "placeholder values must be inserted literally, not parsed: " + rendered);
    }

    @Test
    @DisplayName("an unset key falls back instead of rendering blank")
    void missingKeyUsesFallback() {
        Messages messages = new Messages(new PartiesConfig(8, 60, true, Map.of()));

        String rendered = plain(messages.render("not-configured", "<gray>fallback text</gray>"));
        assertTrue(rendered.contains("fallback text"), rendered);
    }

    @Test
    @DisplayName("odd placeholder arguments are rejected loudly")
    void oddPlaceholderCountIsRejected() {
        Messages messages = new Messages(PartiesConfig.defaults());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> messages.render("k", "<red>x</red>", "player"));
    }
}
