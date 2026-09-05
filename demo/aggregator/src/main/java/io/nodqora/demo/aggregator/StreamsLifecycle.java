package io.nodqora.demo.aggregator;

import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.StreamsBuilderFactoryBeanConfigurer;

/**
 * A Streams client that has died should take the process with it.
 *
 * <p>Without this the JVM stays up with a dead topology: the pod keeps its readiness gate closed
 * but never restarts, which is the worst of both worlds — Kubernetes reports one ready replica
 * fewer forever, and Nodqora reports {@code DEGRADED} for something a restart would have fixed.
 */
@Configuration
public class StreamsLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StreamsLifecycle.class);

    @Bean
    public StreamsBuilderFactoryBeanConfigurer exitOnStreamsFailure(ConfigurableApplicationContext context) {
        return factoryBean -> {
            factoryBean.setStreamsUncaughtExceptionHandler(throwable -> {
                log.error("Kafka Streams thread failed; shutting the client down", throwable);
                return StreamThreadExceptionResponse.SHUTDOWN_CLIENT;
            });
            factoryBean.setStateListener((newState, oldState) -> {
                if (newState == KafkaStreams.State.ERROR) {
                    log.error("Kafka Streams entered ERROR; exiting so Kubernetes restarts the pod");
                    // Off the Streams thread: SpringApplication.exit closes the context, which
                    // would otherwise deadlock waiting for the thread calling it.
                    new Thread(() -> System.exit(SpringApplication.exit(context, () -> 1)), "streams-exit").start();
                }
            });
        };
    }
}
