# ADR-0154: THIRD-PARTY covers what the build adds, and the base image attributes itself

- **Status**: Accepted
- **Date**: 2026-09-08
- **Ticket**: [What the image bundles, and who owes THIRD-PARTY](https://github.com/fredskor/nodqora/issues/67)

## Context

[ADR-0146](0146-the-jar-carries-licence-and-notice-and-no-dependency-inventory.md)
settled that a library jar carries `LICENSE` and `NOTICE` and no dependency
inventory, because none of the six bundles third-party code. It parked the
attribution obligation on whatever eventually does bundle, and named a revisit
trigger: *the first Community binary distribution.*
[ADR-0150](0150-the-community-distribution-is-one-public-image-and-a-compose-file.md)
pulled that trigger — one public image — so one `THIRD-PARTY` is owed.

The obligation has three sources that nothing so far treats alike, and the tree
was measured rather than guessed at:

- **The `eclipse-temurin:21-jre` base layer.** 140 dpkg packages and a JRE, none
  of it resolved by Gradle. Pushing a derived image to GHCR puts those layers in
  this repository's namespace, so *we* redistribute them.
- **The Gradle runtime classpath.** The shipped boot jar carries **86 jars** in
  `BOOT-INF/lib`, six of them Nodqora's own, leaving **80** third-party.
- **The frontend's npm dependencies.** `vite build` bundles React and
  `@xyflow/react` into the served assets. `package.json` is `private: true` and
  npm sits outside Gradle entirely.

Three measurements shaped the decision, and each contradicted the obvious answer:

- **`runtimeClasspath` does not describe what ships.**
  `spring-boot-jarmode-tools-3.4.1.jar` is inside `BOOT-INF/lib`, carries real
  classes, and appears on **no** Gradle configuration — `bootJar` injects it
  through an internal detached configuration. A generator reading the
  configuration omits it.
- **`npm ls --omit=dev` does not describe what ships either.** It reports 29
  packages; only **17** contribute code to the production bundle. Nine
  `@types/*` packages plus `csstype` are types only, and `js-tokens` and
  `loose-envify` are `react-dom`'s CJS-path dependencies that an ESM production
  build never pulls in. That is a 41% over-list, of exactly the kind ADR-0146
  rejected: *"a list of things the jar does not contain."*
- **The artifacts cannot attribute themselves.** Only **35** of the 80 jars carry
  a `META-INF` licence file and **30** a `NOTICE`; **45** carry neither. And
  **39** of the 80 POMs declare no `<licenses>` element of their own, inheriting
  it from a parent — so metadata resolution has to walk the parent chain.

## Decision

**`THIRD-PARTY` is a notices file covering what the Nodqora build adds on top of
its base image. It ships at `/app/THIRD-PARTY` and nowhere else. Gradle assembles
it, and it refuses rather than guesses.**

### It is attribution, not an inventory

The file discharges Apache-2.0 §4(d) and the licences of bundled code. It is not
an SBOM. Its correctness test is *did we omit a required notice*, which is a test
that can be answered; *is this a complete list of everything in the image* is not,
and pursuing it would rebuild ADR-0146's stale-list failure at the largest
available scale. The procurement audience that wants a machine-readable inventory
is the Enterprise buyer, and the Enterprise distribution is a different artifact
with a different build. If that need ever lands here, `docker buildx build
--sbom=true` attaches one as an attestation without touching this file.

### The base image attributes itself, in place

`THIRD-PARTY` covers the 80 bundled jars and the 17 bundled npm packages. For the
base layer it carries a paragraph naming the image **by digest** and pointing at
the notices already inside it:

- `/usr/share/doc/*/copyright` — one per Debian package. The Debian container
  images configure dpkg with `path-exclude=/usr/share/doc/*` **and** an explicit
  `path-include=/usr/share/doc/*/copyright`: the documentation is stripped and
  the copyright files are deliberately kept, for exactly this purpose.
- `/opt/java/openjdk/NOTICE` and `/opt/java/openjdk/legal/` — 49 module
  directories carrying GPLv2+CE and everything OpenJDK vendors.

This relocates the reader, not the notice. Copying 189 files that already travel
with the layer they describe would produce a list this project does not control
and cannot keep current, while the originals cannot drift. It is also the honest
posture: enumerating implies an audit, and the base carries GPL and LGPL code
whose source-availability obligations Debian and Adoptium discharge through their
own channels. Redistributing the layer unmodified inherits that discharge;
publishing a curated list of its contents suggests taking it on.

**The runtime stage installs no packages.** That is a rule, not an observation —
it is what keeps the paragraph true. An `apt-get install curl` for a healthcheck
is an addition the build made, owed an entry, and nothing would catch it.

**The base is pinned by digest** in the Dockerfile, and the header quotes the same
digest, so the claim is true by construction. This matches the model already in
place rather than adding one: ADR-0143 makes published versions immutable and
[ADR-0153](0153-the-deployable-shares-the-version-and-the-number-promises-an-upgrade-path.md)
makes the image tag the version, so the base image is part of what that version
*is*, and rebuilding `v0.1.0` produces `v0.1.0` rather than something adjacent.

### The configuration generates; the artifact verifies

The two halves follow one principle — *derive from the artifact, not from a
manifest* — with different machinery, because the tooling differs:

- **Java.** `com.github.jk1.dependency-license-report` reads `runtimeClasspath`
  for licence metadata, since that is where POM data and parent-chain resolution
  live. A gate then diffs its coverage against the real `BOOT-INF/lib` listing and
  **fails the build** on any jar that ships without an entry. Strays are declared
  in a `thirdPartyExtras` configuration and resolve like any other dependency —
  `spring-boot-jarmode-tools` is declared there rather than special-cased by
  filename.
- **npm.** `rollup-plugin-license` runs *inside* the bundler, so it sees the real
  module graph. Generation and ground truth are the same act and no second gate is
  needed. It emits **JSON**, not prose: Gradle holds the one formatter, so both
  halves are laid out by the same code.

The fragment lands in `frontend/build/`, never `dist/` — ADR-0151 copies `dist/`
into the jar's `static/`, so a notices file written there would be served over
HTTP, which this ADR does not do.

### An entry, and what refuses

Each entry carries the coordinate, every licence its publisher names, the licence
URL and the project URL. Licence *bodies* are reproduced and deduplicated by
exact text, so identical bodies collapse while bodies differing by their embedded
copyright line stay distinct — that line is the part MIT and BSD actually require
to travel. The **30** dependency `NOTICE` files are reproduced verbatim in their
own section, because Apache-2.0 §4(d) obliges them to travel and it is the most
skipped clause in generated attribution files.

**Every named licence is listed, never just the first.** The report cannot
distinguish *or* (dual-licensed, elect one) from *and* (different parts, different
terms), so dropping any of them would be dropping a licence from a legal document:
`logback` is EPL-1.0 or LGPL-2.1, and `tomcat-embed-core` is Apache-2.0 with
EPL-2.0 parts.

Publisher spellings are folded to SPDX identifiers — this build sees **six**
spellings of Apache-2.0 — through a map of unambiguous synonyms only. A name whose
version or variant is not certain from the name alone passes through verbatim;
`GNU Lesser General Public License` is left as published, because the name omits
the version.

**No entry may ship saying `Unknown`.** An unresolved licence fails the build, and
gaps are filled by a reviewable `third-party-overrides.json` carrying a `why` for
each. `Unknown` is worse than a stale list: it is a list that documents its own
incompleteness and ships anyway.

**Both external inputs are required, and their absence is an error.** The npm
fragment path and the base image digest are passed in; neither has a sane default,
and inventing one is how half a notices file ships. They are declared Gradle
inputs, not merely read, so the task cannot go `UP-TO-DATE` across a change of
base image and reuse a header naming a digest the image was not built on.

Rejected, and why:

- **An SBOM (CycloneDX or SPDX) as the primary artifact.** Larger, faster-drifting
  and aimed at an audience that is out of scope on this map. Available later as a
  buildx attestation at no cost to this decision.
- **Enumerating the base layer's 140 packages.** Copies notices that already ship
  one layer down into a file that goes stale when the base is rebuilt, and implies
  an audit whose obligations cannot be met from a laptop.
- **Generating solely from `runtimeClasspath`.** Provably wrong today by one jar,
  and wrong in the direction the obligation cares about.
- **Generating solely from `BOOT-INF/lib`.** A filename is not licence metadata;
  it resolves back through the dependency graph anyway.
- **`license-checker` over the production `node_modules` tree.** Names 12 packages
  that are not in the image.
- **Concatenating the two halves with `cat` in the Dockerfile.** Puts the thing
  that produces and the thing that refuses in different languages with no shared
  notion of a complete file, and buries legally-weighted prose in a shell heredoc.
- **Shipping `THIRD-PARTY` as a release asset or serving it over HTTP.** Both are
  conveniences rather than discharges, and ADR-0147 makes every surface shipped at
  the first tag support-bearing. Both stay cheap to add: the file already exists
  and is already generated.
- **Emitting `Unknown` and shipping, or warning.** The failure mode ADR-0145
  already exists to catch, and a warning nobody reads is the same thing slower.

## Consequences

- **A base-image CVE now requires a release, not a rebuild.** The digest is
  pinned, so nothing picks up a patched Debian userland until someone bumps it —
  and under [ADR-0142](0142-one-version-for-six-artifacts.md)'s lockstep that moves
  all seven artifacts for an OS patch. With no CI and no Renovate, nothing will
  remind anyone the pin has gone stale. **Revisit trigger:** CI arriving in this
  repository, which is where a base-image bump can be automated and where
  ADR-0145 and ADR-0130 already point.
- **The repository gains a `thirdParty` task that nothing yet calls.** The
  generators and the gate are built and exercised; only the `COPY` between Docker
  stages waits, because the Dockerfile does not exist. That wiring belongs to
  whichever ticket writes it, and this ADR is the specification it implements.
- **The first run needed one override, not the audit that was expected.** All 80
  jars resolved a licence. The single gap was cosmetic: log4j publishes its
  licence through an OSGi `Bundle-License` manifest header, which arrives with the
  header's quoting and link attribute intact. The overrides file exists for the
  case where that is not true, and it is one entry today.
- **[Cutting the release](https://github.com/fredskor/nodqora/issues/68) inherits
  a constraint rather than an open question.** The base digest is pinned in the
  Dockerfile and passed to Gradle from there. Whatever orchestration that ticket
  chooses must not introduce a second place the digest is written.
- **The file is 3,859 lines.** Deduplicating licence bodies is what keeps it that
  small; per-package reproduction would have run to roughly 80 copies of the
  Apache licence.
- **Attribution now has a maintainer's edge.** Adding a dependency whose licence
  cannot be resolved fails the release, and the pressure at that moment will be to
  put something plausible in the overrides file. The defence is that the file is
  reviewed, carries a `why` per entry, and is version-controlled — not that the
  gate is softer.
