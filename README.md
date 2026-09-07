# Praxsuite SDK for Java

Auth, queries and server-authoritative endpoints for your Praxsuite workspace.

**Zero dependencies.** Java 17+, standard library only — `java.net.http.HttpClient` plus a bundled
JSON codec. That matters most where this SDK is most useful: a Paper/Spigot plugin runs inside a
server classloader that already holds its own Gson, and two version-skewed copies of a shaded
library is a classic way for a plugin to break a server it didn't ship with.

```java
Praxsuite prax = Praxsuite.builder()
    .workspaceId("your-workspace-id")
    .credential("sk_live_...")
    .build();

Responses.Page page = prax.data().table("Orders")
    .select("ID", "Total", "Status")
    .where(Filters.gte("Total", 100), Filters.eq("Status", "paid"))
    .orderByDescending("Total")
    .limit(50)
    .fetch();

for (Map<String, Object> row : page.rows()) {
    System.out.println(row.get("ID") + " " + row.get("Total"));
}
```

---

## Install

**Gradle**

```kotlin
dependencies {
    implementation("com.tesseractsoftwares:praxsuite-sdk:1.0.0")
}
```

**Maven**

```xml
<dependency>
  <groupId>com.tesseractsoftwares</groupId>
  <artifactId>praxsuite-sdk</artifactId>
  <version>1.0.0</version>
</dependency>
```

Java **17 or newer**. Compiled with `--release 17`, so it runs on Paper 1.20.x (Java 17) and 1.21
(Java 21) alike.

### Kotlin

The Java SDK works from Kotlin as-is. If you'd rather have coroutines and a query DSL, add the
extensions artifact — it depends on this one, so you get both:

```kotlin
dependencies {
    implementation("com.tesseractsoftwares:praxsuite-sdk-kotlin:1.0.0")
}
```

```kotlin
val page = prax.data.table("Orders")
    .select("ID", "Total")
    .where { gte("Total", 100); eq("Status", "paid") }
    .orderByDescending("Total")
    .fetchAsync()
```

Every terminal has a `suspend` form, and `where { }` builds the same request the static `Filters`
calls do — there's a test asserting the two produce an identical body, because a DSL that quietly
sends something else is worse than no DSL.

One honest caveat: the underlying calls are blocking, so the `suspend` functions run on
`Dispatchers.IO`. Your coroutine suspends instead of parking on a blocked thread, which is the point
in a Ktor handler — but it isn't native async I/O, and calling it that would be a lie.

**Zero dependencies applies to the Java artifact only.** The Kotlin one necessarily pulls in
`kotlin-stdlib` and `kotlinx-coroutines-core`. That's the trade: plugin authors who can't afford a
classloader argument take the Java artifact, and Kotlin users who already ship a stdlib get the
nicer face.

## Configure

```java
Praxsuite prax = Praxsuite.builder()
    .workspaceId("your-workspace-id")
    .credential("sk_live_...")
    .build();
```

Or from the environment, which is what a deployed service wants:

```bash
export PRAXSUITE_WORKSPACE_ID=...
export PRAXSUITE_API_KEY=sk_live_...
export PRAXSUITE_BASE_URL=https://gateway.praxsuite.com   # optional
```

```java
Praxsuite prax = Praxsuite.builder().build();
```

Both values come from your workspace under **API Gateway**. The client is cheap to construct and
safe to share between threads — one instance per workspace per process. In a Minecraft plugin, one
field on your `JavaPlugin`.

### Which key to use

**A JVM process is usually a server you control, so a secret key is normally correct** — that's
what one is for.

The exception is anything a user can read: an Android build, a desktop app, a client-side mod, or
**a plugin you hand to server owners who aren't you**. Set `clientSide(true)` and the SDK refuses a
secret key outright rather than trusting you to remember:

```java
Praxsuite.builder().workspaceId(ws).credential("pk_live_...").clientSide(true).build();
```

Two things to know regardless:

