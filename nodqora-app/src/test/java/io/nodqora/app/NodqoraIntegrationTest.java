package io.nodqora.app;

import io.nodqora.core.discovery.DiscoveryEngine;
import io.nodqora.core.discovery.DiscoveryLoop;
import io.nodqora.core.health.HealthEngine;
import io.nodqora.core.health.HealthLoop;
import io.nodqora.core.startup.ConfigMirrorReconciler;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The whole slice, against a real PostgreSQL.
 *
 * <p>The schema is not portable and is not meant to be: it leans on {@code jsonb}, on partial unique
 * indexes over {@code lower(btrim(...))}, and on {@code SELECT ... FOR UPDATE} as the fold's
 * serialization point. Testing it against anything else would test a different system.
 *
 * <p>ADR-0099's "no testcontainers in the MVP" rules on how <em>plugin</em> inputs are obtained —
 * Kubernetes, Kafka and Connect are recorded at each plugin's own outbound-client interface, which
 * {@link RecordedCluster} substitutes. Our own database is not a plugin input, and the {@code yaml}
 * half records nothing because it reads a directory that is itself the product's format and doubles
 * as the demo topology.
 *
 * <p>Neither loop is ever started here; tests drive {@link DiscoveryEngine} and {@link HealthEngine}
 * directly, so a poll happens when a test says so rather than on a five-minute or thirty-second
 * timer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RecordedCluster.class)
public abstract class NodqoraIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected DiscoveryEngine discovery;

    @Autowired
    protected HealthEngine health;

    @Autowired
    protected ConfigMirrorReconciler reconciler;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private DiscoveryLoop discoveryLoop;

    @Autowired
    private HealthLoop healthLoop;

    /**
     * Every test starts from an empty store and an empty graph, then polls both halves. Truncating
     * the config mirrors and re-reconciling is the honest reset: it is exactly what a fresh
     * deployment does, and ADR-0074's cascade is what makes one statement enough.
     *
     * <p>Discovery first, and not as a convention: health contributions are keyed by {@code node.id}
     * (ADR-0072), so an observation recorded before the nodes exist has nothing to attach to and is
     * dropped — correctly, and invisibly. The startup sequence orders the two the same way.
     */
    @BeforeEach
    void resetAndPoll() {
        // Stop the timers the startup sequence began. Spring caches one context per scenario and
        // they all share this database, so a live thirty-second health loop would have one
        // scenario's context writing contributions another scenario's test is asserting against — a
        // flake that reproduces only when the suite is slow. Closing them here rather than adding a
        // configuration key keeps the shipped config surface to the two intervals ADR-0103 fixed.
        discoveryLoop.close();
        healthLoop.close();

        jdbc.execute("truncate table environment, plugin cascade");
        reconciler.reconcile();
        discovery.pollEveryEnvironment();
        health.observeEveryEnvironment();
    }
}
