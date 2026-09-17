// SPDX-License-Identifier: Apache-2.0
/**
 * The {@code prometheus} plugin (ADR-0164, ADR-0165, ADR-0167). It depends on
 * {@code nodqora-plugin-api} and nothing else of ours (ADR-0015).
 *
 * <p>It is the one plugin that observes what it did not discover. Binding a node to its series is
 * the work of whichever plugin knows the node key, which stamps a {@code (prometheus, <recipe>,
 * <selector>)} backing; this plugin only reads what it is handed, through a recipe it owns, and
 * answers with {@code rate} and {@code latency} and never a vote.
 */
package io.nodqora.plugin.prometheus;
