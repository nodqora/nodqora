# ADR-0121: The key verifies offline, at boot and daily, behind a grace window

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [License-key and gating mechanism](https://github.com/fredskor/nodqora/issues/44)

## Context

ADR-0119 fixes what the key says; ADR-0120 fixes what happens once it has
expired. What remains is mechanical, except for one judgement.

Enterprise installs are self-hosted and may be air-gapped — ADR-0114's revisit
trigger already contemplates a buyer running one artifact through an approval
process. Verification therefore cannot touch the network in any code path.

## Decision

**A signed compact token (Ed25519), verified against a public key compiled into
the Enterprise assembly, supplied as a file path or environment variable so it
mounts as an ordinary Kubernetes Secret. No network call, ever.** This is the
Airbyte/Kong shape minus the public verifier ADR-0119 rejected.

**The token is verified at startup and re-evaluated daily**, so an expiry lands
without waiting for a restart, and a renewal lands without one either.

**Expiry is followed by a thirty-day grace window before ADR-0120's freeze takes
effect.** Through it the install behaves exactly as it did the day before, while
the operator sees an escalating warning naming the date the install read.

Rejected, and why:

- **No grace.** Cleaner to explain and cheaper to test. Refused because renewal
  paperwork slips for reasons that have nothing to do with the engineers whose
  map would freeze, and because a host with a skewed clock would otherwise
  freeze an install that is fully paid up.
- **Startup-only checking.** A long-lived deployment would run years past expiry
  and the key would be decorative.
- **A network check** confirming entitlement with a vendor service. It fails the
  air-gapped buyer outright, and it is the first step into the telemetry
  question the map has deliberately left in the fog.

## Consequences

- **The grace window is the real expiry date.** Sales should quote it as such,
  rather than discover that nothing at all happens on the date in the contract.
- **The install trusts its own clock.** A host wrong by more than the grace
  window either freezes early or never freezes. Neither is worth defending
  against; the failure is loud, and the operator warning states the date read.
- **Renewal is a file swap**, picked up at the next daily evaluation with no
  restart. That is the property that makes the whole mechanism cheap to live
  with, and it is the reason the check is not startup-only.
- **The signing key becomes an operational secret** the Enterprise repository
  must custody and rotate. Losing it is losing the ability to renew anyone.
- **Revisit trigger.** A support incident caused by the grace window itself —
  either a customer who did not know they had expired, or one who treats it as a
  free extension every cycle.
