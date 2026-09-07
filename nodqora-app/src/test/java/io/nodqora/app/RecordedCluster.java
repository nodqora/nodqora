// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import io.nodqora.plugin.connect.ConnectApi;
import io.nodqora.plugin.connect.ConnectConfig;
import io.nodqora.plugin.connect.ConnectorInfo;
import io.nodqora.plugin.connect.ConnectorStatus;
import io.nodqora.plugin.connect.RecordedConnectApi;
import io.nodqora.plugin.kafka.KafkaApi;
import io.nodqora.plugin.kafka.RecordedKafkaApi;
import io.nodqora.plugin.kubernetes.KubernetesApi;
import io.nodqora.plugin.kubernetes.RecordedKubernetesApi;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * ADR-0099's recordings, substituted at the one seam each is recorded at.
 *
 * <p>The plugins, their identity resolution, their suppression and prefix scopes, their link
 * composition, the whole merge underneath them and their health normalization are the real ones.
 * Only the calls that would reach a cluster are replaced, and each is replaced by the same
 * implementation that plugin's own tests drive. That is the point of recording at the
 * outbound-client interface rather than at an HTTP boundary: there is nothing to keep in step, and
 * no second copy of the fixture.
 *
 * <p><b>Three seams, one shape.</b> The uniformity is ADR-0099's claim rather than a coincidence: a
 * plugin returns a full stateless snapshot per poll (ADR-0012), so its entire dependency on the
 * outside world is one call returning one set of objects, and recording that is recording the thing
 * the ADR says it is.
 *
 * <p>One seam serves both capabilities in every case. Discovery and Health ask the same recording
 * different questions — what exists, and how it is doing — so §8's scenarios are expressed by
 * changing a number or a state in the recording rather than by stubbing a health method, and every
 * layer between the objects and {@code /state} is exercised on the way.
 */
@TestConfiguration
public class RecordedCluster {

    /**
     * §8's scenario, defaulting to baseline. A property rather than a second configuration class so
     * that a scenario test differs from an ordinary one by one line and inherits everything else.
     *
     * <p>The same property drives all three, because a scenario is a statement about the pipeline
     * rather than about one technology: the incident is a crash-looping workload <em>and</em> the
     * lag behind it <em>and</em> the two sinks somebody paused, and no one recording could hold it.
     */
    @Bean
    @Primary
    KubernetesApi recordedKubernetesApi(@Value("${nodqora.test.scenario:baseline}") String scenario) {
        return RecordedKubernetesApi.scenario(scenario);
    }

    @Bean
    ClusterReachability clusterReachability() {
        return new ClusterReachability();
    }

    /**
     * ADR-0026's second fact, given a seam: the recording says what the cluster holds, and
     * {@link ClusterReachability} says whether we can ask it. Both delegates are real
     * {@code RecordedConnectApi} instances — the dark one simply has no recording directory, so it
     * takes the same {@code orElseThrow} branch a missing recording already took, which is what an
     * unreachable cluster does. Nothing below the seam is stubbed, so ADR-0042's {@code FAILED}
     * branch, ADR-0046's no-op transition and ADR-0086's header write all run for real.
     */
    @Bean
    @Primary
    ConnectApi recordedConnectApi(
            @Value("${nodqora.test.scenario:baseline}") String scenario, ClusterReachability reachability) {
        ConnectApi answering = RecordedConnectApi.scenario(scenario);
        ConnectApi dark = RecordedConnectApi.unreachable();
        return new ConnectApi() {

            @Override
            public List<ConnectorInfo> connectors(ConnectConfig config) {
                return cluster().connectors(config);
            }

            @Override
            public List<ConnectorStatus> statuses(ConnectConfig config) {
                return cluster().statuses(config);
            }

            private ConnectApi cluster() {
                return reachability.canReach("connect") ? answering : dark;
            }
        };
    }

    @Bean
    @Primary
    KafkaApi recordedKafkaApi(@Value("${nodqora.test.scenario:baseline}") String scenario) {
        return RecordedKafkaApi.scenario(scenario);
    }
}
