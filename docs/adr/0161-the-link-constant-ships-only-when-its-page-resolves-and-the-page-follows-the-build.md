# ADR-0161: The link constant ships only when its page resolves, and the page follows the build

- **Status**: Accepted
- **Date**: 2026-09-12
- **Ticket**: [ADR-0133's worked example names nodqora.io; the domain is nodqora.com](https://github.com/nodqora/nodqora/issues/87)
- **Amends**: [ADR-0133](0133-the-mention-is-an-inert-label-one-line-and-one-link.md) — its decision stands unchanged; what changes is when the first mention can ship.

## Context

[ADR-0133](0133-the-mention-is-an-inert-label-one-line-and-one-link.md) gives the
Enterprise mention *"one link to a single URL held in one constant"*, and
[#87](https://github.com/nodqora/nodqora/issues/87) pins that URL to
`https://nodqora.com/editions` — correcting a worked example that named a domain
we do not own.

That page no longer exists. It was removed from the vendor site on 2026-09-10
([nodqora/website@5e8b24b](https://github.com/nodqora/website/commit/5e8b24b))
because there is no paid edition, so a page setting out how one would be priced,
trialled and supported advertised a product rather than describing one. The
commitments did not move: they are in `docs/editions.md`, which the site's
overview links to.

So the URL ADR-0133's constant is pinned to currently 404s. Nothing is broken
today only because [ADR-0134](0134-the-word-enterprise-has-one-address-in-the-frontend.md)'s
`frontend/src/editions/` does not exist yet and the constant is unwritten. #87
corrected the example; the example is what gets copied.

**The constraint is already written down — in the website repository's README,
which the developer it binds does not read.** That developer will be in
`frontend/src`, following an ADR, in this repository. A rule recorded only where
it does not apply is a rule that will be discovered by a user clicking a link.

## Decision

**Three artifacts, one order, and each gate is a fact about the artifact below
it rather than a date:**

1. **An Enterprise build exists**, so the page has something to describe.
2. **The page is restored** — a `git revert` on the website repository.
3. **Only then is the constant introduced**, holding `https://nodqora.com/editions`.

**The constant must not ship while the page is absent.** The asymmetry is the
whole argument, and it is an asymmetry between two media rather than a
preference. A page is edited in place; a published build is not.
[ADR-0150](0150-the-community-distribution-is-one-public-image-and-a-compose-file.md)
distributes Community as a public image at a version tag, pulled by strangers,
and [ADR-0147](0147-only-the-latest-release-is-supported.md) supports only the
latest release — so a wrong link in a shipped version stays wrong in that
version, for everyone who pinned it, and the fix reaches them only if they
upgrade. Nothing catches it in between:
[ADR-0121](0121-the-key-verifies-offline-at-boot-and-daily.md) keeps every code
path off the network on purpose, so no runtime check will ever notice, and the
first report is a reader clicking through to a 404.

**The page must not return while there is no Enterprise build**, which is the
gate 5e8b24b set and this ADR only inherits. It is worth stating because it is
the *worse* of the two failures and reads like the safer one: a link that
resolves to a page describing a product that does not exist is a working link to
a false statement, and nothing announces it. A 404 at least announces itself.

Rejected:

- **Point the constant at `docs/editions.md` on GitHub instead**, and ship it
  now. The closest call, because that URL resolves today and the ledger genuinely
  lives there. Refused on ADR-0133's own terms: the link is *rendered as text* in
  an inert menu item, so it has to survive being read and retyped, and a
  `/blob/main/docs/editions.md` path is neither. It is also not stable —
  [ADR-0158](0158-the-repository-moves-to-an-organisation-and-goes-public-with-its-record-intact.md)
  moved this repository once already, which is direct evidence that a repository
  URL is a location and the apex domain is an address.
- **Point it at `nodqora.com` with no path.** Resolves, and stays resolving. But
  it lands the reader one hop short of the thing they clicked for, and it makes
  the constant's value a fact about the site's current navigation rather than a
  stable address for one subject.
- **Ship the label and the line without the link** until the page returns.
  Already refused by ADR-0133, and for the reason that still holds: *"Informs,
  then dead-ends."* A reader who wants the capability has to leave and guess a
  search term.
- **Restore the page now and ship all three together.** Reintroduces exactly what
  5e8b24b removed, and buys nothing but earlier delivery of an advertisement.

## Consequences

- **The first mention of Enterprise now waits on an Enterprise build.**
  ADR-0133 says *"the first mention ships in whatever feature fires ADR-0132's
  rule"*, and that sentence is no longer true as written — this is what this ADR
  amends. That feature may now fire and render nothing, because ADR-0133 will not
  ship a mention without a link and this ADR will not ship the link. The upsell
  is gated on the thing being sold, which is an odd sentence and the correct
  outcome.
- **ADR-0134's module and its test are not blocked.**
  `frontend/src/editions/` can be created with the labels and the one-line
  descriptions whenever the first mention's feature arrives; only the URL
  constant waits. The test that fails on `Enterprise` outside that directory is
  worth landing early rather than late.
- **Nothing enforces this.** No test can assert that a remote page resolves, and
  ADR-0121's offline-first stance is a reason not to add one even here. This
  joins ADR-0122's recorded cost as a placement rule kept by review, with the
  difference that it is now recorded where the review happens.
- **The constraint is now written in two repositories and can drift.** The
  website README holds the site's half and this file holds the product's. Both
  are accepted: the deadline binds a change made here, and the revert is made
  there.
- **The revert must bring the *Today* column back with it.** The removed page's
  text is intact in 5e8b24b, including the column marking which rows were built;
  restoring the page without it turns a roadmap into a feature list, on both
  sides of the line. That mistake has already been made once.
- **Revisit trigger.** An Enterprise build exists. At that point all three steps
  unblock in order and this ADR is spent.
