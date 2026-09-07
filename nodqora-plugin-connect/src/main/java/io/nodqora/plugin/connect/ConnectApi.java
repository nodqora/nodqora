// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.connect;

import java.util.List;

/**
 * The plugin's <b>outbound-client interface</b> — the one seam ADR-0099 records at, in the same
 * shape as {@code KubernetesApi} and {@code KafkaApi}.
 *
 * <p>Two implementations exist and there is never a third: {@link HttpConnectApi}, which talks to a
 * cluster, and the recording that test fixtures replay. There is no WireMock and no testcontainers
 * in the MVP, and the accepted cost is ADR-0099's — <b>nothing here proves the product can talk to a
 * real Connect cluster</b>.
 *
 * <p><b>Both methods are reads and there will never be a third kind.</b> ADR-0042 established that
 * no read-only Connect credential exists and no per-endpoint authorization does either: whatever
 * credential Nodqora holds can delete every connector on the cluster. Connect cannot be fixed from
 * here, but Nodqora can be made provably harmless against it, which is why this interface has one
 * method per <em>question</em> rather than a general {@code request(method, path)} — and why
 * {@code GetOnlyTest} fails the build over a verb rather than a code review catching it.
 *
 * <p>Config arrives as an argument rather than being held, because a plugin and everything under it
 * is a stateless thread-safe singleton (ADR-0012).
 */
public interface ConnectApi {

    /** ADR-0042's slow loop: one {@code GET /connectors?expand=info}, every five minutes. */
    List<ConnectorInfo> connectors(ConnectConfig config);

    /** ADR-0042's fast loop: one {@code GET /connectors?expand=status}, every thirty seconds. */
    List<ConnectorStatus> statuses(ConnectConfig config);
}
