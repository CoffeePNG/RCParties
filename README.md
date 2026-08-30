# RCParties

Game-agnostic player grouping for Purpur/Paper. Implements **RC-SPEC-PARTIES-001**.

> **This branch targets Paper/Purpur 1.21.11 on Java 21.** The `main` line targets
> Purpur 26.2 on Java 25. The two differ only in `pom.xml` — no source differs — so
> changes port between them cleanly.

RCParties provides parties (a leader plus members, formed via invites) and a public API
plus event surface that any minigame or system plugin consumes. It knows nothing about
golf, racing, fishing, or betting — the moment a game concept leaks in, the reason to
split it from RCPuttPutt is gone.

## Design contract

- A player is in **at most one** party at a time.
- Parties are **in-memory only**. No persistence, no database, nothing survives a restart —
  which is correct, since everyone is disconnected anyway.
- Solo use is a **party of one**, so consumers have exactly one code path for "who is grouped".

## Modules

| Module | What it is |
|---|---|
| `rcparties-api` | The artifact consumers compile against: `PartyService`, `Party`, `Invite`, events. |
| `rcparties-plugin` | The implementation. Shades the API into the shipped jar. |

## Build

Requires JDK 21 (Paper 1.21.11 target).

Note that `paper-api` for 1.21.11 is published as a `-SNAPSHOT`, so a clean build resolves
whatever is current upstream. Pin `paper.version` to a dated snapshot if you need
reproducible builds.

```
mvn clean package
```

The server jar lands at `rcparties-plugin/target/RCParties-<version>.jar`.

## Commands

Root `/party`, alias `/pt`. `/p` is deliberately avoided — it collides with common
chat and plot plugins.

| Command | Permission | Notes |
|---|---|---|
| `/party create` | `rcparties.use` | Party of one, caller is leader. |
| `/party invite <player>` | `rcparties.use` | Config-gated for non-leaders. Creates a party if you have none. |
| `/party accept <leader>` | `rcparties.use` | |
| `/party deny <leader>` | `rcparties.use` | |
| `/party leave` | `rcparties.use` | Guarded by activity lock. |
| `/party kick <player>` | `rcparties.use` | Leader only, guarded by activity lock. |
| `/party promote <player>` | `rcparties.use` | Leader only. |
| `/party disband` | `rcparties.use` | Leader only, guarded by activity lock. |
| `/party list` | `rcparties.use` | Members, leader, and any held locks. |
| `/party admin list\|inspect\|disband\|clearlocks\|reload` | `rcparties.admin` | Support tooling. |

## Lifecycle rules

- **Leader leaves** → leadership transfers to the longest-tenured remaining member.
  If nobody remains, the party disbands.
- **Disconnect** → leave (v1). Grace-period rejoin is a later refinement.
- **Invites** expire after `invite-ttl-seconds`. Joining a party clears every other
  outstanding invite you hold.

## Activity locks

The mechanism that lets a consumer hold a party together for the duration of an activity
without RCParties knowing what the activity is.

```java
parties.lockActivity(partyId, "RCPuttPutt");   // round starts
// ... leave / kick / disband are now refused with a configurable message
parties.unlockActivity(partyId, "RCPuttPutt"); // round ends
```

Locks are keyed by plugin, so two consumers cannot clobber each other. If a consumer
plugin disables while holding locks — reloaded, crashed, shut down mid-round — RCParties
releases its locks automatically so no party is left stranded. `rcparties.admin` can
force-clear a stuck lock.

`promote` is **not** lock-guarded: membership doesn't change, so an in-progress activity
is unaffected.

## Consuming the API

Resolve it Vault-style, with no hard plugin dependency beyond the API artifact:

```java
RegisteredServiceProvider<PartyService> rsp =
        Bukkit.getServicesManager().getRegistration(PartyService.class);
if (rsp == null) {
    return; // RCParties not installed
}
PartyService parties = rsp.getProvider();

parties.getParty(player.getUniqueId())
       .ifPresent(party -> startRound(party.members()));
```

### Events

| Event | Cancellable | Fires when |
|---|---|---|
| `PartyCreateEvent` | no | Party created. |
| `PartyInviteEvent` | yes | Invite about to send. |
| `PartyJoinEvent` | no | Member joined. |
| `PartyLeaveEvent` | no | Member left, was kicked, or disconnected (carries a `Reason`). |
| `PartyLeaderChangeEvent` | no | Leadership transferred. |
| `PartyDisbandEvent` | no | Party dissolved (carries a `Reason`). |

Every event carries an immutable snapshot of the party. `PartyDisbandEvent` still lists
the members the party had when it dissolved, so consumers can clean up per-player state.

## Config

See `rcparties-plugin/src/main/resources/config.yml`. All messages are MiniMessage.

```yaml
max-party-size: 8
invite-ttl-seconds: 60
allow-member-invites: true
```

## Non-goals

No persistence, no database, no cross-restart parties. No game logic, scoring,
teleporting, or economy — consumers do all of that. No cross-server parties; a
Velocity-aware version would be a separate spec.
