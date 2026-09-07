// SPDX-License-Identifier: Apache-2.0
/**
 * ADR-0092: **the URL is an environment path plus an optional node parameter, and nothing else.**
 *
 * ```text
 * /environments/{envKey}
 * /environments/{envKey}?node={key}
 * ```
 *
 * That is the whole grammar. The environment is a **path segment** for ADR-0052's reason, which is
 * stronger in a browser than it was in the API: a URL is pasted into Slack during an incident and
 * reopened by someone whose client state differs, so a dropped scope silently reinterprets the
 * sender's link in the recipient's scope. A path segment makes an unscoped URL unroutable instead.
 *
 * The node is a **query parameter** because there is no route to mirror — ADR-0053 left no per-node
 * route in the API, and a `/environments/x/nodes/y` path would mint a resource the model does not
 * serve. It also forces an answer to what a `/` inside a key means, and ADR-0020 fixes the key as a
 * flat, free-form string with no pinned character class. A query parameter percent-encodes any
 * string and the router never sees it.
 *
 * **The search query is not in the URL at all.** ADR-0069 already made it exclusive with selection —
 * the query and its results clear on pick — so a `?node=&q=` URL is hand-craftable and unreachable,
 * and the app would then have to arbitrate between two states designed never to coexist. ADR-0068
 * also gives search no submit and no minimum length, so putting it in the URL means a history entry
 * per keystroke, against ADR-0069's own price for the alternative: re-running the search costs one
 * keystroke.
 */

export interface Route {
  /** `null` for bare `/` and for anything outside the one grammar — both redirect (ADR-0093). */
  environmentKey: string | null
  /** The `?node=` parameter exactly as supplied, decoded. Resolution happens elsewhere. */
  nodeKey: string | null
}

const PATH = /^\/environments\/([^/]+)\/?$/

export function parseRoute(pathname: string, search: string): Route {
  const matched = PATH.exec(pathname)
  const nodeKey = new URLSearchParams(search).get('node')
  return {
    environmentKey: matched?.[1] ? decodeURIComponent(matched[1]) : null,
    // An empty `?node=` is not a request for a node; it is a parameter someone trimmed by hand.
    nodeKey: nodeKey === null || nodeKey === '' ? null : nodeKey,
  }
}

export function formatRoute(environmentKey: string, nodeKey: string | null): string {
  const path = `/environments/${encodeURIComponent(environmentKey)}`
  return nodeKey === null ? path : `${path}?node=${encodeURIComponent(nodeKey)}`
}

/**
 * ADR-0093: **bare `/` redirects to `environments[0].key`** from the `/api/meta` roster.
 *
 * A redirect makes the root's ambiguity last zero screens. Bare `/` asserts no scope, so resolving
 * it is not the leak ADR-0092 guarded against; what matters is that the address bar is explicit
 * immediately afterwards, which a redirect guarantees and a silently-defaulted render does not.
 *
 * **Last-used-environment was rejected** even though it is friendlier: it makes a bookmarked `/`
 * mean different things to different people and drift over time, which is at odds with ADR-0018's
 * scope-as-frame — the frame should not move because of something the reader did last week in
 * another browser. First-in-roster also makes config order meaningful; operators put `production`
 * first.
 */
export const defaultEnvironmentKey = (roster: { key: string }[]): string | null => roster[0]?.key ?? null

/**
 * ADR-0093: **an unknown environment key renders a designed not-found that names the environments
 * that do exist.** Matching stays exact — no case-folding and no redirect.
 *
 * ADR-0052 matches the environment key exactly because *"it is operator-authored config, not
 * discovered data"*, in deliberate contrast to node keys, which are case-folded. Case-folding the
 * path segment would give the environment key two comparison rules — folded in the UI route, exact
 * in the API — for a hand-typed URL that the not-found already repairs in one click.
 *
 * **Redirecting an unknown key to a valid one is the worst option, not the safest.** It substitutes
 * a scope the user did not ask for and then displays a URL that asserts it: ADR-0092's leak in its
 * most confident form.
 */
export const environmentIsKnown = (roster: { key: string }[], environmentKey: string): boolean =>
  roster.some((environment) => environment.key === environmentKey)
