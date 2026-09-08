// SPDX-License-Identifier: Apache-2.0
import { configuringDocUrl, VERSION } from './unconfigured'

/**
 * ADR-0156: the screen an unconfigured install shows, **replacing the shell rather than emptying
 * it**.
 *
 * Every element of the top bar is scope-dependent — the switcher has nothing to switch, and the
 * search box and plugins chip are both gated on `graph` and cannot appear — so rendering the frame
 * would put an empty dropdown and three invisible controls around one sentence. `UnknownEnvironment`
 * already establishes the pattern: *the URL names no usable scope* is answered by replacing the
 * shell. This is the same category one step earlier — not "I cannot find that environment" but
 * "there are no environments".
 *
 * The headline is not new vocabulary. ADR-0087 ships *"No plugins are configured for `production`"*;
 * this is the identical sentence one level up with the scope removed, which is exactly the
 * relationship between the two states.
 *
 * **It states the route and claims nothing about compliance with it.** The fix is a file this UI
 * cannot verify, create, or check the mount of. So there is no spinner, no "waiting for
 * configuration", no countdown and no retry control: those are claims about a thing being watched,
 * and nothing is being watched. ADR-0087's *no countdown* discipline on a screen where the wait is
 * not bounded by a cadence but by a human editing a file.
 *
 * **No Enterprise mention, and ADR-0132 names this screen in its carve-out** — the highest-attention
 * pixel the product will ever have is honesty layer, not control path. **No pointer at the demo**
 * either: ADR-0152 made the reference pipeline repo-only, so *try the worked example* would route a
 * stranger into `git clone` and a JDK, which is the path this distribution exists to remove.
 */
export function FirstRun() {
  return (
    <div className="app">
      <div className="first-run">
        <p className="first-run-headline">No environments are configured.</p>
        <p className="first-run-route">
          Write an <code>application.yaml</code> declaring <code>nodqora.environments</code> into the
          config directory this container reads — <code>/app/config</code>, which the shipped{' '}
          <code>compose.yaml</code> mounts from <code>./config</code> beside it. Then restart, and
          reload this page.
        </p>
        <p className="first-run-doc">
          <a href={configuringDocUrl(VERSION)} target="_blank" rel="noreferrer">
            How to declare an environment
          </a>
          {VERSION !== null && <span className="first-run-version"> · Nodqora {VERSION}</span>}
        </p>
      </div>
    </div>
  )
}
