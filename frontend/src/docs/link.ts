// SPDX-License-Identifier: Apache-2.0

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
 * ADR-0156: **a link into the repository's documentation is pinned to the tag this build was cut
 * from.**
 *
 * ADR-0153 says a minor version may want a config edit, so an unpinned link would teach a reader a
 * grammar their install does not have, and they cannot detect it. Pinning also makes ADR-0161's gate
 * checkable from here: the document and the constant naming it are in the same tree, so a test that
 * reads the file is a test that the link resolves at the tag.
 */
export function docUrl(path: string, version: string | null): string {
  // A snapshot is not a release, so there is no tag to point at and `main` is the only honest ref.
  // ADR-0143 makes a published version immutable; `v0.1.0-SNAPSHOT` names nothing that will ever
  // exist, and a link to nothing is the 404 this function exists to avoid.
  const released = version !== null && !version.endsWith('-SNAPSHOT')
  const ref = released ? `v${version}` : 'main'
  return `https://github.com/nodqora/nodqora/blob/${ref}/${path}`
}
