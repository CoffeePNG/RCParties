package gg.rc.parties.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import gg.rc.parties.api.Invite;
import gg.rc.parties.api.Party;
import gg.rc.parties.internal.Messages;
import gg.rc.parties.internal.PartyManager;
import gg.rc.parties.internal.PartyOutcome;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The {@code /party} tree (§4). Every branch does the same three things: resolve the actor
 * and target, hand the decision to {@link PartyManager}, then report the
 * {@link PartyOutcome}. No party rules live here.
 */
public final class PartyCommand {

    private static final String PERM_USE = "rcparties.use";
    private static final String PERM_ADMIN = "rcparties.admin";

    private final PartyManager manager;
    private final Messages messages;
    private final Runnable reloadSettings;

    public PartyCommand(PartyManager manager, Messages messages, Runnable reloadSettings) {
        this.manager = manager;
        this.messages = messages;
        this.reloadSettings = reloadSettings;
    }

    /** Builds the command node. Registered against {@code /party} with the {@code /pt} alias. */
    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("party")
                .requires(source -> source.getSender().hasPermission(PERM_USE))
                .executes(ctx -> playerOnly(ctx, this::list))
                .then(Commands.literal("create")
                        .executes(ctx -> playerOnly(ctx, this::create)))
                .then(Commands.literal("invite")
                        .then(playerArg().executes(ctx -> withTarget(ctx, this::invite))))
                .then(Commands.literal("accept")
                        .then(playerArg().executes(ctx -> withTarget(ctx, this::accept))))
                .then(Commands.literal("deny")
                        .then(playerArg().executes(ctx -> withTarget(ctx, this::deny))))
                .then(Commands.literal("leave")
                        .executes(ctx -> playerOnly(ctx, this::leave)))
                .then(Commands.literal("kick")
                        .then(playerArg().executes(ctx -> withTarget(ctx, this::kick))))
                .then(Commands.literal("promote")
                        .then(playerArg().executes(ctx -> withTarget(ctx, this::promote))))
                .then(Commands.literal("disband")
                        .executes(ctx -> playerOnly(ctx, this::disband)))
                .then(Commands.literal("list")
                        .executes(ctx -> playerOnly(ctx, this::list)))
                .then(adminBranch())
                .build();
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, PlayerSelectorArgumentResolver> playerArg() {
        return Commands.argument("player", ArgumentTypes.player());
    }

    // ---- player commands ------------------------------------------------

    private void create(Player player) {
        PartyOutcome outcome = manager.create(player.getUniqueId());
        if (outcome.isSuccess()) {
            messages.send(player, "party.created");
        } else {
            messages.sendOutcome(player, outcome);
        }
    }

    private void invite(Player actor, Player target) {
        PartyOutcome outcome = manager.invite(actor.getUniqueId(), target.getUniqueId());
        if (!outcome.isSuccess()) {
            messages.sendOutcome(actor, outcome);
            return;
        }
        long seconds = manager.config().inviteTtlMillis() / 1000L;
        messages.send(actor, "party.invite-sent", "player", target.getName(), "seconds", seconds);
        messages.send(target, "party.invited", "player", actor.getName(), "leader", actor.getName());
    }

    private void accept(Player actor, Player from) {
        PartyOutcome outcome = manager.accept(actor.getUniqueId(), from.getUniqueId());
        if (!outcome.isSuccess()) {
            messages.sendOutcome(actor, outcome);
            return;
        }
        manager.getParty(actor.getUniqueId()).ifPresent(party ->
                broadcast(party, messages.message("party.joined", "player", actor.getName())));
    }

    private void deny(Player actor, Player from) {
        PartyOutcome outcome = manager.deny(actor.getUniqueId(), from.getUniqueId());
        if (!outcome.isSuccess()) {
            messages.sendOutcome(actor, outcome);
            return;
        }
        messages.send(actor, "party.denied");
        messages.send(from, "party.deny-notice", "player", actor.getName());
    }

    private void leave(Player player) {
        // Capture the party before the leave, so the remaining members can still be told.
        Party before = manager.getParty(player.getUniqueId()).orElse(null);
        PartyOutcome outcome = manager.leave(player.getUniqueId());
        if (!outcome.isSuccess()) {
            messages.sendOutcome(player, outcome);
            return;
        }
        messages.send(player, "party.left");
        if (before != null) {
            broadcastExcept(before, player.getUniqueId(),
                    messages.message("party.member-left", "player", player.getName()));
        }
    }

    private void kick(Player actor, Player target) {
        Party before = manager.getParty(actor.getUniqueId()).orElse(null);
        PartyOutcome outcome = manager.kick(actor.getUniqueId(), target.getUniqueId());
        if (!outcome.isSuccess()) {
            messages.sendOutcome(actor, outcome);
            return;
        }
        messages.send(target, "party.kicked");
        if (before != null) {
            broadcastExcept(before, target.getUniqueId(),
                    messages.message("party.member-kicked", "player", target.getName()));
        }
    }

    private void promote(Player actor, Player target) {
        PartyOutcome outcome = manager.promote(actor.getUniqueId(), target.getUniqueId());
        if (!outcome.isSuccess()) {
            messages.sendOutcome(actor, outcome);
            return;
        }
        manager.getParty(actor.getUniqueId()).ifPresent(party ->
                broadcast(party, messages.message("party.promoted", "player", target.getName())));
    }

    private void disband(Player player) {
        Party before = manager.getParty(player.getUniqueId()).orElse(null);
        PartyOutcome outcome = manager.disband(player.getUniqueId());
        if (!outcome.isSuccess()) {
            messages.sendOutcome(player, outcome);
            return;
        }
        if (before != null) {
            broadcast(before, messages.message("party.disbanded"));
        }
    }

