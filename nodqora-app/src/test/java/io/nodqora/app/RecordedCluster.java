package io.nodqora.app;

import io.nodqora.plugin.kubernetes.KubernetesApi;
import io.nodqora.plugin.kubernetes.RecordedKubernetesApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * ADR-0099's recording, substituted at the one seam it is recorded at.
 *
 * <p>The plugin, its identity resolution, its suppression, its link composition, the whole merge
 * underneath it and — from this slice — its health normalization are the real ones. Only the call
 * that would reach a cluster is replaced, and it is replaced by the same {@code KubernetesApi}
 * implementation the plugin's own tests drive. That is the point of recording at the outbound-client
 * interface rather than at an HTTP boundary: there is nothing to keep in step, and no second copy of
 * the fixture.
 *
 * <p>One seam serves both capabilities. Discovery and Health ask the same {@code list} call different
 * questions — what exists, and how much of it is ready — so the incident scenario is expressed by
 * changing a number in the recording rather than by stubbing a health method, and every layer between
 * the objects and {@code /state} is exercised on the way.
 */
@TestConfiguration
public class RecordedCluster {

    /**
     * §8's scenario, defaulting to baseline. A property rather than a second configuration class so
     * that a scenario test differs from an ordinary one by one line and inherits everything else.
     */
    @Bean
    @Primary
    KubernetesApi recordedKubernetesApi(@Value("${nodqora.test.scenario:baseline}") String scenario) {
        return RecordedKubernetesApi.scenario(scenario);
    }
}
