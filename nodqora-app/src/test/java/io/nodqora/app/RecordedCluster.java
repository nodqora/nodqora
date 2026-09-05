package io.nodqora.app;

import io.nodqora.plugin.kubernetes.KubernetesApi;
import io.nodqora.plugin.kubernetes.RecordedKubernetesApi;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * ADR-0099's recording, substituted at the one seam it is recorded at.
 *
 * <p>The plugin, its identity resolution, its suppression, its link composition and the whole merge
 * underneath it are the real ones — only the call that would reach a cluster is replaced, and it is
 * replaced by the same {@code KubernetesApi} implementation the plugin's own tests drive. That is
 * the point of recording at the outbound-client interface rather than at an HTTP boundary: there is
 * nothing to keep in step, and no second copy of the fixture.
 */
@TestConfiguration
public class RecordedCluster {

    @Bean
    @Primary
    KubernetesApi recordedKubernetesApi() {
        return new RecordedKubernetesApi();
    }
}
