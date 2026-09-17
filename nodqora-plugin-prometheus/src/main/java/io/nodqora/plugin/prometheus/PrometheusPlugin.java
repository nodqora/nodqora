// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.prometheus;

import io.nodqora.plugin.api.HealthCapability;
import org.springframework.stereotype.Component;

/**
 * The {@code prometheus} plugin: id {@code prometheus}, and the first to declare Health alone —
 * {@code yaml}'s mirror (ADR-0164).
 *
 * <p>It discovers nothing and names no node. It is handed the nodes someone else stamped a
 * {@code prometheus} backing onto, and gives them numbers.
 */
@Component
public class PrometheusPlugin implements HealthCapability<PrometheusConfig> {

    private final PrometheusHealth health;

    public PrometheusPlugin(PrometheusApi api) {
        this.health = new PrometheusHealth(api);
    }

    @Override
    public String id() {
        return PrometheusHealth.PLUGIN_ID;
    }

    @Override
    public String displayLabel() {
        return "Prometheus";
    }

    @Override
    public Class<PrometheusConfig> configType() {
        return PrometheusConfig.class;
    }

    @Override
    public HealthResult observe(HealthRequest<PrometheusConfig> request) {
        return health.observe(request.environmentKey(), request.nodes(), request.config());
    }
}
