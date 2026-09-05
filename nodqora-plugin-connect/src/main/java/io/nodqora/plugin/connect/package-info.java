/**
 * The {@code connect} plugin (ADR-0036 … ADR-0042, ADR-0090). It depends on
 * {@code nodqora-plugin-api} and nothing else of ours (ADR-0015).
 *
 * <p>Its whole job is two HTTP reads and the arithmetic over them, and the one hard idea it carries
 * is ADR-0022's: the plugin that can <em>read</em> a signal is routinely not the one that can
 * <em>attribute</em> it. {@code connect} knows the node key of every connector it lists, so it is the
 * only plugin that can stamp the Connect-generated consumer group and the hosting workload onto
 * those nodes — and it does so on behalf of two other plugins, neither of which could have found
 * them.
 */
package io.nodqora.plugin.connect;
