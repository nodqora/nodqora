package io.nodqora.plugin.kubernetes;

import io.nodqora.plugin.api.DiscoveryCapability;
import io.nodqora.plugin.api.DiscoveryRequest;
import io.nodqora.plugin.api.DiscoveryResult;
import io.nodqora.plugin.api.HealthCapability;
import org.springframework.stereotype.Component;

/**
 * The {@code kubernetes} plugin: id {@code kubernetes}, declaring <b>both</b> capabilities.
 *
 * <p>A capability is present <em>iff</em> the bean implements its interface (ADR-0010) — that is the
 * whole of capability negotiation, and adding {@code HealthCapability} to this line is the entire
 * act of turning this plugin into an observer. Everything downstream follows from it: the config
 * roster on {@code /state} gains a row, the fast loop starts routing nodes here, and the canvas goes
 * coloured for the first time.
 *
 * <p>The two halves share the {@link KubernetesApi} seam and nothing else. They run on different
 * cadences against different questions — five minutes of "what exists" and thirty seconds of "how is
 * it doing" — and ADR-0003's wall is exactly that boundary.
 */
@Component
public class KubernetesPlugin implements DiscoveryCapability<KubernetesConfig>, HealthCapability<KubernetesConfig> {

    private final KubernetesDiscovery discovery;
    private final KubernetesHealth health;

    public KubernetesPlugin(KubernetesApi api) {
        this.discovery = new KubernetesDiscovery(api);
        this.health = new KubernetesHealth(api);
    }

    @Override
    public String id() {
        return KubernetesDiscovery.PLUGIN_ID;
    }

    @Override
    public String displayLabel() {
        return "Kubernetes";
    }

    @Override
    public Class<KubernetesConfig> configType() {
        return KubernetesConfig.class;
    }

    @Override
    public DiscoveryResult discover(DiscoveryRequest<KubernetesConfig> request) {
        return discovery.discover(request.environmentKey(), request.config());
    }

    @Override
    public HealthResult observe(HealthRequest<KubernetesConfig> request) {
        return health.observe(request.environmentKey(), request.nodes(), request.config());
    }
}
