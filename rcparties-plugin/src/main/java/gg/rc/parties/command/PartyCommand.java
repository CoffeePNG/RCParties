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
import net.kyori.adventure.text.format.NamedTextColor;
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
            player.sendMessage(messages.render("created",
                    "<green>Party created. Invite someone with <white>/party invite <player></white>.</green>"));
        } else {
            player.sendMessage(messages.forOutcome(outcome));
        }
    }

    private void invite(Player actor, Player target) {
        PartyOutcome outcome = manager.invite(actor.getUniqueId(), target.getUniqueId());
        if (!outcome.isSuccess()) {
            actor.sendMessage(messages.forOutcome(outcome));
            return;
        }
        long seconds = manager.config().inviteTtlMillis() / 1000L;
        actor.sendMessage(messages.render("invite-sent",
                "<green>Invited <white><player></white>. The invite expires in <white><seconds></white>s.</green>",
                "player", target.getName(), "seconds", String.valueOf(seconds)));
        target.sendMessage(messages.render("invited",
                "<green><player> invited you. <hover:show_text:'Click'><click:run_command:'/party accept "
                        + actor.getName() + "'>/party accept <leader></click></hover></green>",
                "player", actor.getName(), "leader", actor.getName(), "seconds", String.valueOf(seconds)));
    }

    private void accept(Player actor, Player from) {
        PartyOutcome outcome = manager.accept(actor.getUniqueId(), from.getUniqueId());
        if (!outcome.isSuccess()) {
            actor.sendMessage(messages.forOutcome(outcome));
            return;
        }
        manager.getParty(actor.getUniqueId()).ifPresent(party ->
                broadcast(party, messages.render("joined", "<green><player> joined the party.</green>",
                        "player", actor.getName())));
    }

    private void deny(Player actor, Player from) {
        PartyOutcome outcome = manager.deny(actor.getUniqueId(), from.getUniqueId());
        if (!outcome.isSuccess()) {
            actor.sendMessage(messages.forOutcome(outcome));
            return;
        }
        actor.sendMessage(messages.render("denied", "<gray>Invite declined.</gray>"));
        from.sendMessage(messages.render("deny-notice", "<gray><player> declined your invite.</gray>",
                "player", actor.getName()));
    }

    private void leave(Player player) {
        // Capture the party before the leave, so the remaining members can still be told.
        Party before = manager.getParty(player.getUniqueId()).orElse(null);
        PartyOutcome outcome = manager.leave(player.getUniqueId());
        if (!outcome.isSuccess()) {
            player.sendMessage(messages.forOutcome(outcome));
            return;
        }
        player.sendMessage(messages.render("left", "<gray>You left the party.</gray>"));
        if (before != null) {
            broadcastExcept(before, player.getUniqueId(),
                    messages.render("member-left", "<gray><player> left the party.</gray>",
                            "player", player.getName()));
        }
    }

    private void kick(Player actor, Player target) {
        Party before = manager.getParty(actor.getUniqueId()).orElse(null);
        PartyOutcome outcome = manager.kick(actor.getUniqueId(), target.getUniqueId());
        if (!outcome.isSuccess()) {
            actor.sendMessage(messages.forOutcome(outcome));
            return;
        }
        target.sendMessage(messages.render("kicked", "<red>You were removed from the party.</red>"));
        if (before != null) {
            broadcastExcept(before, target.getUniqueId(),
                    messages.render("member-kicked", "<gray><player> was removed from the party.</gray>",
                            "player", target.getName()));
        }
    }

    private void promote(Player actor, Player target) {
        PartyOutcome outcome = manager.promote(actor.getUniqueId(), target.getUniqueId());
        if (!outcome.isSuccess()) {
            actor.sendMessage(messages.forOutcome(outcome));
            return;
        }
        manager.getParty(actor.getUniqueId()).ifPresent(party ->
                broadcast(party, messages.render("promoted",
                        "<green><player> is now the party leader.</green>", "player", target.getName())));
    }

    private void disband(Player player) {
        Party before = manager.getParty(player.getUniqueId()).orElse(null);
        PartyOutcome outcome = manager.disband(player.getUniqueId());
        if (!outcome.isSuccess()) {
            player.sendMessage(messages.forOutcome(outcome));
            return;
        }
        if (before != null) {
            broadcast(before, messages.render("disbanded", "<gray>The party was disbanded.</gray>"));
        }
    }

    private void list(Player player) {
        Party party = manager.getParty(player.getUniqueId()).orElse(null);
        if (party == null) {
            player.sendMessage(messages.forOutcome(PartyOutcome.NOT_IN_PARTY));
            showPendingInvites(player);
            return;
        }
        player.sendMessage(messages.render("list-header",
                "<gold>Party</gold> <gray>(<size>/<max>)</gray>",
                "size", String.valueOf(party.size()), "max", String.valueOf(party.maxSize())));
        for (UUID member : party.members()) {
            boolean leader = party.isLeader(member);
            player.sendMessage(messages.render(leader ? "list-leader" : "list-member",
                    leader ? "<gray> - </gray><gold><player> (leader)</gold>"
                            : "<gray> - <player></gray>",
                    "player", nameOf(member)));
        }
        if (party.isLocked()) {
            player.sendMessage(messages.render("list-locked",
                    "<yellow>In an activity: <locks></yellow>",
                    "locks", String.join(", ", party.activityLocks())));
        }
    }

    private void showPendingInvites(Player player) {
        List<Invite> invites = manager.pendingInvites(player.getUniqueId());
        for (Invite invite : invites) {
            player.sendMessage(messages.render("pending-invite",
                    "<gray>Pending invite from <white><player></white>.</gray>",
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
                    sender.sendMessage(Component.text("Live parties: " + parties.size(), NamedTextColor.GOLD));
                    for (Party party : parties) {
                        sender.sendMessage(Component.text(
                                " - " + party.id() + " leader=" + nameOf(party.leader())
                                        + " members=" + party.size()
                                        + " locks=" + party.activityLocks(),
                                NamedTextColor.GRAY));
                    }
                    return parties.size();
                }))
                .then(Commands.literal("inspect")
                        .then(playerArg().executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            Player target = resolveTarget(ctx);
                            Party party = manager.getParty(target.getUniqueId()).orElse(null);
                            if (party == null) {
                                sender.sendMessage(Component.text(
                                        target.getName() + " is not in a party.", NamedTextColor.GRAY));
                                return 0;
                            }
                            sender.sendMessage(Component.text(
                                    party.id() + " leader=" + nameOf(party.leader())
                                            + " created=" + party.createdAt()
                                            + " locks=" + party.activityLocks()
                                            + " metadata=" + party.metadata(),
                                    NamedTextColor.GRAY));
                            for (UUID member : party.members()) {
                                sender.sendMessage(Component.text("   " + nameOf(member) + " " + member,
                                        NamedTextColor.DARK_GRAY));
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
                                    sender.sendMessage(Component.text(
                                            ok ? "Party disbanded." : "No such party.",
                                            ok ? NamedTextColor.GREEN : NamedTextColor.RED));
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
                                    sender.sendMessage(Component.text(
                                            cleared ? "Activity locks cleared." : "No locks to clear.",
                                            cleared ? NamedTextColor.GREEN : NamedTextColor.GRAY));
                                    return cleared ? 1 : 0;
                                })))
                .then(Commands.literal("reload").executes(ctx -> {
                    reloadSettings.run();
                    ctx.getSource().getSender().sendMessage(
                            Component.text("RCParties config reloaded.", NamedTextColor.GREEN));
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
            sender.sendMessage(Component.text("Only players can use /party.", NamedTextColor.RED));
            return 0;
        }
        action.accept(player);
        return 1;
    }

    private int withTarget(CommandContext<CommandSourceStack> ctx, java.util.function.BiConsumer<Player, Player> action) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use /party.", NamedTextColor.RED));
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
            sender.sendMessage(Component.text("Not a valid party id: " + raw, NamedTextColor.RED));
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
