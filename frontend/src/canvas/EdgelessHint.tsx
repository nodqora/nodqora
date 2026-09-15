// SPDX-License-Identifier: Apache-2.0
import { VERSION } from '../docs/link'
import { drawEdgesDocUrl, edgelessSentence } from './edgeless'

/**
 * ADR-0166's sentence, rendered above the graph rather than in place of it. Quiet, like ADR-0087's
 * headline: this is not an outcome banner, because nothing failed.
 */
export function EdgelessHint({ environmentDisplayName }: { environmentDisplayName: string }) {
  return (
    <p className="canvas-hint">
      {edgelessSentence(environmentDisplayName)}{' '}
      <a href={drawEdgesDocUrl(VERSION)} target="_blank" rel="noreferrer">
        Draw the edges
      </a>
    </p>
  )
}
