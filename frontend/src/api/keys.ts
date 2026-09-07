// SPDX-License-Identifier: Apache-2.0
/**
 * ADR-0020: a key is stored verbatim and trimmed, but every comparison is on the case-folded form.
 *
 * The frontend needs the same folding as `Keys.folded` on the server, and it needs it in one place:
 * node keys are typed by hand in YAML and minted independently by four plugins, so a comparison that
 * quietly differs from the server's is how an edge stops finding its node, or an `ownerKey` stops
 * finding its Owner — with no error, just an empty section.
 */
export const foldKey = (key: string) => key.trim().toLowerCase()

/** Convenience for the common `a === b` case, which is otherwise easy to write half-folded. */
export const sameKey = (a: string | null | undefined, b: string | null | undefined) =>
  a != null && b != null && foldKey(a) === foldKey(b)
