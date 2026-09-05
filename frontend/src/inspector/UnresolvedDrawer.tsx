import { unresolvedSentence, type UnresolvedState } from '../routing/resolve'

/**
 * ADR-0095: what the drawer says when `?node=` names a node the loaded graph does not carry.
 *
 * A silently-ignored parameter renders identically to a link that never carried a node at all, so
 * the recipient cannot tell a dead link from a plain graph link. The two sentences here are
 * **distinct claims rather than one hedged one**, which is what stops this contradicting an
 * ADR-0088 banner directly above it.
 *
 * **The parameter is retained in the URL**, in all three cases. That is ADR-0047's self-healing fold
 * cashed in: deletion is non-destructive because *"the next clean poll restores a node exactly"*, so
 * keeping the parameter means the very next poll that brings the node back selects it, with no
 * second click. Stripping it would permanently discard the reader's request in order to tidy an
 * address bar. Retention also makes the state live in both directions — a selected node that leaves
 * a snapshot mid-session transitions to this sentence rather than the drawer silently closing.
 *
 * The cost is a URL that can persist a request nothing will ever satisfy. It is the same cost
 * ADR-0070 accepted at the point of use: the honest report of an absence is worth more than a tidy
 * URL, and the sentence names the scope so the reader can act on it.
 */
export function UnresolvedDrawer({
  state,
  environmentDisplayName,
  parameter,
  onClose,
}: {
  state: Exclude<UnresolvedState, 'SILENT'>
  environmentDisplayName: string
  parameter: string
  onClose: () => void
}) {
  return (
    <aside className="drawer" aria-label="Node inspector">
      <header className="drawer-head">
        <div>
          <h2>Not found</h2>
          <code className="drawer-key">{parameter}</code>
        </div>
        <button className="drawer-close" onClick={onClose} aria-label="Close inspector">
          &times;
        </button>
      </header>
      <p className="drawer-unresolved">{unresolvedSentence(state, environmentDisplayName, parameter)}</p>
    </aside>
  )
}
