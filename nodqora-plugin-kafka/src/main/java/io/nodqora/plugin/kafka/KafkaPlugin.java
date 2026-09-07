// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kafka;

import io.nodqora.plugin.api.DiscoveryCapability;
import io.nodqora.plugin.api.DiscoveryRequest;
import io.nodqora.plugin.api.DiscoveryResult;
import io.nodqora.plugin.api.HealthCapability;
import org.springframework.stereotype.Component;

/**
 * The {@code kafka} plugin: id {@code kafka}, declaring both capabilities.
 *
 * <p>It is built after {@code connect} in slice 4, and the order is load-bearing: two of the three
 * groups this plugin reads are stamped onto their nodes by {@code connect}, so building it second
 * means the routed set is complete the first time it runs.
 *
 * <p>This is the third observer, and the one that makes ADR-0022 concrete. It reads all the lag in
 * the environment and can attribute none of it — every group reaches it through a backing another
 * plugin wrote.
 */
@Component
public class KafkaPlugin implements DiscoveryCapability<KafkaConfig>, HealthCapability<KafkaConfig> {

    private final KafkaDiscovery discovery;
    private final KafkaHealth health;

    public KafkaPlugin(KafkaApi api) {
        this.discovery = new KafkaDiscovery(api);
        this.health = new KafkaHealth(api);
    }

    @Override
    public String id() {
        return KafkaDiscovery.PLUGIN_ID;
    }

    @Override
    public String displayLabel() {
        return "Kafka";
    }

    @Override
    public Class<KafkaConfig> configType() {
        return KafkaConfig.class;
    }

    @Override
    public DiscoveryResult discover(DiscoveryRequest<KafkaConfig> request) {
        return discovery.discover(request.environmentKey(), request.config());
    }

    @Override
    public HealthResult observe(HealthRequest<KafkaConfig> request) {
        return health.observe(request.environmentKey(), request.nodes(), request.config());
    }
}
