# ADR-0157: The image build takes a worker count, because some networks drop Gradle's parallel fetches

- **Status**: Accepted
- **Date**: 2026-09-09
- **Ticket**: [Cut v0.1.0: run the release for real](https://github.com/fredskor/nodqora/issues/83)

## Context

Act 1 of [ADR-0155](0155-the-release-is-one-gradle-command-and-the-tag-is-the-last-act.md)
builds the image without pushing it, precisely so that a build failure costs
nothing. On the machine the first release was cut from, it failed — every time,
in the `backend` stage, on Gradle's Maven Central downloads:

> The server may not support the client's requested TLS protocol versions:
> (TLSv1.2, TLSv1.3) ... Remote host terminated the handshake

The error names TLS, which is a false lead, and *which* jar dies varies between
runs, which is the real one. This had previously been recorded as the agent
sandbox's network egress. It is not. Measured, in order:

| Probe | Result |
| --- | --- |
| `curl` to Maven Central inside a build container | HTTP 200 |
| One jar via the JVM, `docker run` | OK |
| One jar via the JVM, inside the `buildx` builder | OK |
| **16 parallel** JVM downloads, same builder | **14/16** — 2 fail with that error |
| Full image build, agent sandbox disabled | fails identically |
| JVM jar fetch at MTU 1200 vs 1500 | OK either way |

So it is neither egress, nor the sandbox, nor MTU, nor the Dockerfile. It is
**concurrency** — something between this machine and Maven Central drops
connections when enough are opened at once, and Gradle opens many. With hundreds
of jars to resolve and roughly a one-in-eight failure rate per connection, the
build does not pass by being retried.

Serialising fixes it completely: with `--max-workers=1` and nothing else
changed, the same build reports `BUILD SUCCESSFUL in 43s`.

## Decision

The backend stage takes a **`GRADLE_MAX_WORKERS` build argument**, empty by
default. Unset, `${GRADLE_MAX_WORKERS:+--max-workers=$GRADLE_MAX_WORKERS}`
expands to nothing and Gradle chooses its own worker count exactly as before —
so the shipped build is unchanged for everyone whose network does not do this.

It is declared once, before the dependency warm-up, and applies to **both**
`gradlew` invocations in the stage. The warm-up resolves `runtimeClasspath` and
downloads as much as the build does, so a knob that covered only the second
invocation would leave the failure exactly where it was.

The release passes it from `nodqora.build.maxWorkers`, read from the same
`~/.gradle/gradle.properties` that
[ADR-0145](0145-the-release-gate-is-a-gradle-task.md) already keeps the
publishing credentials in, and absent from the repository for the same reason:
it describes a machine, not the project. `buildImage` passes it to both of its
invocations or to neither — the dry run has to build the same image the push
builds, and a worker count that differed between them would make act 1 prove
something about an image act 3 never produces.

## Consequences

- **The default is the old behaviour**, so this cannot slow anyone's build or
  change what the image contains. A machine that never had the problem never
  sets the property and never learns this exists.
- **A hardcoded `--max-workers=1` was rejected.** The measured cost looked small
  — 43s against the ~33s multi-arch cache-hot figure from
  [#74](https://github.com/fredskor/nodqora/issues/74) — but that is a warm-cache
  comparison, and serialising dependency resolution for every future builder to
  work around one network's behaviour prices a local fault into the shipped
  build.
- **This is a workaround, and it is worth saying so.** Nothing here diagnoses
  *why* the connections are dropped; it establishes only that concurrency is the
  trigger and that serialising avoids it. If the cause is ever found and removed,
  the property stops being set and nothing else changes.
- **The failure it prevents is the cheap one.** Act 1 is before anything is
  published, so this never protected a version — it protected the ability to cut
  one at all.
