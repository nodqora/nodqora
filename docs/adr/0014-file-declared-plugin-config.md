# ADR-0014: Plugin configuration is file-declared; secrets are references only

- **Status**: Accepted
- **Date**: 2026-09-01
- **Ticket**: [Plugin/adapter contract for the MVP](https://github.com/fredskor/nodqora/issues/6)

## Context

ADR-0004 makes Environment an entity "holding its own discovery connection
configuration" — production's `kafka-prod.internal:9092` and `payments-prod`
namespace against staging's equivalents — because the environment switcher is
populated from it. It does not say that configuration is stored in the database.

Three constraints bear on where it lives: the MVP ships **without auth** (the
destination); §39 requires credential **references** rather than embedded
secrets; and research #5 found that Connect has no read-only credential and that
`GET /connectors/{name}/config` returns **inlined secrets unmasked**.

## Decision

Per-environment plugin configuration is **declared in a file and bound at
startup**. The database holds only the environment roster.

```yaml
environments:
  production:
    displayName: Production
    plugins:
      kubernetes: { namespace: payments-prod, kubeconfig: "${file:/etc/nodqora/kubeconfig}" }
      kafka:      { bootstrap: kafka-prod.internal:9092, saslPassword: "${env:KAFKA_PROD_PASSWORD}" }
      connect:    { url: https://connect-prod.internal:8083 }
```

A plugin declares `Class<C> configType()`; the core binds its slice of the file
into it with Bean Validation, so bad configuration fails at **startup** rather
than at first poll. No JSON Schema, no config-schema language (§36 defers it).

`Environment{key, displayName}` stays in the database so nodes can key off it
and the switcher has its authoritative roster. **The API returns key and display
name only** — never connection configuration, never secrets (§40).

Secret references resolve as `${env:VAR}` and `${file:/path}` only, at use time,
never persisted. Vault, AWS Secrets Manager and Key Vault slot in behind the
same syntax later.

**Plugin output must be secret-free, and config-derived metadata is
allow-listed, not deny-listed.** The `connect` plugin copies `connector.class`,
`topics`, `tasks.max` and friends **by name** — never everything-except-
`*.password`.

## Consequences

- A write API for integration configuration on an unauthenticated service is a
  credential-entry form open to anyone who reaches the ingress. File config
  sidesteps it rather than mitigating it.
- Credential references resolve against the process environment and mounted
  files — file config's native habitat, and how §41 actually deploys this
  (ConfigMap plus mounted Secret).
- **#13's "integration-config endpoints" narrow to a read-only environment
  roster.**
- `metadata` is opaque to the core (ADR-0006) and returned wholesale by the API,
  so a plugin that dumped a Connect config response into it would publish
  credentials to every viewer. The allow-list is the only defence that fails
  safe when a new secret-bearing config key appears.
- Reconfiguring an environment is a restart, not an API call. Acceptable for the
  MVP; it is the cost of having no auth.