**Every credential carries both halves.** There's no publishable-only credential, and
`/{workspace}/auth/config` is unauthenticated — so the workspace id alone yields the publishable
key, and whatever tables you scope to that credential are reachable by anyone who has it. Scope
narrowly.

**An endpoint does not authenticate its caller for you** — see below. That one surprises people.

---

## Querying

```java
Responses.Page page = prax.data().table("Scores")
    .select("Player", "Score")
    .where(Filters.gte("Score", 100))
    .whereEquals("Season", 3)
    .orderByDescending("Score")
    .limit(20)
    .withTotalCount()
    .fetch();

page.size();     // rows in this page
page.total();    // null unless you asked for it
page.limit();    // the limit the SERVER applied, which may be lower than yours
```

Nothing is sent until a terminal method — `fetch()`, `first()`, `count()`, `exists()`, `all()`.

To page through everything:

```java
List<Map<String, Object>> rows = prax.data().table("Scores")
    .whereEquals("Season", 3)
    .all(200, null);
```

`all()` reads back the limit the server actually applied rather than assuming yours was honoured —
a table scope can clamp it, and assuming otherwise turns pagination into an infinite loop.

`Filters` exposes **only** operators the gateway implements: `eq neq gt gte lt lte like ilike in is
between contains textsearch`. The friendly-sounding ones compile down — `startsWith` becomes
`like "value%"`, `isNull` becomes `is null`. Offering `startsWith` as an operator would only produce
a 400 at runtime.

**`page.total()` is `null` when you didn't request a count**, not `0`, so "no rows matched" stays
distinguishable from "nobody asked".

### Writes

```java
prax.data().insert("Saves", Map.of("Slot", 1, "Level", 12));
prax.data().updateById("Saves", rowId, Map.of("Level", 13));
prax.data().update("Saves", Map.of("Level", 13), Filters.eq("Slot", 1));
prax.data().delete("Saves", Filters.eq("Slot", 1));
```

