# ADR-0107: The split is buyer-based, and the query is free while the standing query is paid

- **Status**: Accepted
- **Date**: 2026-09-05
- **Ticket**: [The Community/Enterprise feature ledger](https://github.com/fredskor/nodqora/issues/42)

## Context

[The tiers and monetization map](https://github.com/fredskor/nodqora/issues/40)
fixed a **buyer-based** split rule while charting: what one engineer or team
needs to see the truth of their system is Community; what an organization needs
to run it across many teams or over time is Enterprise. The rule was fixed
without a ticket because it decides the shape of every other decision.

[The open-core survey](https://github.com/fredskor/nodqora/issues/41) later found
GitLab publishing almost the same criterion — *"if the likely buyer is an
individual contributor the feature will be open source, otherwise it will be
source-available"* — which is corroboration from the only vendor in the survey
that publishes a criterion at all.

Applying it to the roadmap, the rule settles most of phases 1 through 11 on
sight and then stops working in one specific place. **Blast radius, environment
comparison and incident mode are each wanted by a single team and by an
organization, for the same reason.** An engineer asking *"what breaks if this
topic dies?"* is the archetypal individual-contributor question; an organization
wanting that answer maintained across a hundred services is the archetypal
enterprise one. The buyer axis cannot separate them because the buyer is the
same person at two different scales of attention.

Product plan §43 sketched all three as Enterprise. The survey found the paid
categories that recur across all ten products, and the recurring one that covers
these three is not *analysis* — it is **continuous monitoring and alerting**.
Nobody in the survey gated a query. Several gated the schedule.

## Decision

**The buyer-based rule stands as the primary rule, and a second rule decides
what it cannot: the query is free, the standing query is paid.**

- **Asking is Community.** Any question a user poses about the graph in front of
  them — traverse it, filter it, diff it, walk its blast radius — is observation
  of data the customer already owns, and observation is what the Community half
  of the buyer rule promises.
- **Nodqora asking on your behalf is Enterprise.** The same question, evaluated
  on a schedule, with a result that is watched for change and reported when it
  changes, is continuous monitoring.

The two rules apply in order: the buyer rule places a capability where it can,
and the standing-query rule places what the buyer rule leaves ambiguous. Where a
capability is placed by neither, the ADR placing it **names its own
justification** rather than claiming the rule settled it (ADR-0113 is the case).

Three consequences follow immediately and are recorded here rather than in
separate ADRs, because each is the rule applied rather than a decision of its
own:

**Phase 8 is entirely Community.** Upstream, downstream, shortest path, all
paths, blast radius and critical dependencies are all traversal. §43's *"advanced
blast radius"* is reinterpreted as the alerting, not the traversal. Product plan
§16 already calls these *"core operations"* and §47 already puts
upstream/downstream in the MVP's required features, so part of this was free by
fact.

**Environment comparison splits at the schedule.** Comparing two environments in
the same install on demand is Community. Continuous drift detection, alerting on
divergence and scheduled comparison reports are Enterprise.

**Incident mode is not a feature and gets no ledger line.** Phase 10 decomposes
into parts already placed: unhealthy filtering is a filter on current health,
related alerts are observation, dependency impact is phase 8 — all Community.
Recent changes and the incident timeline need the journal, and are Enterprise by
ADR-0110.

## Consequences

- **The rule that decides the hard cases is about time, not about people.** That
  is a different axis from the buyer rule and it is deliberately so: the buyer
  rule failed on exactly the capabilities where the distinction is temporal. Two
  rules on two axes cover the space the roadmap actually contains.
- **§43 is contradicted in three places**, and this ADR is the contradiction:
  blast radius, environment comparison and incident mode were all sketched as
  Enterprise there. §43 closes with *"this split should not be finalized until
  there is user adoption evidence"*, which is the licence to overrule it, and the
  survey is the first evidence on file.
- **Enterprise gets thinner than §43 imagined.** After this ADR it rests on
  group synchronisation and view-scoping (ADR-0109), the journal (ADR-0110),
  audit aggregation (ADR-0111), continuous monitoring, the policy engine, and the
  AI assistant (ADR-0113). That is the price of taking the buyer rule seriously,
  and it is paid knowingly: a Community edition that cannot answer an engineer's
  question is a Community edition nobody adopts, and §77 names *"a diagram
  engineers stop using"* as the product's main risk.
- **Cost: the free half is where the demo lives.** Every capability that makes
  Nodqora impressive in a five-minute demonstration is now Community. The paid
  half is administration, memory and vigilance — none of which demonstrate well
  and all of which are felt only after months of use. Selling Enterprise
  therefore requires an installed base rather than a pitch, which is a slower
  commercial path deliberately chosen over a faster one that risks the adoption
  the whole model depends on.
- **Cost: "standing" is a boundary a forker can move.** The difference between a
  query and a scheduled query is a cron expression. This rule is defensible as a
  *product* line but it is weak as a *technical* one, and the gating mechanism
  ([issue #44](https://github.com/fredskor/nodqora/issues/44)) inherits that
  weakness rather than solving it. The survey's finding that every vendor's gate
  is weaker than it looks — OpenMetadata's is possession of a jar — applies here
  in full.
- **Revisit trigger.** Adoption evidence that engineers use the free traversal
  and never encounter a reason to buy: installs active for more than six months
  with no Enterprise enquiry, or enquiries that ask for a capability already in
  Community. Either indicates the standing-query line is drawn where value is
  not.
