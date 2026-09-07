// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.graph;

/**
 * A team, environment-scoped and folded exactly like a node (ADR-0049) — not like the global
 * descriptors, because register-if-absent would let poll order decide a team's on-call channel.
 */
public record FoldedOwner(String key, String displayName, String channel, String onCall) {}
