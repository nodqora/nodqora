// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kubernetes;

/**
 * The plugin's <b>outbound-client interface</b> — the one seam ADR-0099 records at, for this plugin
 * and, in the same shape, for {@code kafka} and {@code connect}.
 *
 * <p>Two implementations exist and there is never a third: the fabric8 one that talks to a cluster,
 * and the recording that test fixtures replay. There is no WireMock, no testcontainers and no
 * envtest in the MVP, and the accepted cost is stated plainly in ADR-0099 — <b>nothing here proves
 * the product can talk to a real cluster</b>. First contact is a known, bounded, manual step.
 *
 * <p>Config arrives as an argument rather than being held, because a plugin and everything under it
 * is a stateless thread-safe singleton (ADR-0012).
 */
public interface KubernetesApi {

    NamespaceObjects list(KubernetesConfig config, String namespace);
}
