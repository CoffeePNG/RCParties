package gg.rc.parties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.rc.parties.internal.PartyOutcome;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * RCUI validates the bundled catalog at boot and disables RCParties if it is wrong, and it
 * has no fallback — a key referenced by code but absent from messages.yml throws
 * MissingMessageException when a player triggers it. Neither failure is reachable from a
 * unit test of the plugin, so these tests mirror RCUI's own rules
 * (MessageServiceImpl.validateLeaves) and fail the build instead of the server.
 */
class MessageCatalogTest {

    /** Copied from RCUI's MessageServiceImpl. */
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9_-]*(?:\\.[a-z0-9][a-z0-9_-]*)*");
    private static final Set<String> METADATA_KEYS = Set.of("prefix", "legacy-prefixes", "removed-paths");

    /** Every key the plugin can ask for. Kept beside the send sites it mirrors. */
    private static final List<String> KEYS_USED_BY_CODE = List.of(
            "party.created", "party.invite-sent", "party.invited", "party.joined", "party.denied",
            "party.deny-notice", "party.left", "party.member-left", "party.kicked",
            "party.member-kicked", "party.promoted", "party.disbanded", "party.pending-invite",
            "list.header", "list.leader", "list.member", "list.locked",
            "error.players-only",
            "admin.party-count", "admin.party-row", "admin.not-in-party", "admin.inspect-header",
            "admin.inspect-member", "admin.invalid-party-id", "admin.disbanded",
            "admin.no-such-party", "admin.locks-cleared", "admin.no-locks", "admin.reloaded");

    /** Flattens the catalog to dotted leaf paths, the shape RCUI validates. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> leaves() {
        try (InputStream in = MessageCatalogTest.class.getClassLoader().getResourceAsStream("messages.yml")) {
            assertNotNull(in, "messages.yml is missing from the plugin resources");
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> flat = new LinkedHashMap<>();
            flatten("", root, flat);
            return flat;
        } catch (Exception e) {
            throw new AssertionError("could not read messages.yml", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> node, Map<String, Object> out) {
        node.forEach((key, value) -> {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Map<?, ?> child) {
                flatten(path, (Map<String, Object>) child, out);
            } else {
                out.put(path, value);
            }
        });
    }

    @Test
    @DisplayName("the bundled catalog is the flat shape RCUI expects, not the operator shape")
    void catalogUsesBundledShape() {
        Map<String, Object> root = new Yaml().load(
                MessageCatalogTest.class.getClassLoader().getResourceAsStream("messages.yml"));

        // Both of these make RCUI reject the catalog and disable the plugin at boot.
        assertFalse(root.containsKey("schema-version"),
                "a bundled catalog must not declare schema-version; that is the operator-file shape");
        assertFalse(root.containsKey("messages"),
                "a bundled catalog is a flat tree at the root, with no messages: wrapper");
    }

    @Test
    @DisplayName("every leaf passes RCUI's validateLeaves rules")
    void everyLeafIsValid() {
        List<String> errors = new ArrayList<>();

        leaves().forEach((path, value) -> {
            if (METADATA_KEYS.contains(path)) {
                return;
            }
            if (!KEY.matcher(path).matches()) {
                errors.add("invalid key '" + path + "'");
            } else if (!(value instanceof String text)) {
                errors.add("entry '" + path + "' must be a string, was "
                        + (value == null ? "null" : value.getClass().getSimpleName()));
            } else if (text.isBlank()) {
                errors.add("entry '" + path + "' cannot be blank");
            } else {
                try {
                    MiniMessage.miniMessage().deserialize(text);
                } catch (RuntimeException e) {
                    errors.add("entry '" + path + "' is invalid MiniMessage: " + e.getMessage());
                }
            }
        });

        assertTrue(errors.isEmpty(), "RCUI would reject this catalog:\n  " + String.join("\n  ", errors));
    }

    @Test
    @DisplayName("the prefix is valid MiniMessage and carries the RC gradient")
    void prefixIsValid() {
        Object prefix = leaves().get("prefix");
        assertTrue(prefix instanceof String, "prefix must be a string");

        String text = (String) prefix;
        assertDoesNotThrow(() -> MiniMessage.miniMessage().deserialize(text), "prefix is invalid MiniMessage");
        assertTrue(text.contains("<gradient:#FF7F50:#DB7093:#9370DB:#87CEFA>"),
                "prefix should keep the RC house gradient: " + text);
        assertTrue(text.contains("»"), "prefix should keep the guillemet: " + text);
    }

    @Test
    @DisplayName("the catalog defines every refusal PartyOutcome names")
    void catalogCoversEveryOutcome() {
        Map<String, Object> leaves = leaves();

        for (PartyOutcome outcome : PartyOutcome.values()) {
            if (outcome.isSuccess()) {
                continue;
            }
            assertTrue(leaves.containsKey(outcome.messageKey()),
                    "messages.yml is missing " + outcome.messageKey() + " for " + outcome
                            + "; RCUI has no fallback, so this throws when a player hits it");
        }
    }

    @Test
    @DisplayName("the catalog defines every key the command layer sends")
    void catalogCoversEveryKeyUsedByCode() {
        Map<String, Object> leaves = leaves();

        for (String key : KEYS_USED_BY_CODE) {
            assertTrue(leaves.containsKey(key),
                    "messages.yml is missing " + key + "; RCUI throws MissingMessageException for it");
        }
    }

    @Test
    @DisplayName("the catalog ships no keys nothing sends")
    void catalogHasNoOrphanKeys() {
        List<String> known = new ArrayList<>(KEYS_USED_BY_CODE);
        for (PartyOutcome outcome : PartyOutcome.values()) {
            if (!outcome.isSuccess()) {
                known.add(outcome.messageKey());
            }
        }

        List<String> orphans = leaves().keySet().stream()
                .filter(key -> !METADATA_KEYS.contains(key))
                .filter(key -> !known.contains(key))
                .toList();

        assertTrue(orphans.isEmpty(),
                "these catalog keys are never sent, so operators would edit dead wording: " + orphans);
    }
}
