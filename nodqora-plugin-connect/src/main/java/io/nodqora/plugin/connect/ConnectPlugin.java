package io.nodqora.plugin.connect;

import io.nodqora.plugin.api.DiscoveryCapability;
import io.nodqora.plugin.api.DiscoveryRequest;
import io.nodqora.plugin.api.DiscoveryResult;
import io.nodqora.plugin.api.HealthCapability;
import org.springframework.stereotype.Component;

/**
 * The {@code connect} plugin: id {@code connect}, declaring both capabilities.
 *
 * <p>It is built before {@code kafka} in slice 4 and the order is load-bearing rather than
 * alphabetical. {@code connect} closes the edge set — the last two of §3's nine edges are its
 * {@code SOURCES_FROM} pair (ADR-0041) — so the graph is complete one step earlier; and it is
 * {@code connect} that emits the two Connect-generated consumer-group backings, without which
 * {@code kafka} would be routed one group instead of three.
 *
 * <p>The two halves share the {@link ConnectApi} seam and nothing else, and both of its methods are
 * one HTTP request against the same cluster asking different questions — what exists, and how it is
 * doing. ADR-0003's wall is exactly that boundary.
 */
@Component
public class ConnectPlugin implements DiscoveryCapability<ConnectConfig>, HealthCapability<ConnectConfig> {

    private final ConnectDiscovery discovery;
    private final ConnectHealth health;

    public ConnectPlugin(ConnectApi api) {
        this.discovery = new ConnectDiscovery(api);
        this.health = new ConnectHealth(api);
    }

    @Override
    public String id() {
        return ConnectDiscovery.PLUGIN_ID;
    }

    @Override
    public String displayLabel() {
        return "Kafka Connect";
    }

    @Override
    public Class<ConnectConfig> configType() {
        return ConnectConfig.class;
    }

    @Override
    public DiscoveryResult discover(DiscoveryRequest<ConnectConfig> request) {
        return discovery.discover(request.environmentKey(), request.config());
    }

    @Override
    public HealthResult observe(HealthRequest<ConnectConfig> request) {
        return health.observe(request.environmentKey(), request.nodes(), request.config());
    }
}
