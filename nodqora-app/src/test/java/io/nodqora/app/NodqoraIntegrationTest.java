package io.nodqora.app;

import io.nodqora.core.discovery.DiscoveryEngine;
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
 * <p>The discovery loop is never started here; tests drive {@link DiscoveryEngine} directly, so a
 * poll happens when a test says so rather than on a five-minute timer.
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
    protected ConfigMirrorReconciler reconciler;

    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * Every test starts from an empty store and an empty graph, then polls. Truncating the config
     * mirrors and re-reconciling is the honest reset: it is exactly what a fresh deployment does.
     */
    @BeforeEach
    void resetAndPoll() {
        jdbc.execute("truncate table environment, plugin cascade");
        reconciler.reconcile();
        discovery.pollEveryEnvironment();
    }
}
