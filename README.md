# RCParties

Game-agnostic player grouping for Purpur/Paper. Implements **RC-SPEC-PARTIES-001**.

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

Requires **JDK 25** (Purpur 26.2 / Paper 26.2 target). The build checks this up front and
tells you if `JAVA_HOME` points somewhere older.

For servers still on 1.21.11, build the `legacy/1.21.11` branch instead, which targets
Paper 1.21.11 on JDK 21. The two branches differ only in `pom.xml`.

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

## Requirements

RCParties depends on **RCUI**, which itself requires **RCPlatform**. Both are part of the
wider RepubliCraft framework — consume their published APIs, never fork or vendor them.
Everything here targets **Java 25**.

Neither is on a public Maven repository, so build them into your local `~/.m2` first, in
this order (RCUI compiles against `rcplatform-api`):

```sh
git clone https://github.com/CoffeePNG/RCPlatform && (cd RCPlatform && mvn -B install -DskipTests)
git clone https://github.com/CoffeePNG/RCUI       && (cd RCUI       && mvn -B install -DskipTests)
```

Server load order is `RCPlatform -> RCUI -> RCParties`.

## Installing

Only **one** RCParties jar goes on the server:

| Jar | Goes where |
|---|---|
| `rcparties-plugin/target/RCParties-<version>.jar` | the server's `plugins/` folder |
| `rcparties-api/target/rcparties-api-<version>.jar` | a consumer plugin's `pom.xml`, **not** `plugins/` |

The API classes are shaded into the plugin jar, so the server already has them. Putting
`rcparties-api.jar` in `plugins/` does not work and is actively harmful: it has no
`paper-plugin.yml` for Paper to load, and the duplicate classes on a second classloader
cause `LinkageError` or a `ClassCastException` where a `Party` is somehow not a `Party`.

## Consuming the API

Install the API artifact into your local repository once:

```
mvn install
```

Then depend on it from the consumer plugin. Scope it `provided` — RCParties already ships
these classes at runtime, so shading them again reintroduces exactly the duplicate-class
problem described above:

```xml
<dependency>
  <groupId>gg.rc</groupId>
  <artifactId>rcparties-api</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <scope>provided</scope>
</dependency>
```

Declare the dependency in your `paper-plugin.yml` so load order is right. `RCParties` is
the plugin name, and the API jar is irrelevant here:

```yaml
dependencies:
  server:
    RCParties:
      load: BEFORE
      required: false     # true if your plugin is useless without parties
```

Resolve the service Vault-style, with no hard plugin dependency beyond the API artifact:

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

`config.yml` holds behaviour only:

```yaml
max-party-size: 8
invite-ttl-seconds: 60
allow-member-invites: true
```

## Messages

All player-facing wording lives in RCUI's catalog. Operators edit:

```
plugins/RCUI/messages/rcparties.yml
```

`rcparties-plugin/src/main/resources/messages.yml` is the bundled default and the annotated
reference for every key — RCUI rewrites the operator file and drops comments, so keep the
packaged copy as documentation. `/party admin reload` re-reads both `config.yml` and the
catalog, so edits apply without a restart.

Three things that will catch you out:

- **The bundled file is a flat tree at the root.** It is deliberately *not* the
  `schema-version:` / `messages:` shape RCUI writes for operators. Ship that shape and RCUI
  rejects the catalog and disables RCParties at boot.
- **RCUI has no fallback.** A key referenced by code but missing from the catalog throws
  `MissingMessageException` when a player triggers it. `MessageCatalogTest` fails the build
  rather than the server — it mirrors RCUI's own validation rules and cross-checks the key
  list against the send sites in both directions.
- **The prefix is seeded exactly once.** Once a server has started RCParties, changing the
  bundled `prefix` will *not* update it; edit `plugins/RCUI/messages/rcparties.yml`
  directly, or clear `defaults.message-prefixes.rcparties` in `plugins/RCUI/migrations.yml`
  *and* blank the prefix so RCUI re-seeds.

If you are upgrading from a version that kept wording in `config.yml`, that block is no
longer read. RCParties warns on boot while it is still present rather than deleting it —
re-apply the wording in RCUI's catalog, then remove the block.

## Non-goals

No persistence, no database, no cross-restart parties. No game logic, scoring,
teleporting, or economy — consumers do all of that. No cross-server parties; a
Velocity-aware version would be a separate spec.
