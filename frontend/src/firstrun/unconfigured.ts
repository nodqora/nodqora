// SPDX-License-Identifier: Apache-2.0
import type { Meta } from '../api/types'

/**
 * ADR-0156: **an install whose roster is empty renders a first-run screen that replaces the shell,
 * on every route.**
 *
 * ADR-0152 makes the image ship zero `nodqora.environments` and keeps the process booting anyway,
 * so a stranger's very first screen is served by an install that has nothing to show. Nothing in
 * the frontend had a branch for it: ADR-0093's redirect has no `environments[0]` to reach,
 * ADR-0087's three empty states all presuppose an environment to name, and the render fell through
 * to `Reading the graph…` for a fetch that is never issued.
 *
 * **The gate is `meta !== null`, and that is not a detail.** A server that is unreachable and a
 * server that is unconfigured both leave the roster unusable, and firing on the wrong one tells a
 * reader *"No environments are configured"* — a confident false statement about their config made
 * from no evidence at all. It is ADR-0085's own argument, *absence is already load-bearing and
 * already means something else*, one level above where ADR-0085 put it. A failed `/api/meta` keeps
 * the existing error path; only a **known**-empty roster reaches the screen.
 */
export const isFirstRun = (meta: Meta | null): boolean => meta !== null && meta.environments.length === 0

/**
 * ADR-0156: the version this bundle was built from, baked in at build time.
 *
 * `/api/meta` carries `environments`, `plugins` and `refresh` and no version, so this is the first
 * place the running version is expressible in the UI. Putting it on the wire is the better
 * architecture and was rejected as the wrong ticket — it is a property of the running server rather
 * than of the bundle, and moving it later changes this screen not at all.
 *
 * A working tree has no build argument and falls back to `main`, which is correct for a working
 * tree and wrong only in the case the argument exists to fix.
 */
export const VERSION: string | null = __NODQORA_VERSION__

/**
 * ADR-0156: **one link out, pinned to the tag this build was cut from.**
 *
 * ADR-0153 says a minor version may want a config edit, so an unpinned link would — the moment the
 * next minor lands — teach this reader a config grammar their install does not have, and they
 * cannot detect it, because they are on this screen precisely for not yet knowing how the thing is
 * configured. That is the failure invisible to the only person harmed, which ADR-0155 refused when
 * it verified GHCR visibility rather than assuming it.
 *
 * The target **exists today**, and that is a requirement rather than a convenience: a 404 from the
 * first-run screen is the worst failure available on this route. Whatever the install documentation
 * becomes, `docs/running-against-your-own-cluster.md` keeps answering *"how do I declare an
 * environment"* at that path, or the same commit that moves it updates this constant.
 */
export const CONFIGURING_DOC = 'docs/running-against-your-own-cluster.md'

export function configuringDocUrl(version: string | null): string {
  // A snapshot is not a release, so there is no tag to point at and `main` is the only honest ref.
  // ADR-0143 makes a published version immutable; `v0.1.0-SNAPSHOT` names nothing that will ever
  // exist, and a link to nothing is the 404 this constant exists to avoid.
  const released = version !== null && !version.endsWith('-SNAPSHOT')
  const ref = released ? `v${version}` : 'main'
  return `https://github.com/fredskor/nodqora/blob/${ref}/${CONFIGURING_DOC}`
}
