// SPDX-License-Identifier: Apache-2.0
/**
 * The {@code kafka} plugin (ADR-0036 … ADR-0040, ADR-0042). It depends on
 * {@code nodqora-plugin-api} and nothing else of ours (ADR-0015).
 *
 * <p>It is the plugin ADR-0022 was written about from the other side: it can read every consumer
 * group's lag in the environment and can attribute none of it. Every group it reads arrives as a
 * backing another plugin stamped, and every verdict it returns is about a node key it was handed.
 */
package io.nodqora.plugin.kafka;
