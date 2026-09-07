# ADR-0124: One named key per installation, never a fingerprint

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [Pricing unit and trial](https://github.com/fredskor/nodqora/issues/45)

## Context

ADR-0119 gave the key customer identity, edition, issued-at, expiry and an open
entitlements map, and left that map open for this ticket to fill. ADR-0123 then
chose a unit that needs no counts in it.

What is still unanswered is whether a customer buying three installations holds
three keys or one key used three times. Without an answer, ADR-0123's unit
leaves no trace anywhere in the product and exists only in a contract.

## Decision

**One key per installation. The key carries an `installation` claim holding a
name the customer chooses at purchase. Nothing verifies that the name
corresponds to anything.**

The Enterprise assembly shows the name in its licence panel and writes it to the
audit stream. The entitlements map stays in the format and stays empty.

Rejected, and why:

- **A hardware, cluster or node fingerprint.** The obvious way to make the count
  real, and unusable here: ADR-0114 makes upgrading a *redeploy*, so every
  upgrade would rotate the fingerprint and break the key. Pods, nodes and
  cluster UIDs all move under a Kubernetes operator. It would convert routine
  operations into support tickets and buy nothing, since ADR-0119 already
  conceded the customer holds the jar.
- **One customer-wide key reused across installations.** Simpler, and it leaves
  the unit invisible in the object the customer actually handles. Three named
  keys make a renewal conversation factual rather than an assertion.
- **Dropping the entitlements map** now that nothing populates it. Keeping an
  unused field costs one line of JSON and is the seam a future cap or middle
  tier would use; removing it would have to be undone by the ticket that needs
  it.

## Consequences

- **Nothing stops a customer copying one key into six deployments.** The audit
  stream will record six installs all calling themselves `eu-prod`, which is at
  least legible if anyone looks. Deterrence with manners, as before.
- **The installation name is customer-chosen and therefore meaningless to
  code.** No feature may key off it, and no future decision should treat it as
  an identifier — it is a label for humans reading a licence panel and an audit
  log.
- **Key issuance is now per installation**, so minting is proportional to
  installations sold rather than to customers. That is an operation of the
  Enterprise repository, which the map holds out of scope.
- **Revisit trigger.** An installation name appearing in a bug report as
  something a customer expected to be enforced or validated. That means the
  panel is overselling the claim and the wording needs to say plainly that it is
  recorded, not checked.
