package io.nodqora.core;

import io.nodqora.core.config.BoundConfiguration;
import io.nodqora.core.config.NodqoraProperties;
import io.nodqora.core.fold.GraphFold;
import io.nodqora.core.fold.StateFold;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Wiring for the core. It names no plugin, and nothing here knows a plugin's shape (ADR-0015). */
@Configuration
@EnableConfigurationProperties(NodqoraProperties.class)
@EnableTransactionManagement
public class CoreConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
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
