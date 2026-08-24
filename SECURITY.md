# Security Policy

## Reporting a vulnerability

**Do not open a public issue for a security problem.**

Email **security@tesseractsoftwares.com** with:

- what the issue is, and what an attacker gains
- steps to reproduce, or a proof of concept
- the SDK version and JVM version
- whether it affects the SDK, the Praxsuite gateway, or both

We aim to acknowledge within **2 business days** and to give you an assessment and a fix timeline
within **10 business days**.

If you would like credit in the release notes, say so and tell us how to name you. If you would
rather stay anonymous, that is fine too.

We will not take legal action against good-faith research that follows this policy: report
privately, do not access or modify data belonging to anyone else, and give us a reasonable window
to fix the issue before disclosing it.

## Supported versions

| Version | Supported |
|---|---|
| 1.x | yes |
| < 1.0 | no |

## What counts as a vulnerability here

This SDK usually runs on a server you control, so a secret key is the *correct* credential and the
interesting failures are about that key escaping. But it also ships inside Minecraft plugins handed
to server owners who are not the plugin author, which changes the threat model - so both cases are
in scope.

**In scope:**

- The SDK sending a credential somewhere it should not, or logging one unredacted - including via
  an exception message, a `toString()`, or a stack trace
- `clientSide(true)` accepting an `sk_live_` key
- A way to make the SDK talk to a host other than the configured `baseUrl`
- An endpoint id or table name escaping its URL path segment
- Anything letting one signed-in user's session reach another user's data
- A dependency appearing in the published POM - the artifact declares none, and a transitive
  dependency would be a supply-chain surface this SDK exists to avoid
- A deserialisation issue in the bundled JSON codec: it parses untrusted gateway responses, and it
  is ours rather than a hardened third-party library, so it deserves scrutiny

**Not vulnerabilities - these are documented properties, not defects:**

- *A secret key is readable by anyone who can read the process environment, the jar, or a heap
  dump.* True of every server credential. Scope it narrowly and rotate it.
- *A publishable key can be extracted from anything shipped to a user.* It is designed to be
  public, like a Stripe publishable key.
- *A gateway endpoint runs without a credential.* Measured and documented. Endpoints are webhook
  receivers; a third party firing one cannot hold your credential. Authority comes from the
  endpoint verifying its caller, which is opt-in per endpoint.
- *A modified client can send arbitrary requests.* Assumed. The gateway is the boundary.

If you are unsure which side of that line something falls on, report it. We would rather triage a
non-issue than miss a real one.

## For developers using this SDK

Most incidents involving a backend SDK are configuration, not code. Before shipping:

- The credential comes from the environment or a secret manager, never from source, and never from a
  `config.yml` you ship inside a plugin jar.
- **If you distribute a plugin, do not embed your own credential in it.** Each server owner needs
  their own workspace and their own key. A key inside a jar you hand out is a key every one of those
  server owners holds.
- Pass `clientSide(true)` anywhere a user can read the code: an Android build, a desktop app, a
  client-side mod.
- The credential is scoped to the minimum tables the code needs. Auth routes skip table-scope
  checks, so sign-in works on a credential that can reach nothing.
- Per-user tables carry an ownership column with a `__SELF__` row filter on the **table** scope
  **and** a `{{claim:sub}}` default value template on the **column** scope - both, not one.
- Anything a caller must not influence goes through a gateway endpoint **with a signature secret or
  a token claim check**, not a bare endpoint.
- The `com.tesseractsoftwares.praxsuite` logger is left at its default level in production. `FINE`
  logs request and response bodies.

The reasoning behind the two isolation settings, including the silent failure when only the row
filter is configured, is in the [README](README.md#per-user-isolation-needs-two-settings).
