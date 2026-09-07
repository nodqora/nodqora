# Contributing to Nodqora

Thanks for considering it. This page covers the two things that are easy to get
wrong — how to sign off a commit, and what licensing your contribution under
Apache-2.0 actually means here — and then how to build and test.

## Licensing and the Enterprise edition

Nodqora is **open core**, and this page states the arrangement plainly rather
than leaving you to discover it.

Everything in this repository is Apache-2.0 and always will be. A proprietary
**Enterprise** edition exists; it lives in a separate private repository and
consumes this repository's published artifacts as dependencies
([ADR-0114](docs/adr/0114-the-enterprise-edition-is-a-second-assembly.md)). No
file here is ever proprietary, and no contribution of yours is ever moved into a
proprietary tree.

Two consequences worth stating directly:

- **Your contribution may be used in the Enterprise edition.** This does not
  require you to sign anything beyond the sign-off below. Apache-2.0 §2 grants
  every recipient a *sublicensable* copyright licence, so licensing your patch
  under Apache-2.0 already permits its use in a closed edition — by the
  maintainers and, equally, by anyone else. That symmetry is inherent to the
  licence and is accepted deliberately.
- **A capability that ships in Community never becomes paid.** Placements move
  free-ward only, and Community stays Apache-2.0 and self-hostable
  ([ADR-0128](docs/adr/0128-community-capabilities-move-one-way-only.md)).

You keep the copyright in what you write. There is no CLA and no copyright
assignment; see
[ADR-0129](docs/adr/0129-inbound-is-dco-because-apache-2-0-already-grants-the-sublicense.md)
for why.

Which capabilities sit on which side of the line is published in
[`docs/editions.md`](docs/editions.md) — worth a look before starting anything
substantial.

## Sign off your commits (DCO)

Every commit needs a `Signed-off-by` line. Use `-s` and git adds it for you:

```bash
git commit -s -m "fix: carry the last reading forward on a failed poll"
```

which appends:

```text
Signed-off-by: Your Name <your.email@example.com>
```

That line certifies the [Developer Certificate of
Origin](https://developercertificate.org/) 1.1 — in short, that you wrote the
patch or otherwise have the right to submit it under Apache-2.0. Use your real
name and an address you can be reached at.

Nothing currently verifies sign-off automatically; this repository has no CI
yet. Please add it anyway.

## Licence headers

Every source file starts with a single SPDX line, before the package
declaration or the first import:

```java
// SPDX-License-Identifier: Apache-2.0
```

No per-file copyright line — copyright is stated once, in `LICENSE` and
`NOTICE`. New files should carry the tag; nothing checks this yet
([ADR-0130](docs/adr/0130-the-licence-is-stated-once-per-file-and-once-per-repo.md)).

## Building and testing

The backend needs JDK 21 (the Gradle toolchain will provision one) and
PostgreSQL for the integration tests. See the
[README](README.md#running-it) for the schema and connection details.

```bash
./gradlew build          # compile and test the JVM modules
./gradlew test           # tests only
cd frontend && npm ci && npm test
```

`nodqora-core` must never depend on a plugin — that boundary is
[ADR-0015](docs/adr/0015-core-knows-no-plugin.md) and it is enforced by an ArchUnit test, so a violation
fails the build rather than review.

## Where the decisions live

Before proposing a change in behaviour, check whether it has already been
decided. [`CONTEXT.md`](CONTEXT.md) holds the domain model and
[`docs/adr/`](docs/adr/) the numbered decisions; the worked example everything
is tested against is
[`docs/reference-pipeline.md`](docs/reference-pipeline.md). If your change
contradicts an ADR, say so in the pull request and argue it — that is a normal
thing to do, and superseding an ADR is a normal outcome.
