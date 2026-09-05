import { emptyStateSentence, type CanvasEmptyState } from './emptyState'

/**
 * ADR-0087's three empty states, rendered where the graph would be.
 *
 * Leaving this to ADR-0081's chip alone was rejected on ADR-0081's own grounds: it refused
 * environment-level-only *"because it leaves the canvas with nothing in the blind case"* and
 * declined to license *"the canvas being the last place to find out"*. A cold graph is the maximal
 * instance — ADR-0083's marks were designed for a few affected nodes, and here every node is
 * affected and there is not one on screen to mark.
 */
export function EmptyCanvas({
  state,
  environmentDisplayName,
}: {
  state: CanvasEmptyState
  environmentDisplayName: string
}) {
  return (
    <div className="canvas-empty">
      <p className="canvas-empty-headline">{emptyStateSentence(state, environmentDisplayName)}</p>
    </div>
  )
}
