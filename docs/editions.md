# Nodqora Editions

**Provisional, in one direction.** Every placement below names the evidence
that would reopen it — but reopening only ever moves a capability *toward*
Community. **Nothing shipped in Community ever moves to Enterprise**
(ADR-0128). The placements are recorded as ADR-0107 through ADR-0113 and were
made by
[The Community/Enterprise feature ledger](https://github.com/fredskor/nodqora/issues/42);
how Enterprise is sold is ADR-0123 through ADR-0128, from
[Pricing unit and trial](https://github.com/fredskor/nodqora/issues/45). Both
rest on the survey in
[docs/research/open-core-lines-in-comparable-tools.md](research/open-core-lines-in-comparable-tools.md).

Two editions. **Community** is this repository, Apache-2.0. **Enterprise** is a
separate proprietary distribution; this repository never contains proprietary
files.

---

## The rules

Placements are made by two rules applied in order, and by nothing else. Where
neither rule places a capability, the ADR that places it names its own
justification — which happens exactly once, at the AI assistant.

**1. The split is buyer-based** (ADR-0107). What one engineer or team needs to
see the truth of their system is Community. What an organization needs to run it
across many teams or over time is Enterprise.

**2. The query is free; the standing query is paid** (ADR-0107). Any question a
user asks of the graph in front of them is observation, and observation is
Community. The same question evaluated on a schedule, watched for change and
reported when it changes, is continuous monitoring, and that is Enterprise.

**Anything not listed here is Community by default** (ADR-0108), except four
categories that are Enterprise by standing rule:

| Category | What it means here |
|---|---|
| State flowing **outward** to source systems | any write-back to Kafka, Connect, Kubernetes or a cloud API |
| Approval and request workflow | anything where one person's action waits on another's consent |
| Continuous monitoring and alerting | rule 2, in every form |
| Fleet-scale rollups **across installs** | aggregation over more than one Nodqora deployment |

---

## Community

Everything an engineer needs to see the truth of their system.

| Capability | Roadmap | Why |
|---|---|---|
| Interactive canvas, node inspector, search, filters, links | §49 phase 1, MVP | Seeing the truth of your system |
| YAML topology, environments, deep links | §49 phase 1, MVP | As above |
| Health, freshness, plugin outcome | §49 phases 2–3, MVP | As above |
| **Every discovery plugin, tiers 1–6** — Kubernetes, Kafka, Connect, data destinations, workflow, GitOps, observability, **cloud providers and commercial APM included** | §49 phases 2–7, §12 | Technology is an accident of stack, not a property of the buyer; §5.2 (ADR-0112) |
| Automatic relationship discovery | §49 phase 7 | Reduces map maintenance for the team that owns the map |
| **Upstream, downstream, shortest path, all paths, dependency depth** | §49 phase 8, §16 | Traversal is observation (ADR-0107) |
| **Blast radius and critical dependencies** | §49 phase 8, §17 | Observation of a graph you already own (ADR-0107) |
| **Drift against the previous fold** — what appeared, vanished or changed health since the last refresh | §49 phase 9 (part) | The fold already recomputes wholesale; no storage that grows (ADR-0110) |
| **One-shot comparison** between environments in the same install | §43 (part) | A single-team debugging question (ADR-0107) |
| **Incident view** — filter to unhealthy, related alerts, walk the impact | §49 phase 10 (part) | Composed of parts already free (ADR-0107) |
| **Authentication** — OIDC and SAML bind against your own identity provider | §49 phase 12 (part) | The category convention; contradicts §43 deliberately (ADR-0109) |
| **Audit capture** — complete structured events to stdout, file or Kafka | §43 (part) | You already own the disk (ADR-0111) |

A Community install is a **shared view**: everyone who authenticates sees every
environment in it. Isolation is achieved by running separate installs, which is
already possible and costs a deployment rather than a licence.

## Enterprise

What an organization needs to run Nodqora across many teams, or over time.

| Capability | Roadmap | Why |
|---|---|---|
| **Group synchronisation** — mapping identity-provider groups onto roles | §49 phase 12 | Identity as an administration problem (ADR-0109) |
| **View-scoping / RBAC** — roles and per-environment, per-type, per-owner, per-team visibility in one install | §43, §49 phase 12 | The API is read-only, so authorization here *is* view-scoping, and that is organizational (ADR-0109) |
| **The journal** — durable topology history, time-travel, deployment and configuration history, GitOps events | §49 phase 9 | Storage the product otherwise does not keep (ADR-0110) |
| **Incident timeline** and change correlation | §49 phase 10 (part) | The journal by another name (ADR-0110) |
| **Continuous monitoring** — blast-radius alerting, environment drift detection, standing analysis | §43, standing rule | The standing query (ADR-0107, ADR-0108) |
| **Audit aggregation** — search, retention policy, export, cross-install collection | §43 | Fleet collection (ADR-0111, ADR-0108) |
| **Policy engine** — enforcing conventions such as "every service has an owner" | §43 | Governance *and* a standing query; both rules agree |
| **AI assistant** — architecture explanation, likely-failure identification, incident summarisation, troubleshooting guidance | §49 phase 11 | **Placed by §5.5 "useful without AI", not by the buyer rule** (ADR-0113) |
| **Write-back** to source systems, when it exists | none yet; §5.6 defers it | Standing rule (ADR-0108) |

Two rows describe capabilities that **do not exist yet on either side**. Group
synchronisation is worth nothing until roles exist, and audit aggregation is
thin while every authenticated user sees everything. Neither is being sold
today.

## Unplaced

| Capability | Why unplaced |
|---|---|
| **Collaboration** — annotations, comments, shared or saved views | Named in §43 with no specification anywhere in the plan and no roadmap phase. Saved views for one engineer and an organization-wide view library are plainly different tiers, and guessing between them ahead of a spec is how a ledger acquires a line nobody can defend. Tracked in the map's *Not yet specified*. |

---

## How Enterprise is sold

**A flat annual subscription per installation** (ADR-0123). No price is
published yet; see *The price* below.

**The unit is a deployment of Nodqora, and nothing Nodqora observes is ever
counted.** Nodes, environments, plugins, connected systems and authenticated
users are unlimited on both editions, permanently. There is exactly one
countable object and it is the thing you deploy, not the estate you own.

| Question | Answer |
|---|---|
| Replicas of one deployment | **One installation.** Scale out freely |
| Non-production copies — sandbox, upgrade rehearsal, config testing | **Free and unlimited** |
| A DR standby | **Free while passive**; an installation once it serves users |
| Environments inside one install (prod, staging, dev) | **Unlimited and free**, on both editions |
| Discovered nodes, edges, plugins, connected systems | **Unlimited and free**, on both editions |
| Users who sign in | **Unlimited and free**, on both editions |

You receive **one key per installation**, carrying a name you choose —
`eu-prod`, `acme-gov-enclave`. Nothing fingerprints your hardware or your
cluster, because upgrading is a redeploy (ADR-0114) and a fingerprint would
break on every one (ADR-0124). The key verifies offline and
never contacts us (ADR-0121); an air-gapped install stays air-gapped.

**If a subscription lapses, nothing stops working.** The install boots, the
canvas renders, health refreshes, the journal keeps writing, and every view
scope already configured stays enforced. What freezes is the control path — new
roles, scopes, monitoring rules, policies and retention changes (ADR-0120).
There is a thirty-day grace period past expiry (ADR-0121). The key is a renewal
prompt, not a lock, and it is sold as one.

### The trial

**Thirty days, full-featured, arranged through us** (ADR-0125). Extended by
reissuing a key if a security review or procurement runs long — ask.

It is sales-gated rather than self-serve for a structural reason worth stating
plainly: Enterprise is a private artifact, and possession of that artifact is
what gates capability (ADR-0119). A self-serve download would be a decision to
publish the proprietary build to anyone who fills in a form.

### The price

**Contact us.** No list price is published yet, because there is not yet enough
selling behind it for a published number to be anything but a guess — and a
published guess is an anchor we would own permanently (ADR-0127).

We have committed to publishing a list price once **five Enterprise deals have
closed, or twelve months of active selling have passed**, whichever comes first.
The *unit* and the *trial terms* are published now, above, so you can tell what
you would be charged for without a call.

**There is no free Enterprise tier, at any threshold** (ADR-0126). Community is
the answer for a small organisation, and it is a complete one. A zero-cost key
for a university, an OSS foundation or a design partner remains something we can
decide to do; it is not a tier and carries no published threshold.

---

## What is promised

Three commitments, in one direction only (ADR-0128).

1. **No capability shipped in Community ever moves to Enterprise.** Every
   Community row above is a commitment, not a placement. Capabilities may move
   the other way — Enterprise to Community — and the revisit triggers below
   operate only in that direction.
2. **New, unshipped capabilities are unconstrained.** The rules at the top of
   this page place them when they ship. Nothing is promised about work that does
   not exist yet.
3. **The Community edition stays Apache-2.0 and self-hostable.** This closes the
   manoeuvre the survey found vendors treat as fair game — never demoting a
   feature, while withdrawing the free way to run it. Airbyte withdrew its
   self-hosted Enterprise SKU during the writing of this map; we are promising
   against the equivalent here.

The cost of promising this is real and is accepted: if a Community capability
turns out to be the one everyone would have paid for, it cannot be taken back —
only built beside.

---

## Where this contradicts the product plan

§43 was a sketch and closes by saying the split *"should not be finalized until
there is user adoption evidence"*. The survey is the first evidence on file, and
it moves three items:

| §43 said | This ledger says | Reason |
|---|---|---|
| SSO/SAML is Enterprise (listed first) | **Authentication is Community**; group synchronisation is Enterprise | No lineage or catalog product gates SSO. Where it *is* gated, the real seam is group sync — the finer Grafana/GitLab cut (ADR-0109) |
| Advanced blast radius is Enterprise | **Blast radius is Community**; alerting on it is Enterprise | Nobody in the survey gated a query; several gated the schedule (ADR-0107) |
| Incident mode is Enterprise | **Decomposed** — the view is Community, the timeline is Enterprise | Its parts were already placed, and an incident is the worst moment to meet a paywall (ADR-0107) |
| Enterprise integrations are Enterprise | **No plugin is Enterprise** | Technology is not a buyer axis; §5.2 (ADR-0112) |
| Audit logging is Enterprise | **Capture free, aggregation paid** | The consensus shape in three unrelated products; the concession costs nothing (ADR-0111) |

§43's remaining items — topology history, environment comparison, RBAC, the AI
assistant, the policy engine, managed SaaS — are unchanged in direction, though
history and comparison are both split rather than placed whole.

---

## What this costs

Stated here rather than left to be discovered.

- **Every capability that demos well is free.** The paid half is administration,
  memory and vigilance — none of which show up in five minutes. Selling
  Enterprise therefore needs an installed base rather than a pitch, which is a
  slower commercial path chosen deliberately over one that risks the adoption the
  whole model depends on (ADR-0107).
- **Enterprise is thinner than §43 imagined.** That is the price of taking the
  buyer rule seriously.
- **The lines are product boundaries, not technical ones.** The difference
  between a query and a scheduled query is a cron expression; the difference
  between drift and a journal is a table. These deter rather than enforce, which
  matches what the survey found every comparable vendor actually relies on, and
  the gating mechanism ([issue #44](https://github.com/fredskor/nodqora/issues/44))
  inherits that rather than solving it.
- **Charging per connector is foreclosed** (ADR-0112), which removed the most
  natural expansion-revenue mechanism. The pricing unit gave up the rest of it:
  a flat per-installation subscription means a twenty-person startup and a
  ten-thousand-engineer bank pay for the same object, and expansion comes only
  from genuinely separate estates (ADR-0123).
- **Every free placement above is now permanent** (ADR-0128), so the cost of
  putting a row on the wrong side of the line no longer decays — it compounds.
- **The free tier's isolation story consumes deployments.** Eight teams wanting
  eight views run eight Nodqoras. That is the intended upgrade pressure, and it
  is also a problem a team that automates deployments may simply solve
  (ADR-0109).

---

## Revisit triggers

Each ADR carries its own; these are the ones that would reopen the ledger rather
than a single row.

**All of these move capabilities toward Community, never away from it**
(ADR-0128).

- Installs active for more than six months with no Enterprise enquiry, or
  enquiries asking for something already in Community — the standing-query line
  is drawn where value is not (ADR-0107). Note what this trigger can no longer
  mean: the answer is finding what an organisation will actually pay for, not
  moving a Community row into Enterprise.
- Community installs building their own journals by scraping `/state` — the free
  line is short of the need (ADR-0110).
- Natural-language querying becoming table stakes in the category — §5.5 is
  falsified and ADR-0113 falls with it.
- A middle tier being asked for. That is a redraw of the map's destination and
  returns as a fresh effort, not an amendment here.
- A customer whose Enterprise value plainly scales with something *inside* one
  install rather than with the install itself — a consultancy or managed-service
  provider running many clients' estates in one deployment is the shape to watch
  for. That reopens the unit and the buyer rule together (ADR-0123, ADR-0126).
- Five closed Enterprise deals, or twelve months of active selling — the
  commitment to publish a list price falls due (ADR-0127).
