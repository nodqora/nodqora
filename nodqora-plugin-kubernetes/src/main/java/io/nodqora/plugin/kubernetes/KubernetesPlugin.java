package io.nodqora.plugin.kubernetes;

import io.nodqora.plugin.api.DiscoveryCapability;
import io.nodqora.plugin.api.DiscoveryRequest;
import io.nodqora.plugin.api.DiscoveryResult;
import org.springframework.stereotype.Component;

/**
 * The {@code kubernetes} plugin: id {@code kubernetes}, declaring Discovery and — in this slice —
 * not Health. Health is ADR-0034's, and it arrives in slice 3.
 *
 * <p>A capability is present <em>iff</em> the bean implements its interface (ADR-0010), so the
 * plugin's nodes sit at {@code UNKNOWN} until that interface is added here. That is the honest
 * answer rather than a placeholder: nothing has observed them yet.
 */
@Component
public class KubernetesPlugin implements DiscoveryCapability<KubernetesConfig> {

    private final KubernetesDiscovery discovery;

    public KubernetesPlugin(KubernetesApi api) {
        this.discovery = new KubernetesDiscovery(api);
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
}
