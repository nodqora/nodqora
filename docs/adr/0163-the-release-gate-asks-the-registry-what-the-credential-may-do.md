# ADR-0163: The release gate asks the registry what the credential may do, and never infers it from a file

- **Status**: Accepted
- **Date**: 2026-09-12
- **Ticket**: [The release gate tests for a ghcr.io credential's presence, not its authority — and 0.1.2 paid for the difference](https://github.com/nodqora/nodqora/issues/95)
- **Amends**: [ADR-0159](0159-the-release-runs-on-a-runner-and-the-trigger-is-not-a-tag.md) — its `GITHUB_TOKEN` sentence, which holds for packages the workflow created and not for one that predates it. [ADR-0155](0155-the-release-is-one-gradle-command-and-the-tag-is-the-last-act.md)'s ordering and its gate keep their places; what changes is the question the gate asks.

## Context

[Run 34689174632](https://github.com/nodqora/nodqora/actions/runs/34689174632), the
first release cut on a runner under
[ADR-0159](0159-the-release-runs-on-a-runner-and-the-trigger-is-not-a-tag.md),
failed at act 3:

```
ERROR: failed to push ghcr.io/nodqora/nodqora:0.1.2: denied: permission_denied: write_package
> Task :releasePushImage FAILED
```

All six Maven publications had already succeeded, and
[ADR-0143](0143-published-versions-are-immutable-and-the-inner-loop-has-no-artifact.md)
makes them immutable, so **0.1.2 was spent**. Nothing user-visible broke:
ADR-0155's ordering did exactly what it was written for — no tag, no Release,
`:0.1.1` and `:latest` still resolving. The cost was a version number rather than
a tag pointing at nothing.

The gate was built for this precise failure, and said so in its own words at
`build.gradle.kts`:

> This is checked here and not left to act 3 because act 3 runs after the
> coordinates are published, and ADR-0143 makes those immutable — an auth failure
> there costs the version number, not a retry.

What let the failure through is the **test** it chose, one line further down:

> A credential helper leaves an empty `auths` entry, so presence of the key is
> the test.

On a laptop that proxy was sound. The only way a `ghcr.io` entry reached
`~/.docker/config.json` was a `docker login` with a personal access token the
releaser had scoped `write:packages` against their own package, so presence
really did imply authority. ADR-0159 moved the release to a runner, where
`docker/login-action@v3` writes a `ghcr.io` entry for `GITHUB_TOKEN`. The key is
present and the credential behind it cannot push. **Presence was a proxy for
authority, and the proxy stopped holding without the gate noticing** — which is
the one way a gate can fail that costs more than having no gate at all, because
it is the way that is trusted.

The likeliest reading of the denial: `GITHUB_TOKEN` carries `packages: write` for
packages **the workflow creates**, which is why all six Maven packages published
on that same run with that same token. The container package `nodqora/nodqora`
already existed — pushed by hand while cutting v0.1.1 under ADR-0155 — and a
pre-existing GHCR container package carries no repository link, so the token has
no write role on it. This is the strong hypothesis rather than a confirmed
reading; `read:packages` was not available to verify it directly, and the
decision below does not depend on it being right.

**v0.1.3 did not exercise any of this.** The package-repository link was granted
by hand, which unblocked act 3 — so the gate is still testing presence and is
still green for the wrong reason. The next container package pushed by hand
before its first workflow run reproduces the failure exactly.

## Decision

**`releasePreflight` asks ghcr.io whether this credential may push this
repository, and refuses on the answer.** It presents the credential to the token
endpoint and asserts `push` appears in what comes back:

```
GET https://ghcr.io/token?service=ghcr.io&scope=repository:nodqora/nodqora:pull,push
```

**This is act 4's call moved earlier and asked a different question**, not new
machinery. `releaseVerifyPublic` already made this request to prove the pushed
image can be pulled by a stranger *after the fact*; preflight now proves the
runner can push it *before act 2 spends the version*. Anonymous and `:pull`
there, credentialed and `:pull,push` here, and **one function makes both** —
otherwise the claim would be a comment rather than a fact, and the two calls
would be free to drift.

**The answer is in the token and not in the status code.** A registry does not
refuse an over-broad scope request — it answers `200` and *narrows* what it
grants, so a credential that may only pull comes back with a perfectly valid
token naming `pull`. The gate therefore decodes the middle segment of the
returned JWT and reads its `access` claim. Those claims are **read, not
verified**: the signature is the registry's business, and this claim is only ever
used to refuse early.

**A `401` or `403` is an answer too**, and is counted as granting nothing. A
registry that will not issue a scoped token for this credential has said what the
gate asked.

**Anything else is a refusal, not an assumption.** An unreachable registry, a
response that is not a token, a token that is not a JWT — each stops the release
at preflight. Assuming is what cost 0.1.2, and preflight is the one place where
refusing costs nothing.

**The credential is resolved the way Docker resolves one**: a helper named for
the registry, then the global store, then the inline `auth`. The old test could
tolerate an empty `auths` entry precisely because it never needed the secret; the
new one does, so where a credential helper is configured the gate runs it. The
secret is presented to ghcr.io and to nothing else — never logged, and never
reaching an error message.

**The other two credentials keep their existing tests, and that stays right.**
`gh auth status` is checked for usability because act 5 needs it; the Gradle
publish credentials are checked for presence only, and the comment there is
correct that validity is act 2's to discover — act 2 publishes nothing before it
fails. The docker credential was the only one of the three sitting in front of an
irreversible act with nothing between.

**The package-repository grant is not automated.** It is a one-time settings act
(*Manage Actions access* → add `nodqora/nodqora` with **Write**), and the gate's
refusal message teaches it alongside the laptop's `docker login`, as ADR-0155
requires of every gate that stands in for the release document it rejected.

Rejected, and why:

- **Granting the package link and changing nothing else.** This unblocks the next
  release, and it was already done by hand before v0.1.3 — which is exactly the
  problem: it fixes one package and leaves the gate green for the wrong reason.
  The next package pushed by hand before its first workflow run repeats the
  failure with the same version-number price.
- **Pushing a throwaway tag in preflight to prove the credential works.** The only
  test that cannot be wrong, because it exercises the thing itself. It writes to
  the public package to find out whether it may, and
  [ADR-0153](0153-the-deployable-shares-the-version-and-the-number-promises-an-upgrade-path.md)
  reserves deletion for the legally-compelled case, so the proof is permanent and
  public. A gate may not leave litter in the artifact it is guarding.
- **Reordering the acts so the image push precedes the publish.** This removes the
  cost rather than detecting it, but ADR-0155 settled that ordering on its own
  argument and this failure is not evidence against it — it would trade a spent
  version number for a public image no coordinate matches. The gate is the
  cheaper half of the same protection.
- **Amending ADR-0159's sentence and leaving the gate as it was.** The sentence
  does want narrowing, and it is narrowed below. But documentation cannot fail a
  build, and the failure this ADR exists for was a build that passed.

## Consequences

- **ADR-0159's credential claim is narrowed, not withdrawn.** It reads
  "`GITHUB_TOKEN` carries `packages:write`, so the PAT and the docker login both
  disappear." That holds for packages the workflow creates — demonstrated by the
  six Maven coordinates published on the very run that failed. It does not hold
  for a package that predates the workflow, which carries no repository link
  until one is granted by hand. With that scope the sentence stands, and the
  PAT-free runner remains what ADR-0159 bought.
- **Preflight now needs the network.** It already ran `docker buildx version`,
  `gh auth status` and `git`; it now also reaches ghcr.io before anything is
  built. Act 2 and act 3 need that host regardless, so this moves a failure
  earlier rather than adding one — but a release can no longer get as far as the
  build without a reachable registry.
- **The gate reads the credential rather than detecting it.** On a machine using a
  credential helper it runs `docker-credential-<helper> get`, which is a new
  process in the release path. A missing helper binary or an empty store is
  reported as *no credential*, because that is what `buildx --push` would find.
  The secret is carried in a type whose `toString` redacts it, so it cannot reach
  a build log by way of an exception message.
- **None of this is covered by a committed test, and that is a gap.** The root
  build script has no test seam — there is no `buildSrc`, and adding one to hold
  three functions is a structural decision that wants its own argument, not a
  side effect of a bug fix. The decision logic was therefore checked by a
  throwaway Gradle task against fixtures and thrown away with them: credential
  resolution against inline-`auth`, `credHelpers` and `credsStore`, a config
  whose `auths` and `credHelpers` spell the registry differently, a helper for
  another registry, a missing helper binary, an empty config and no config at
  all; and the `access` claim against synthetic tokens granting `pull,push`,
  `pull`, nothing, a different repository, no claim at all, and an opaque token.
  Nineteen cases. A future `buildSrc` is where they should live.
- **The real failure is not reproduced by any of it.** The denial cannot be
  staged now that the package link is granted, preflight refuses to run off
  `main`, and no response from ghcr.io was observed while writing this — the
  `access` claim is read from the registry token specification act 4 already
  relies on, not from a measured reply. The end-to-end path is exercised by the
  next release, and the failure mode to watch for is a *refusal* where the old
  gate would have passed.
- **Revisit trigger.** GHCR answering a scoped token request with something other
  than a narrowed `access` claim — at which point the probe silently starts
  measuring nothing, which is this ADR's own failure mode. Or a second credential
  in the release acquiring an irreversible act behind it, which would make
  presence the wrong test there too.
