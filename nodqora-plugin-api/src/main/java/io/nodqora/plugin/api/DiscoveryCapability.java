// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.api;

/**
 * Produces topology (ADR-0010). One call per {@code (plugin, environment)} pair, returning a full
 * snapshot of that plugin's scope (ADR-0012).
 *
 * <p>An enumerated scope unit that yields zero nodes must be reported {@code PARTIAL}: only the
 * plugin can tell an empty scope from an empty result (ADR-0047).
 */
public interface DiscoveryCapability<C> extends Plugin<C> {

    DiscoveryResult discover(DiscoveryRequest<C> request);
}
