# ADR-0092: The URL is an environment path plus an optional node parameter, and nothing else

- **Status**: Accepted
- **Date**: 2026-09-04
- **Ticket**: [Frontend routing and deep-linking](https://github.com/fredskor/nodqora/issues/21)

## Context

ADR-0052 settled the URL of the **API**. Nothing settled the URL of the
**browser**, and three candidate pieces of state were on the table: the
environment, the selected node, and the active search query.

## Decision

```text
/environments/{envKey}
/environments/{envKey}?node={key}
```

**That is the whole grammar.** The environment is a path segment, the selected
node is a query parameter, and the search query is not in the URL at all.

## Consequences

- **Environment is a path segment for ADR-0052's reason, which is stronger
  here.** That ADR rejected environment-as-query-parameter because *"a forgotten
  parameter is a cross-environment leak rather than a 404"*. In a browser the URL
  is pasted into Slack during an incident and reopened by someone whose client
  state differs, so a dropped scope is not a local slip — it silently reinterprets
  the sender's link in the recipient's scope. A path segment makes an unscoped URL
  unroutable instead.
- **The node is a parameter because there is no route to mirror.** ADR-0053 left
  **no per-node route in the API**; a `/environments/x/nodes/y` path would mint a
  resource the model does not serve, putting a second addressing concept beside
  ADR-0052's. It also forces an answer to what a `/` inside a key means, and
  ADR-0020 fixes the key as a *flat, free-form* string with **no pinned character
  class** — the same fact that disqualified a composed identity string in
  ADR-0072. A query parameter percent-encodes any string and the router never
  sees it.
- **The split is honest about what each thing is.** Scope is structural; selection
  is state within a scope. ADR-0018 calls environment *"the frame every reading is
  made in"*, and a frame belongs in the path.
- **The query is excluded because ADR-0069 already made it exclusive with
  selection** — *"the query and its results clear on pick"*. A `?node=&q=` URL is
  hand-craftable and unreachable, and the app would then have to arbitrate between
  two states designed never to coexist: ADR-0069's *"the drawer would need to
  arbitrate between two"*, one layer up. ADR-0068 also gives search no submit and
  no minimum length, so putting it in the URL means a history entry per keystroke
  or debounced `replaceState` built to preserve a few characters — against
  ADR-0069's own price for the alternative, *"re-running the search costs one
  keystroke"*.
- **ADR-0052's aside is confirmed, not amended.** Its consequences already say
  *"the key is the identifier the `?node=` deep link carries"* — written by
  [#13](https://github.com/fredskor/nodqora/issues/13), which had no mandate to
  decide frontend routing. It lands correct for reasons it never stated.
- Cost: a path-scoped URL carrying a query parameter is a hybrid rather than a
  uniform hierarchy. Recorded rather than hidden — the hybrid is the point, because
  the two halves are not the same kind of thing.
