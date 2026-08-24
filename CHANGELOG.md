# Changelog

All notable changes to the Praxsuite SDK for Java.

## [1.0.0] - 2026-08-24

First release. Zero dependencies, Java 17+.

Java 17 rather than 21 on purpose: Paper 1.20.x runs on 17 and 1.21 on 21, so 17 reaches both - and
Minecraft plugin developers are the audience that asked for a Java SDK rather than using the .NET or
Kotlin one. Compiled with `--release 17` rather than a Gradle toolchain, so a Java 21-only API is a
compile error here instead of a NoSuchMethodError on someone's 1.20 server.

### Added

- **`Praxsuite.builder()`** - thread-safe, one pooled `HttpClient` per instance. Every value falls
  back to an environment variable, which is what a deployed service wants.
- **Auth** - register, login, logout, refresh, password reset, resend confirmation, and the
  unauthenticated `auth/config` read. A refresh is serialised behind a lock, because the gateway
  retires the old refresh token as it issues the new one and two concurrent refreshes would leave
  the loser holding a retired token. Profile fields carry forward across a refresh, so a signed-in
  user never briefly reads as anonymous.
- **Query builder** - select, where, order, limit, offset, group/having, aggregates, related-table
  includes, plus `fetch` / `first` / `count` / `exists` / `all`. `all()` reads back the limit the
  server actually applied, so a scope clamp cannot turn pagination into an infinite loop.
- **Writes** - insert, insertMany, update, updateById, delete, deleteById, upsert. `update` and
  `delete` take conditions positionally and require at least one, so an unscoped write cannot be
  written by accident. Native columns the backend maintains are refused.
- **Endpoints** - POST only, unenveloped, never auto-retried, 100-second default timeout. All three
  of those are measured behaviours rather than choices; see the README.
- **Schema** - cached table and column introspection, which is the quickest way to tell a typo apart
  from a missing scope.
- **Errors as exceptions**, all extending `PraxError` so one catch covers the SDK.
  `PraxRateLimitError` and `PraxQuotaExceededError` are distinct types because both arrive as HTTP
  429 and only one is worth retrying.
- **Credential guard** - `clientSide(true)` refuses an `sk_live_` key, for an Android build, a
  desktop app, or a plugin handed to server owners who are not you.
- **Log scrubbing** on the `com.tesseractsoftwares.praxsuite` JUL logger: keys, JWTs and secret
  fields removed from every message.
- **A bundled JSON codec.** Java has no JSON in its standard library, and a Paper plugin runs inside
  a server classloader that already holds its own Gson - two version-skewed copies of a shaded
  library is a classic way to break a server you did not ship with. 16 of the 50 tests cover the
  codec alone, because every response passes through it.
- 50 offline tests. No workspace, no credentials, no network.

### Notes for anyone coming from another Praxsuite SDK

- A whole JSON number parses to `Long`, a fractional one to `Double`. Unlike Godot, an Int column
  does not arrive as a float and needs no cast.
- `Page.total()` is `null` when a count was not requested, so "no rows matched" stays
  distinguishable from "nobody asked".
- There is no `endpoints().get()`. GET never reaches the automation - the gateway consumes it as a
  Meta webhook verification handshake. Two sibling SDKs had theirs removed for the same reason.

### Not included

Publishing to Maven Central. Unlike npm, PyPI and NuGet, Maven Central requires GPG-signed
artifacts, so it needs a Sonatype Central account and a signing key before the pipeline can push.
