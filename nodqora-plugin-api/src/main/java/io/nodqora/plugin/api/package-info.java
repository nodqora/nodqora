// SPDX-License-Identifier: Apache-2.0
/**
 * The plugin contract (ADR-0015). Immutable records and interfaces only: no Spring,
 * no persistence, no dependency on {@code nodqora-core}.
 *
 * <p>ADR-0012's "core vocabulary" means the vocabulary, not the JPA objects — these
 * types are what a plugin returns, and the core folds them into its own rows.
 */
package io.nodqora.plugin.api;