`update()` and `delete()` take their conditions **positionally and require at least one**, so an
unscoped write can't be written by accident. Don't set an ownership column yourself — see
[isolation](#per-user-isolation-needs-two-settings).

## Errors

Everything throws `PraxError` or a subclass, so one `catch` covers the SDK:

```java
try {
    prax.data().table("Scores").fetch();
} catch (PraxRateLimitError e) {
    // transient — back off and retry
} catch (PraxQuotaExceededError e) {
    // NOT transient — the workspace owner has to upgrade
} catch (PraxError e) {
    log.warning(e.code() + " " + e.status() + " " + e.details());
}
```

Both of those arrive as HTTP 429 and mean opposite things, which is exactly why they're separate
types. `PraxError` is a `RuntimeException`, so nothing forces a `throws` clause on your API.

Reads are retried automatically with backoff on transient failures. Writes and endpoint calls are
not: retrying a failed insert is how you get two rows.

## Automations (endpoints)

```java
Map<String, Object> result = prax.endpoints().call(endpointId, Map.of("score", score));
```

Two things measured against a live gateway, both of which change how you should use this:

**An endpoint does not authenticate its caller.** A POST with no credential at all returns 200 and
runs the automation. That follows from what an endpoint is — a webhook receiver, and Stripe or Meta
can't hold your workspace credential — but it means putting logic here makes it
server-**executed**, not automatically server-**authoritative**. The authority comes from the
endpoint verifying who called: a signature secret configured on the endpoint, or the automation
checking a verified claim from the session token this SDK attaches. Design accordingly, especially
in a plugin where the server owner isn't you.

**GET doesn't work.** `GET /{workspace}/endpoint/{id}` never reaches the automation — the gateway
consumes it as a Meta webhook verification handshake and answers 400. There's deliberately no GET
helper here.

The response comes back **exactly as the automation returned it**. Endpoint responses are not
platform-enveloped, so nothing is unwrapped.

The default timeout is 100 seconds, because a Sync endpoint holds the connection while its
automation runs — `syncTimeoutSeconds` values of 30, 45, 60 and 90 all exist in practice.

## Logging

`java.util.logging`, under `com.tesseractsoftwares.praxsuite`, with credentials scrubbed from every
message — keys, JWTs and password fields.

```java
Logger.getLogger("com.tesseractsoftwares.praxsuite").setLevel(Level.FINE);
```

JUL rather than SLF4J so the SDK stays dependency-free. If you use SLF4J or Log4j, bridge JUL in one
line — better than us forcing a binding on you.

---

## Per-user isolation needs TWO settings

The most damaging misconfiguration in the platform, so it's worth stating plainly.

| Setting | Where | Value | Covers |
|---|---|---|---|
| Row filter | the role's **table** scope | `__SELF__` | select, update, delete |
| Default value template | the ownership **column**'s scope | `{{claim:sub}}` | insert |

The row filter can't cover inserts, because an insert has no WHERE clause to constrain. Configure
only the row filter and **inserts succeed with a null owner, which the filter then hides** — the
user saves a record and can't read it back, with no error raised anywhere.

The default value template also blocks the client from setting the column at all, which is what
makes ownership untamperable. That rejection is the guarantee; don't work around it.

---

## Conformance is the law

Praxsuite has SDKs in several languages. Where they touch the gateway they do **not** get to
disagree. A single normative contract defines the shared behaviour, and every SDK implements it
identically:

1. **The contract is normative.** Where this SDK and the contract differ, this SDK is wrong.
2. **Every rule cites the backend source it derives from.** No rule rests on memory.
3. **Every rule exists because getting it wrong fails silently.** Wrong data, not an error.
4. **A behaviour change is a contract change first.** Not an implementation detail.

The contract is internal and deliberately has no public repository. Its value is that it's
authoritative for us, not that it's browsable — and it cites backend internals that aren't ours to
publish. Everything a consumer needs is in this README.

What it pins down, and why each earned its place:

- **Operators.** Only the thirteen the parser accepts. A friendlier name is a runtime 400.
- **`meta.total`, never `meta.totalCount`.** Reading the wrong name returns nothing and reports
  zero, silently, forever. One SDK shipped that for months.
- **Three response envelopes.** `/query` is bare, `/auth/*` nests under `.data`, `/files` and
  `/endpoint` errors are a bare string. Assuming one shape mis-parses the others.
- **`limit` is clamped up to a minimum of 1.** A zero-row count request quietly returns a row.
- **Unscoped updates and deletes refused before sending.**
- **Endpoints are POST-only, unenveloped, and never auto-retried.**
- **No client-supplied identity parameter.** The server ignores it, so it would read as a security
  boundary while being decorative.

The suite is offline — no workspace, no network, no credentials:

```bash
./gradlew test
```

62 checks: 16 for the bundled JSON codec, 34 for the contract, and 12 asserting the Kotlin
extensions send exactly what the Java calls do.

## The Event Bus

Ephemeral realtime between connected clients: player positions, cursors, "is typing", a lobby.
State that is *changing*, where losing a message is fine because a newer one is 100ms behind it.

```java
prax.auth().login(email, password);   // the bus needs a signed-in user, not the credential

PraxChannel room = prax.bus().topic("office").channel("hq");   // the bus "office:hq"

room.on("move", e -> moveAvatar(e.fromUserId(), e.payload()));
room.onPeerLeft(this::removeAvatar);

// join() returns everyone already there, so a late arrival sees the room rather than an
// empty one until somebody happens to move.
for (var peer : room.join()) {
    moveAvatar(peer.userId(), peer.payload());
}

room.publish("move", Map.of("x", x, "y", y));
```

**A topic must exist before anyone can join it.** Declare it once in the portal under
API Gateway / Event Bus and pick its access rule: open to any signed-in user, gated on a role
from their token, or gated on a grant on that one bus instance. An undeclared topic is refused -
which is what stops another application's client squatting in your namespace.

`prax.bus().self()` is the caller's own bus, `user:self`. The server resolves it to their id, so
it can never address anybody else.

Three things about it are not obvious and will bite:

- **Nothing is persisted.** No history, no retry, no delivery to somebody who was not connected.
  The test is one question: *if this is lost, does it matter?* Yes - a purchase, a score, an
  inventory grant - means a table via `prax.data()`, or an automation, and a server-authoritative
  one at that. No, because a newer one is coming, means the bus.
- **Payloads are hostile.** The bus relays opaque JSON between *users* and parses none of it, so
  every server-side check is bypassed. A position is a hint, never an authority.
- **You never receive your own event.** Apply your own change locally.

`publish` does not throw when the bus refuses a frame - a tick loop that throws on a rate limit
is worse than one that skips a frame. Read the result when you care:

```java
var r = room.publish("move", Map.of("x", x, "y", y));
if (!r.ok()) log(r.error());          // e.g. "rate_limited"
if (r.recipients() == 0) { }          // it went out, and nobody was joined
```

`join` is the opposite and throws: a publish that does not land is one lost frame, a join that
does not land leaves this client silently absent for the whole session.

Handlers run on the WebSocket's own thread. In a Paper or Spigot plugin, hand anything that
touches the world to `Bukkit.getScheduler().runTask(...)` from inside the handler rather than
doing it there.

Reconnects are handled: the socket comes back with backoff and every channel you still want is
re-joined, because SignalR group membership does not survive a reconnect - a client that only
reconnects is connected, in no groups, and looks for all the world like a broken server.

It runs on `java.net.http.WebSocket` from the JDK, so it adds no dependency. That matters most
here: `com.microsoft.signalr` would drag in RxJava and OkHttp, and a shaded, version-skewed copy
of either inside a server classloader is one of the classic ways a plugin breaks a server it did
not ship with.

---

## Signing in with an external provider

```java
for (var provider : prax.auth().providers()) {
    addButton(provider.slug(), provider.displayName());
}

var start = prax.auth().startOidcLogin("tesseract");
openInBrowser(start.authorizationUrl());        // keep start.state()

// ...once the provider has redirected back with code and state:
prax.auth().completeOidcLogin(
    "tesseract", code, state,
    "https://app.example/callback");   // byte-identical to the configured redirect URI
```

All four arguments are required, and three of them are why an external sign-in fails when it
fails: the gateway scopes its one-time `state` per provider, consumes it once, and compares the
redirect URI against the value configured for that provider. Pass the URI you were actually
redirected to rather than rebuilding it - that is how it ends up differing by a trailing slash
and failing with a message about redirect URIs that nobody can act on.

The session lands in the same place a password login puts it, so refresh, sign-out and every
authenticated call behave identically afterwards.

Only the authorization-code flow exists. There is no route that accepts a provider's own
`id_token`, so even a native button has to make the browser hop.

---

## API surface

| | |
|---|---|
| `Praxsuite.builder()` | `workspaceId` `credential` `baseUrl` `clientSide` `timeout` `maxAttempts` |
| `prax.auth()` | `register` `login` `logout` `refresh` `ensureFreshSession` `forgotPassword` `verifyResetCode` `resetPassword` `resendConfirmation` `config` `onSessionChange` |
| `prax.data()` | `table(name)` → query builder; `insert` `insertMany` `update` `updateById` `delete` `deleteById` `upsert` `execute` |
| `prax.endpoints()` | `call` |
| `prax.schema()` | `tables` `table` `columns` `hasTable` |
| `prax.bus()` | `topic(key).channel(instance)`, `channel(busKey)`, `self()`, `connect()`, `close()`; a channel has `join` `publish` `leave` `peers` `on` `onAny` `onPeerJoined` `onPeerLeft` `onEvicted` |
| `Filters` | `eq neq gt gte lt lte like ilike contains textSearch startsWith endsWith isNull isNotNull in between anyOf allOf` |

## Licence

[Praxsuite Open SDK Licence](LICENSE) — source-available, not OSI open source.

Free to use in anything you build, including products you sell. Free to fork, modify and publish
your changes. Not free to resell as an SDK, or to point at a competing backend.

Derived from the Praxsuite SDK — <https://praxsuite.com>