    private void list(Player player) {
        Party party = manager.getParty(player.getUniqueId()).orElse(null);
        if (party == null) {
            messages.sendOutcome(player, PartyOutcome.NOT_IN_PARTY);
            showPendingInvites(player);
            return;
        }
        // The header carries the prefix; the rows below are unprefixed so it appears once.
        messages.send(player, "list.header", "size", party.size(), "max", party.maxSize());
        for (UUID member : party.members()) {
            player.sendMessage(messages.component(
                    party.isLeader(member) ? "list.leader" : "list.member", "player", nameOf(member)));
        }
        if (party.isLocked()) {
            player.sendMessage(messages.component("list.locked",
                    "locks", String.join(", ", party.activityLocks())));
        }
    }

    private void showPendingInvites(Player player) {
        List<Invite> invites = manager.pendingInvites(player.getUniqueId());
        for (Invite invite : invites) {
            player.sendMessage(messages.component("party.pending-invite",
                    "player", nameOf(invite.from())));
        }
    }

    // ---- admin branch (§4) ----------------------------------------------

    private LiteralArgumentBuilder<CommandSourceStack> adminBranch() {
        return Commands.literal("admin")
                .requires(source -> source.getSender().hasPermission(PERM_ADMIN))
                .then(Commands.literal("list").executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    List<Party> parties = manager.allParties();
                    messages.send(sender, "admin.party-count", "count", parties.size());
                    for (Party party : parties) {
                        sender.sendMessage(messages.component("admin.party-row",
                                "id", party.id(),
                                "leader", nameOf(party.leader()),
                                "size", party.size(),
                                "locks", party.activityLocks()));
                    }
                    return parties.size();
                }))
                .then(Commands.literal("inspect")
                        .then(playerArg().executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            Player target = resolveTarget(ctx);
                            Party party = manager.getParty(target.getUniqueId()).orElse(null);
                            if (party == null) {
                                messages.send(sender, "admin.not-in-party", "player", target.getName());
                                return 0;
                            }
                            messages.send(sender, "admin.inspect-header",
                                    "id", party.id(),
                                    "leader", nameOf(party.leader()),
                                    "created", party.createdAt(),
                                    "locks", party.activityLocks(),
                                    "metadata", party.metadata());
                            for (UUID member : party.members()) {
                                sender.sendMessage(messages.component("admin.inspect-member",
                                        "player", nameOf(member), "uuid", member));
                            }
                            return 1;
                        })))
                .then(Commands.literal("disband")
                        .then(Commands.argument("partyId", StringArgumentType.word())
                                .suggests(this::suggestPartyIds)
                                .executes(ctx -> {
                                    CommandSender sender = ctx.getSource().getSender();
                                    UUID id = parseUuid(sender, ctx.getArgument("partyId", String.class));
                                    if (id == null) {
                                        return 0;
                                    }
                                    boolean ok = manager.adminDisband(id).isSuccess();
                                    messages.send(sender, ok ? "admin.disbanded" : "admin.no-such-party");
                                    return ok ? 1 : 0;
                                })))
                .then(Commands.literal("clearlocks")
                        .then(Commands.argument("partyId", StringArgumentType.word())
                                .suggests(this::suggestPartyIds)
                                .executes(ctx -> {
                                    CommandSender sender = ctx.getSource().getSender();
                                    UUID id = parseUuid(sender, ctx.getArgument("partyId", String.class));
                                    if (id == null) {
                                        return 0;
                                    }
                                    boolean cleared = manager.adminClearLocks(id);
                                    messages.send(sender, cleared ? "admin.locks-cleared" : "admin.no-locks");
                                    return cleared ? 1 : 0;
                                })))
                .then(Commands.literal("reload").executes(ctx -> {
                    reloadSettings.run();
                    messages.send(ctx.getSource().getSender(), "admin.reloaded");
                    return 1;
                }));
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPartyIds(
            CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        for (Party party : manager.allParties()) {
            String id = party.id().toString();
            if (id.toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) {
                builder.suggest(id);
            }
        }
        return builder.buildFuture();
    }

    // ---- plumbing -------------------------------------------------------

    private int playerOnly(CommandContext<CommandSourceStack> ctx, java.util.function.Consumer<Player> action) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.players-only");
            return 0;
        }
        action.accept(player);
        return 1;
    }

    private int withTarget(CommandContext<CommandSourceStack> ctx, java.util.function.BiConsumer<Player, Player> action) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            messages.send(sender, "error.players-only");
            return 0;
        }
        action.accept(player, resolveTarget(ctx));
        return 1;
    }

    /** Paper's player selector always resolves to at least one player or throws first. */
    private Player resolveTarget(CommandContext<CommandSourceStack> ctx) {
        PlayerSelectorArgumentResolver resolver =
                ctx.getArgument("player", PlayerSelectorArgumentResolver.class);
        try {
            return resolver.resolve(ctx.getSource()).getFirst();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new IllegalStateException("player selector failed to resolve", e);
        }
    }

    private UUID parseUuid(CommandSender sender, String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            messages.send(sender, "admin.invalid-party-id", "id", raw);
            return null;
        }
    }

    private void broadcast(Party party, Component message) {
        broadcastExcept(party, null, message);
    }

    private void broadcastExcept(Party party, UUID skip, Component message) {
        for (UUID member : party.members()) {
            if (member.equals(skip)) {
                continue;
            }
            Player online = Bukkit.getPlayer(member);
            if (online != null) {
                online.sendMessage(message);
            }
        }
    }

    private static String nameOf(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName();
        return name != null ? name : uuid.toString().substring(0, 8);
    }

    /** Aliases registered alongside {@code /party}. Deliberately not {@code /p} (§4). */
    public static List<String> aliases() {
        return new ArrayList<>(List.of("pt"));
    }
}
