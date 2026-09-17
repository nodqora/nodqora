# ADR-0171: The release gate opens an upload and cancels it, because the token no longer says what was granted

- **Status**: Accepted
- **Date**: 2026-09-17
- **Ticket**: [The release gate cannot read ghcr.io's opaque token](https://github.com/nodqora/nodqora/issues/136)
- **Amends**: [ADR-0163](0163-the-release-gate-asks-the-registry-what-the-credential-may-do.md): *how* the gate asks. Its question still stands: whether this credential may push, answered by the registry and never inferred from a file. So do its refusal on anything unexpected, its credential resolution and its redaction.

## Context

[The `v0.2.0` release run](https://github.com/nodqora/nodqora/actions/runs/35219933970/job/105197289568)
stopped in `releasePreflight` with nothing published:

```
ghcr.io's token is not a JWT this can read the `access` claim of
```

ADR-0163 names this as its revisit trigger, and the gate did what that ADR
wanted: it refused instead of assuming. Before choosing a replacement, this
is what ghcr.io was measured doing on 2026-09-17:

- **An anonymous token is opaque.** `djE6…` decodes to
  `v1:nodqora/nodqora:<nanos>`: a repository and a timestamp, with no actions.
- **An anonymous `pull,push` request is refused** with `403 DENIED`, not
  narrowed.
- **A credentialed token request decides nothing.** A basic credential with no
  `write:packages` got `200` for `pull,push` on `nodqora/nodqora`, and also for
  a repository that does not exist. The returned "token" is **exactly the base64
  of the credential presented**. The token endpoint no longer makes an
  authorisation decision, so no claim exists to read, JWT or otherwise. ADR-0163's
  probe cannot be repaired; it has nothing left to measure.
- **The write path does refuse.** Presented as a bearer, that same credential
  got `403 DENIED — permission_denied: The token provided does not match expected
  scopes.` on `POST /v2/nodqora/nodqora/blobs/uploads/`, and no session was
  opened. Sent as basic auth directly, the upload answers `401` with a challenge,
  so the exchange still has to come first.

## Decision

**`releasePreflight` asks whether the credential may push by opening a blob
upload on `nodqora/nodqora`, then cancelling it.**

1. Exchange the credential at `ghcr.io/token` for `pull,push`, as before. A
   `401` or `403` here is a *no*. A `200` now means only "here is something to
   present", and nothing is read from it.
2. `POST /v2/nodqora/nodqora/blobs/uploads/` with that bearer. **`202` is a yes;
   `401` or `403` is a no.** This is the first write `buildx --push` makes, so the
   gate is asking at the exact place act 3 would be refused.
3. `DELETE` the session named by `Location`.

**Anything else still refuses.** An unreachable registry, a token response with
no token, or an upload answered with a status other than `202`, `401` or `403`
stops the release at preflight, as ADR-0163 required.

**A cancel that does not land does not change the answer.** If the `DELETE` is
refused, fails, or there is no `Location` to send it to, the release proceeds
and the gate logs one line saying the session was left to expire. An upload that
is never completed makes no tag, no manifest and no package version, so a
stranger sees nothing. ADR-0163's rule against litter was about permanent
public writes, and this is neither. Refusing here would also stop every release
the day GHCR stops honouring `DELETE`, and a failure that stops the release when
nothing is wrong is a gate that teaches people to remove it.

Rejected, and why:

- **Reading the package's repository link from GitHub's Packages API.** It is
  read-only, and it explains 0.1.2's *known* cause. But it checks for that one
  cause and not for the push path, so the next reason a push is denied would pass
  it. It also needs `read:packages`, and a laptop's `docker login` has no
  equivalent.
- **Using both, with the API only wording the refusal.** The refusal message
  already teaches the repository-link grant. A second call to explain a denial is
  a second thing to drift, for a better sentence.
- **Refusing when the cancel fails.** Argued above: the question has already been
  answered, and what is left behind is invisible and expires.
- **Pushing a throwaway tag, or reordering the acts.** Still rejected, for
  ADR-0163's reasons.

## Consequences

- **The gate makes a write attempt before anything is built.** On a credential
  that may push, it opens and cancels one upload session per release. That is the
  only registry state the gate touches, and it never touches it on a credential
  that may not push.
- **ADR-0163's "one function makes both" still holds, with a narrower meaning.**
  Act 4 and the gate share `ghcrTokenResponse`. Act 4 still reads a refusal of the
  anonymous `pull` token as *not public*, which was measured again here (a `403`
  for anonymous `pull,push`, and a `200` plus a working manifest `HEAD` for
  anonymous `pull`). Neither caller reads anything from inside the token any more.
- **What was exercised.** The new code was run through a throwaway Gradle task
  against the real ghcr.io, twice. A credential without `write:packages` was
  refused at the upload with `403`. A wrong secret was refused at the exchange
  with `403`. **The `202` path and the `DELETE` were not observed**, because only
  the runner holds a credential that may push. The re-dispatch of `v0.2.0`
  exercises both, and the thing to watch for is a refusal, or a *left to expire*
  line, where a pass was expected.
- **Still no committed test.** ADR-0163's gap stands for the same reason: no
  `buildSrc`.
- **Revisit trigger.** GHCR answering an upload request with something other than
  `202`, `401` or `403`, or accepting an upload from a credential that then fails
  at act 3. Either one means the upload has also stopped being the place the
  registry decides.
