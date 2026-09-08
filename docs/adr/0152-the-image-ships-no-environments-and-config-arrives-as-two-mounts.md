# ADR-0152: The image ships no environments, and an operator's configuration arrives as two mounts

- **Status**: Accepted
- **Date**: 2026-09-08
- **Ticket**: [Shipped configuration, and how a user supplies their own](https://github.com/fredskor/nodqora/issues/63)

## Context

[ADR-0150](0150-the-community-distribution-is-one-public-image-and-a-compose-file.md)
made the Community distribution one public image and recorded the consequence it
could not settle: *"a container has no repo root, so whatever
`nodqora.environments.*.plugins.yaml.dir` points at has to arrive from outside
the image."*

The state it points at is sharper than that, and worse. **The shipped
`nodqora-app/src/main/resources/application.yaml` is the demo.** It declares
`production` and `staging` against `payments-prod`, templates every link against
`acme.io`, points `connect` and `kafka` at `*.internal` hosts that do not
resolve, and reads its topology from `fixtures/reference-pipeline/…` — a
repo-relative path that works only because `bootRun` and the `test` task both pin
`workingDir` to the repo root.

It is also, and this was not previously written down, **the test suite's
binding**. `nodqora-app/src/test/resources/` contains only `static`; there is no
test `application.yaml`. `ColdStoreTest`, `IncidentScenarioTest`,
`BlindObserverTest`, `FrontendRoutingTest` and the golden-document tests all bind
`production` and `staging` from the shipped file. So the demo config is not
decoration to be deleted — it is load-bearing twice.

[ADR-0014](0014-file-declared-plugin-config.md) already fixed the shape of the
answer: per-environment plugin configuration is **declared in a file and bound at
startup**, the database holds only the roster, and secrets are `${env:}` and
`${file:}` references resolved at use time. What was never decided is which file,
arriving how, and what the image carries in the meantime.

## Decision

### 1. The image ships no `nodqora.environments` at all

`application.yaml` keeps everything else — the two plugin orders
([ADR-0011](0011-yaml-is-a-plugin.md),
[ADR-0044](0044-scalar-precedence-and-collection-identity.md)), the refresh
cadences, the Jackson, problemdetails and
[ADR-0151](0151-the-deployable-serves-the-frontend-and-the-forward-names-the-grammar.md)
cache settings — and declares no environment.

**The reason is a merge semantic, not a matter of taste.** Spring binds
`Map<String, Environment>` by merging every property source. A mounted file can
*override* `nodqora.environments.production.plugins.yaml.dir` and can *add*
`nodqora.environments.mine.*`. It cannot **delete** the `production` key. A
baked-in environment would therefore survive into
[ADR-0055](0055-descriptors-ride-with-the-graph.md)'s roster and
[ADR-0085](0085-plugins-is-a-config-roster.md)'s `plugins[]` on every install
forever, and [ADR-0093](0093-root-redirects-and-an-unknown-environment-names-the-roster.md)
would send bare `/` to somebody else's fiction rather than to the operator's own
first environment. **A default a user cannot decline is not a default.**

Shipping one empty `default` environment — which would keep ADR-0093 working —
was rejected for the same reason plus a second: an environment with no plugins
renders `plugins[]` as an empty array, a fourth canvas state nobody designed.

### 2. The demo becomes repo-only, in one file

The environments block moves to
**`fixtures/reference-pipeline/application-demo.yaml`**, beside the topology it
names, loaded by both `bootRun` and the `test` task through
`spring.config.additional-location`. One copy, two consumers, shipped nowhere.

A demo *in* the image was rejected on its own merits, not only on scope. With
only `yaml` configured it would not show the reference pipeline:
[ADR-0063](0063-declare-the-gaps-not-the-graph.md) makes the fixture's stanzas
one and two lines deliberately, because `kubernetes` supplies the type and
`kafka` the partitions, and `YamlTopologyPlugin` implements `DiscoveryCapability`
alone. The result is untyped nodes on
[ADR-0091](0091-kubernetes-emits-no-type-default.md)'s fallback descriptor with
no health at all — a worse first screen than an honest empty one. Making it good
means baking recorded fixtures and a replay client per plugin: shipped code whose
only consumer is a demo, on the release where
[ADR-0147](0147-only-the-latest-release-is-supported.md) makes everything shipped
support-bearing.

### 3. Configuration arrives as a mounted file at Spring's own default location

**`/app/config/application.yaml`.** Boot's default search path already includes
`optional:file:./config/` and the runtime stage's `WORKDIR` is `/app`, so the
file is discovered with **no flag, no `SPRING_CONFIG_ADDITIONAL_LOCATION`, and no
line of Dockerfile**. It *merges* with the jar's, so the file an operator writes
carries `nodqora.environments` and nothing else — a user restating ADR-0011's two
plugin orders would be a disaster the first time a plugin is added.

**Environment variables keep exactly two jobs**: the `NODQORA_DB_URL` / `_USER` /
`_PASSWORD` triple ADR-0150 already names, and ADR-0014's `${env:}` / `${file:}`
secret references, which keep credentials out of the mounted file entirely.

Full env-var configurability is **disqualified, not merely disliked**:

- `KafkaConfig.properties` is `Map<String, String>` of dotted client keys —
  `security.protocol`, `sasl.mechanism`. Boot's environment source maps `_` to
  `.`, so `…_PROPERTIES_SECURITY_PROTOCOL` binds as nested
  `properties.security.protocol`, not the flat dotted key Kafka needs. **The
  TLS/SASL path is not expressible as a variable.**
- Lists inside a plugin slice survive only through `ConfigLists`' index-key
  heuristic, whose own javadoc calls it *"a workaround for something the binder
  does rather than a decision about the product"*. Making it the public
  configuration interface would promote it to a contract.
- [ADR-0052](0052-environment-scoped-api-addressed-by-node-key.md) matches the
  environment key **exactly**, and variable names lowercase it. An operator who
  wants `EU-Prod` cannot spell it.

### 4. The topology is a second, optional, read-only mount

**`/etc/nodqora/topology/<environment>`** — the path
[ADR-0061](0061-yaml-is-a-directory-per-environment.md) already wrote in its own
example, now literally true rather than illustrative.

It is *not* under `/app/config`, because Spring searches `config/` **and one
level of `config/*/`** for `application.{yaml,properties}`: a topology file named
`application.yaml` would be read as configuration by Spring *and* as topology by
the plugin, and [ADR-0064](0064-the-environment-is-the-unit-of-failure.md) makes
an unknown top-level key the whole environment's failure. The two trees also have
different lifecycles — a config file an operator edits, against an authored,
versioned artifact ADR-0064 treats as a unit and which is likely rendered from
another repository.

**The mount is optional.** `yaml` is a plugin like any other and
`Environment.plugins` is a map; an operator observing only `kubernetes` and
`kafka` never declares it and never mounts a directory.

### 5. With nothing mounted, the process starts

`/api/meta` serves an empty roster. This is already what `BoundConfiguration`
does — an empty `environments` map is a no-op loop — and the decision is to keep
it rather than add a fail-fast guard.

ADR-0014's fail-at-startup rule governs configuration that is **wrong**, and
absent configuration is not wrong; bad connection details still kill the process
through Bean Validation the moment a slice is declared. `YamlTopologyConfig`
already reasons identically one level down: directory existence is deliberately
not a startup constraint, *"a mount that arrives late should not stop the process
from serving the rest of the graph."* A typo in a compose volume path becoming a
crash-loop — diagnosable only by `docker logs` — is a worse first five minutes
than an empty roster, and Flyway migrating anyway means the fix is an edit and a
restart.

### 6. Nothing extra ships to bootstrap it

No `application.example.yaml` release asset. It would reopen ADR-0150's
two-artifact decision and add a third immutable thing per release under
[ADR-0143](0143-published-versions-are-immutable-and-the-inner-loop-has-no-artifact.md),
for content the install documentation must state anyway. The repo now holds a
better example than one written for docs: `application-demo.yaml` is a complete
four-plugin config that **the test suite binds on every build**, so it cannot rot
without CI going red.

## Consequences

- **The first screen of a fresh install is now an open question, and a sharper
  one.** It is no longer "four honesty banners over someone else's demo" but "no
  environment at all", which ADR-0093 has no branch for — it redirects bare `/`
  to `environments[0].key` — and which none of
  [ADR-0087](0087-the-canvas-has-three-empty-states.md)'s three empty states
  covers, all of them presupposing an environment. That is the first-run ticket's
  question and this ADR deliberately stops at the server contract.
- **`spring.config.additional-location` is now load-bearing in the build.** The
  `test` task and `bootRun` set it; a developer overriding it on the command line
  *replaces* the demo rather than adding to it, because an argument outranks a
  system property. `docs/running-against-your-own-cluster.md` says so.
- **The demo is now honestly labelled as a fixture.** It was always the test
  suite's binding; it is now in a directory that says so, next to the golden
  inputs it belongs with, and covered by ADR-0099's one-copy rule in spirit as
  well as in letter.
- **Two guards keep this true.** `ShippedConfigurationTest` asserts the packaged
  resource declares no environments *and* still declares everything a mounted
  file should not have to restate; `UnconfiguredInstallTest` boots the context
  with the additional location emptied and asserts an empty roster over a
  migrated schema.
- **`compose.yaml` exists now**, mounting `./config` and `./topology` and pinning
  `postgres:16-alpine` per ADR-0150. Its image tag is a `TODO(#64)` placeholder:
  ADR-0143's immutability promise is worth nothing if the compose file naming the
  version floats, so `:latest` must not survive the tag.
- **The install documentation inherits a four-step story** — download
  `compose.yaml`, `up`, write `./config/application.yaml`, restart — and a worked
  example it can point at rather than invent.
- **Revisit trigger.** A plugin whose config genuinely needs a shape environment
  variables cannot express *and* that an operator would reasonably want to set
  without a file. Separately, if a second config-arrival mechanism is ever added,
  it should reopen this rather than accrete beside it.
