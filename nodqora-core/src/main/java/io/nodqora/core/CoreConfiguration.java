package io.nodqora.core;

import io.nodqora.core.config.BoundConfiguration;
import io.nodqora.core.config.NodqoraProperties;
import io.nodqora.core.fold.GraphFold;
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
}
