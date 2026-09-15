// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core;

import io.nodqora.core.config.BoundConfiguration;
import io.nodqora.core.config.NodqoraProperties;
import io.nodqora.core.fold.GraphFold;
import io.nodqora.core.fold.StateFold;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Wiring for the core. It names no plugin, and nothing here knows a plugin's shape (ADR-0015). */
@Configuration
@EnableTransactionManagement
public class CoreConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** ADR-0165: bound by hand, so Spring's placeholders never touch a secret reference. */
    @Bean
    public NodqoraProperties nodqoraProperties(Environment environment) {
        return NodqoraProperties.from(environment);
    }

    @Bean
    public GraphFold graphFold(BoundConfiguration configuration) {
        return new GraphFold(configuration.order());
    }

    /**
     * The two folds take the same {@link io.nodqora.core.registry.PluginOrder} and use different
     * halves of it: the graph fold settles a contested scalar by <em>precedence</em>, the state fold
     * joins {@code rawSignal} in <em>registry</em> order (ADR-0028, ADR-0044). One list would not do,
     * which is why the record carries both.
     */
    @Bean
    public StateFold stateFold(BoundConfiguration configuration) {
        return new StateFold(configuration.order());
    }
}
