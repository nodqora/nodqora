// SPDX-License-Identifier: Apache-2.0
import { VERSION, docUrl } from '../docs/link'

/**
 * ADR-0177 §5: the section of the install documentation this screen sends a reader to, pinned to the
 * tag as ADR-0156's link is. The file is in the tree; the `#signing-in` section is the release's to
 * write, and it has to be titled *Signing in* for the anchor to land.
 */
export const SIGNING_IN_DOC = 'docs/install.md'

export const signingInDocUrl = (version: string | null): string => `${docUrl(SIGNING_IN_DOC, version)}#signing-in`

/**
 * ADR-0173 and ADR-0177 §5: **an install with no `nodqora.authentication` replaces the shell with
 * this screen**, in ADR-0156's grammar — one quiet headline, one route, one link, no retry, no
 * spinner, and no Enterprise (ADR-0132's sixth noun).
 *
 * Both answers are named, and `none` by its consequence rather than with a warning or a
 * recommendation; the order follows ADR-0173's closed default. Nothing about upgrading: a new install
 * and a `v0.2.0` upgrade see the same screen, and the release notes carry the upgrade sentence.
 */
export function NoIdentityProvider() {
  return (
    <div className="app">
      <div className="first-run">
        <p className="first-run-headline">No identity provider is configured.</p>
        <p className="first-run-route">
          Declare <code>nodqora.authentication</code> in <code>/app/config/application.yaml</code> — the
          same file that declares <code>nodqora.environments</code>. Name your provider under{' '}
          <code>oidc</code>, or set it to <code>none</code> to let anyone who can reach this install read
          it without signing in. Then restart, and reload this page.
        </p>
        <p className="first-run-doc">
          <a href={signingInDocUrl(VERSION)} target="_blank" rel="noreferrer">
            How to configure sign-in
          </a>
          {VERSION !== null && <span className="first-run-version"> · Nodqora {VERSION}</span>}
        </p>
      </div>
    </div>
  )
}

/**
 * ADR-0177 §4: what replaces the shell between a `401` and the page unloading, and the loop guard's
 * sentence in its place. **The canvas is never left on screen under either** (ADR-0175): a graph that
 * can no longer be fetched is not shown as if it could.
 */
export function SessionEnded({ renavigating }: { renavigating: boolean }) {
  return (
    <div className="app">
      <div className="first-run">
        <p className="first-run-headline">
          {renavigating ? 'Your session has ended. Signing in again…' : 'Signing in did not succeed. Reload to try again.'}
        </p>
      </div>
    </div>
  )
}
