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

## Consequences

- A Spring placeholder anywhere under an environment, including `displayName`, is now literal text.
  ADR-0152 already rules out environment variables as a way to shape an environment, and one
  resolver per tree is the thing that stops this recurring.
- `NodqoraProperties` is no longer `@ConfigurationProperties`, so it gets no Boot metadata. Nothing
  consumed any.
- A value that genuinely needs a literal `${env:` in a plugin slice cannot be written. No plugin
  config has one, and it is the same limitation ADR-0014's syntax always had.
- The regression is covered where it lived: `SecretReferencesInAConfigFileTest` loads a real
  `application.yaml` through Spring Boot.
