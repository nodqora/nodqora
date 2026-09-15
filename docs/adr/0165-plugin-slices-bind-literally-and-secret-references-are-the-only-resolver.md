# ADR-0165: Plugin slices bind literally, and secret references are the only resolver in them

- **Status**: Accepted
- **Date**: 2026-09-15
- **Ticket**: [Spring expands ${file:} and ${env:} references before SecretReferences sees them](https://github.com/nodqora/nodqora/issues/111)
- **Amends**: [ADR-0014](0014-file-declared-plugin-config.md) — how the file is bound, not what it holds.

## Context

ADR-0014 decided that a secret is written as `${env:VAR}` or `${file:/path}` and resolved at use
time by the core. It did not say how the file reaches the core, and the implementation used
`@ConfigurationProperties("nodqora")`.

That binder resolves Spring placeholders, and Spring's grammar is `${name:default}`. It reads
`${file:/etc/nodqora/kubeconfig}` as *property `file`, default `/etc/nodqora/kubeconfig`*, and
`${env:KAFKA_PASSWORD}` as the text `KAFKA_PASSWORD`. `SecretReferences` was never handed a
reference. The kubeconfig arrived as its own path and failed every namespace. The password arrived
as the variable's name and would have failed only at authentication. The unit tests passed because
they built the slice as a `Map` and never went through a file.

While this was open the docs prescribed Spring's escape, `'\${file:...}'`, and installs carry it.

## Decision

**`nodqora.environments` is bound with no placeholder resolver.** `NodqoraProperties.from` binds it
with a `Binder` over the environment's `ConfigurationPropertySources` alone, so every value reaches
`BoundConfiguration` exactly as written, and `SecretReferences` is the only thing that interprets
`${...}` inside it. `nodqora.plugins` and `nodqora.refresh` keep Spring's resolution. They hold
plugin ids and durations, never secrets.

**The escaped form is a reference too.** `\${env:...}` and `\${file:...}` resolve exactly as the
unescaped forms do, permanently, so an install written against the interim docs keeps working
across the upgrade. The docs go back to the unescaped form ADR-0014 decided.

**Rejected: a Spring placeholder resolver that skips `env:` and `file:`.** It would keep Spring
resolution for `displayName` and every other value in an environment, but it means two resolvers
reading one string and agreeing on who owns which prefix. That agreement is exactly what broke
here, and a third scheme (Vault, per ADR-0014) would have to be taught to both.

## Consequences

- A Spring placeholder anywhere under an environment, including `displayName`, is now literal text.
  Nothing in an environment is shaped by the process environment except through a reference, which
  is ADR-0152's position on environment variables carried over to placeholders.
- A single backslash immediately before a reference is read as the escape and dropped, so
  `DOMAIN\${env:USER}` resolves to `DOMAINuser`. Through a real file that was already true before
  this change, because Spring consumed the same backslash; write `DOMAIN\\${env:USER}` for a literal
  one.
- `NodqoraProperties` is no longer `@ConfigurationProperties`, so it gets no Boot metadata. Nothing
  consumed any.
- A value that genuinely needs a literal `${env:` in a plugin slice cannot be written. No plugin
  config has one, and it is the same limitation ADR-0014's syntax always had.
- The regression is covered where it lived: `SecretReferencesInAConfigFileTest` loads a real
  `application.yaml` through Spring Boot.
