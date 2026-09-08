# ADR-0153: The deployable shares the version, and the number promises an upgrade path

- **Status**: Accepted
- **Date**: 2026-09-08
- **Ticket**: [Versioning the deployable, and what v0.1.0 means](https://github.com/fredskor/nodqora/issues/64)

## Context

[ADR-0142](0142-one-version-for-six-artifacts.md) put one version across the six
library artifacts and made it mean one thing: *this is the same commit*. Those
six are private, resolved by one consumer — the Enterprise assembly
([ADR-0141](0141-publication-is-private-and-the-sdk-trigger-stays-unfired.md),
[ADR-0114](0114-the-enterprise-edition-is-a-second-assembly.md)).

[ADR-0150](0150-the-community-distribution-is-one-public-image-and-a-compose-file.md)
adds a seventh thing with none of those properties: `nodqora-app` as a **public**
image on GHCR, for a stranger who will never see a Maven coordinate. Nothing so
far says whether it shares the libraries' number, and
[ADR-0114](0114-the-enterprise-edition-is-a-second-assembly.md) excluded it from
the six, so it is not obvious the question even arises.

It arises by default. `build.gradle.kts` sets `version` in `allprojects`, so the
deployable already carries the libraries' number today — by construction, not by
decision. Splitting is the change.

Two facts sharpen what the number has to say, and neither is in ADR-0142:

- **Community has no response commitment at all**
  ([ADR-0137](0137-all-community-support-is-public.md)) — public issues, nothing
  promised. So the first tag does *not* create a support obligation to the
  audience this map is for; ADR-0139's support-bearing *latest release* lands on
  the Enterprise line and on ADR-0138's paid Community agreement. The number has
  to earn its keep some other way.
- **Upgrades are already one-way.** Flyway runs two forward-only migrations and
  Flyway Community has no `undo`, so downgrading an install is impossible today
  and nothing says so.

## Decision

### The deployable joins the lockstep

**One version across all seven artifacts, and the image tag is that number.**
ADR-0142's principle was that the version is a property of *this repository*, not
of any module in it; the deployable is a module in it, built from the same
commit, on ADR-0139's single line of development.

### The number carries a second clause, for a second audience

ADR-0142 states minor and patch entirely in terms of **public signatures in six
library jars**. That is checkable, and it is meaningless to a self-hoster, who
has no compile step to fail. Under one number the same digits must answer both
readings:

- **patch** (`0.1.0` → `0.1.1`) — *libraries*: no public signature moved.
  *Deployable*: **drop-in.** Same config, no migration; pull and restart.
- **minor** (`0.1.0` → `0.2.0`) — *libraries*: a signature may have moved.
  *Deployable*: **your `/app/config/application.yaml` may need an edit, and a
  schema migration may run.**

**Upgrades are one-way, and that is part of what `v0.1.0` asserts.** Flyway is
forward-only, so `0.2.0` → `0.1.0` against one database is not a supported
operation and will not become one under ADR-0139's single line.
[ADR-0143](0143-published-versions-are-immutable-and-the-inner-loop-has-no-artifact.md)
supersedes rather than replaces and ADR-0139 leaves a regression no escape
hatch, so **the operator's escape hatch is a database backup taken before the
upgrade** — which the install documentation owes them in a sentence.

### `1.0` requires both surfaces

ADR-0142 tied `1.0` to going public — the moment a surface becomes a promise.
ADR-0150 makes the image **public from birth**, so for the deployable that
trigger is spent without ever having fired, and `1.0` would otherwise mean *a
private Maven registry became a public one*: an event the only audience that
reads the number cannot see.

The deployable brings its own surface. It is not Java signatures but the things
an install is built out of, all fixed by
[ADR-0152](0152-the-image-ships-no-environments-and-config-arrives-as-two-mounts.md):
**the `application.yaml` key grammar, the topology file format, and the two mount
paths.**

**`1.0` requires both — the libraries going public *and* the config grammar
settling.** One number cannot promise half a project. ADR-0142's *reached
deliberately and never drifted into* is unchanged; what changes is that it now
waits on the later of two independent readinesses.

### Immutability is enforced by the gate, not the registry

An OCI tag is a **mutable pointer by design**: a second push moves it and every
pinned `compose.yaml` runs different bytes under the same name. GHCR also permits
deleting a published version, which Maven Central does not. Both of ADR-0143's
protections are absent at the registry layer.

- **A release pushes exactly two tags: the exact version, and `latest`.** No
  floating `0.1` or `0`. `latest` is honest — ADR-0139 makes the latest release
  the entire support surface — and it is the difference between `docker run
  ghcr.io/fredskor/nodqora` working and failing for someone thirty seconds into
  meeting this project. **No install runs it**: ADR-0150's `compose.yaml` pins
  the exact tag, which is what stops a stray `docker compose pull` walking an
  operator across a one-way migration.
- **The enforcement is
  [ADR-0145](0145-the-release-gate-is-a-gradle-task.md)'s existing gate**, which
  already refuses when the version is already tagged. Git tag and image tag are
  the same string by construction, so the guard we own already covers the image,
  and it fails on the machine before anything is pushed. GHCR's own immutability
  setting is not the mechanism.
- **A bad release is superseded, never deleted.** `0.1.1` supersedes `0.1.0` and
  `latest` moves; the broken version stays pullable so pinned compose files keep
  resolving. **Deletion is legally-compelled only** — a shipped secret, or
  something we had no right to redistribute — and never a remedy for a bug.

### One tag releases everything, atomically

**Cutting the tag publishes the six jars to the private registry, the public
image, and the `compose.yaml` asset, in one act behind one gate.** ADR-0139 and
[ADR-0147](0147-only-the-latest-release-is-supported.md) make *the latest
release* support-bearing, so **"is `0.1.0` released?" must have one answer**, not
one per artifact.

[ADR-0144](0144-releases-are-pulled-by-a-consumer-never-pushed-by-a-schedule.md)
is satisfied rather than bent: it governs *when* a release happens, not what is
in one, and this release **is** consumer-pulled — by a consumer that ADR did not
imagine, namely strangers wanting an installable Community.

Rejected, and why:

- **A separate version line for the deployable.** Genuinely more honest — a
  library-only fix would stop bumping the product a user sees, and a frontend fix
  would stop bumping six untouched libraries. It buys that with a **second tag
  namespace** on a repository ADR-0139 leaves with one line of development, and
  it revives one level down the question ADR-0147 had to answer for Enterprise —
  *which library version is inside this image* — inside a repo that currently
  gets the answer for free. Under `0.x`, with no compatibility promise on either
  side, the noise it removes is cheap and the mapping table it adds is permanent.
- **Leaving the deployable's number asserting nothing**, with a changelog
  carrying the upgrade story. It makes the version a decoration for the only
  audience that reads it, which is the exact ground ADR-0142 rejected CalVer on:
  *if it also cannot mean this may break you, it means nothing.*
- **Letting `1.0` fire on the libraries' trigger alone.** The image would then
  assert a config-stability promise nobody made — a version number lying to its
  readers.
- **A partial release**, publishing the image without the jars while no
  Enterprise repo exists to want them. It saves nothing measurable in a private
  registry with one publisher, and it makes a support-bearing question ambiguous.

## Consequences

- **`1.0` got strictly harder to reach than ADR-0142 left it.** That ADR needed
  one readiness; this one needs two, and they are independent. Accepted as the
  price of one number, and consistent with `1.0` being an act rather than a
  drift — but it is a constraint ADR-0142 did not sign up for and would not have
  chosen alone.
- **The libraries' release cadence is now set by the deployable's audience.**
  Before this, the six moved when Enterprise asked (ADR-0144). Now they move
  whenever the public product ships, which will be far more often, and every
  image release burns a version number the Enterprise consumer did not ask for.
  ADR-0142's footnote — *modules get releases in which nothing about them
  changed* — becomes the normal case.
- **A broken public image is pullable forever.** ADR-0143 accepted this for a
  private coordinate resolvable by one consumer; here a stranger can pull it, and
  supersede-never-delete means the only signal that they should not is `latest`
  pointing elsewhere. This raises the stakes on ADR-0145's gate again, for a
  wider audience than it was written for.
- **The install documentation inherits a required sentence.** *Back up your
  database before upgrading* is not advice here, it is the consequence of a
  forward-only schema meeting a no-rollback release policy. The still-unwritten
  install docs carry it.
- **`docs/editions.md` gains nothing.** ADR-0147 found the version policy not
  customer-facing, and that survives: the user-facing half of this decision
  belongs in install documentation, next to the upgrade command it qualifies, not
  in an editions comparison.
- **Nothing here is executable yet.** The `-SNAPSHOT` handling, who edits the
  version, and whether a laptop or a workflow cuts the tag are
  [Cutting the release: by hand or by workflow](https://github.com/fredskor/nodqora/issues/68),
  which inherits every decision above.
- **Revisit trigger.** A release cadence mismatch sharp enough to hurt — the
  deployable shipping so often that the Enterprise consumer cannot track version
  numbers that mean nothing to it — or a second consumer of the libraries, which
  is already ADR-0141's and ADR-0142's shared trigger. Either reopens the single
  number, and the split rejected above is what it reopens to.
