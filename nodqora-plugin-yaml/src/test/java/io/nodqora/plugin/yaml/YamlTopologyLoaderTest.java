package io.nodqora.plugin.yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.DiscoveredNode;
import io.nodqora.plugin.api.DiscoveryResult;
import io.nodqora.plugin.api.OutcomeStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlTopologyLoaderTest {

    private final YamlTopologyLoader loader = new YamlTopologyLoader();

    @TempDir
    Path dir;

    private void write(String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    @Nested
    class ReadingADirectory {

        @Test
        void reads_every_yaml_file_in_the_directory_as_one_snapshot() throws IOException {
            write("a.yaml", """
                    environment: production
                    nodes:
                      - key: payments-api
                    """);
            write("b.yaml", """
                    environment: production
                    nodes:
                      - key: payments-enricher
                    """);

            DiscoveryResult result = loader.load("production", dir);

            assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.COMPLETE);
            assertThat(result.nodes()).extracting(DiscoveredNode::key)
                    .containsExactlyInAnyOrder("payments-api", "payments-enricher");
        }

        @Test
        void a_stanza_carrying_only_a_key_says_nothing_at_all() throws IOException {
            write("a.yaml", """
                    environment: production
                    nodes:
                      - key: payments-api
                        producesTo: [payments.events.raw.v1]
                    """);

            DiscoveredNode node = loader.load("production", dir).nodes().getFirst();

            // ADR-0063: null is no opinion. `kubernetes` supplies this node's type and owner.
            assertThat(node.type()).isNull();
            assertThat(node.displayName()).isNull();
            assertThat(node.ownerKey()).isNull();
            assertThat(node.links()).isEmpty();
            assertThat(node.backings()).isEmpty();
        }

        @Test
        void a_verb_target_needs_no_stanza_of_its_own() throws IOException {
            write("a.yaml", """
                    environment: production
                    nodes:
                      - key: payments-api
                        producesTo: [payments.events.raw.v1]
                    """);

            DiscoveryResult result = loader.load("production", dir);

            // ADR-0063: ordinary. The fold materializes a stub at the endpoint (ADR-0048).
            assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.COMPLETE);
            assertThat(result.nodes()).extracting(DiscoveredNode::key).containsExactly("payments-api");
            assertThat(result.edges()).hasSize(1);
        }

        @Test
        void owners_and_descriptors_ride_in_the_same_snapshot() throws IOException {
            write("a.yaml", """
                    environment: production
                    owners:
                      - key: data-platform
                        displayName: Data Platform
                        channel: "#data-platform"
                        onCall: Data Platform SRE
                    types:
                      - type: iceberg-table
                        label: Iceberg Table
                        category: datastore
                        icon: table
                    nodes:
                      - key: analytics.payments_events
                        owner: data-platform
                    """);

            DiscoveryResult result = loader.load("production", dir);

            assertThat(result.owners()).singleElement()
                    .satisfies(owner -> {
                        assertThat(owner.key()).isEqualTo("data-platform");
                        assertThat(owner.channel()).isEqualTo("#data-platform");
                    });
            assertThat(result.descriptors()).singleElement()
                    .satisfies(descriptor -> {
                        assertThat(descriptor.type()).isEqualTo("iceberg-table");
                        assertThat(descriptor.source()).isEqualTo("yaml");
                    });
        }

        @Test
        void consumer_groups_become_kafka_consumer_group_backings() throws IOException {
            write("a.yaml", """
                    environment: production
                    nodes:
                      - key: payments-enricher
                        consumerGroups: [enrich-consumer-prod]
                    """);

            // ADR-0013, ADR-0022: `plugin` names the technology domain the object belongs to,
            // not the plugin that emitted the backing. This is what routes lag to this node.
            assertThat(loader.load("production", dir).nodes().getFirst().backings())
                    .containsExactly(new Backing("kafka", "consumer-group", "enrich-consumer-prod"));
        }
    }

    @Nested
    class Edges {

        @Test
        void a_forward_verb_stores_the_subject_as_the_producer() throws IOException {
            write("a.yaml", """
                    environment: production
                    nodes:
                      - key: payments-api
                        producesTo: [payments.events.raw.v1]
                      - key: stripe-webhooks
                        calls: [payments-api]
                      - key: payments-es-sink
                        writesTo: [payments-events-v1]
                    """);

            assertThat(loader.load("production", dir).edges())
                    .extracting("fromKey", "toKey", "relation")
                    .containsExactlyInAnyOrder(
                            tuple("payments-api", "payments.events.raw.v1", "PRODUCES_TO"),
                            tuple("stripe-webhooks", "payments-api", "CALLS"),
                            tuple("payments-es-sink", "payments-events-v1", "WRITES_TO"));
        }

        @Test
        void a_reversed_verb_stores_the_target_as_the_producer() throws IOException {
            write("a.yaml", """
                    environment: production
                    nodes:
                      - key: payments-enricher
                        consumesFrom: [payments.events.raw.v1]
                      - key: payments-es-sink
                        sourcesFrom: [payments.events.enriched.v1]
                      - key: trino-analytics
                        queries: [analytics.payments_events]
                    """);

            // ADR-0002: from -> to is always the direction data flows. The author wrote the
            // consumer as the subject; the loader applied the orientation.
            assertThat(loader.load("production", dir).edges())
                    .extracting("fromKey", "toKey", "relation")
                    .containsExactlyInAnyOrder(
                            tuple("payments.events.raw.v1", "payments-enricher", "CONSUMES_FROM"),
                            tuple("payments.events.enriched.v1", "payments-es-sink", "SOURCES_FROM"),
                            tuple("analytics.payments_events", "trino-analytics", "QUERIES"));
        }
    }

    @Nested
    class TheEnvironmentIsTheUnitOfFailure {

        @Test
        void a_file_that_will_not_parse_fails_the_whole_directory() throws IOException {
            write("good.yaml", """
                    environment: production
                    nodes:
                      - key: payments-api
                    """);
            write("broken.yaml", "environment: production\nnodes:\n  - key: [unclosed\n");

            DiscoveryResult result = loader.load("production", dir);

            assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.FAILED);
            assertThat(result.outcome().reasons()).singleElement().asString().contains("broken.yaml");
            assertThat(result.nodes()).isEmpty();
        }

        @Test
        void an_unknown_top_level_key_fails_the_directory() throws IOException {
            write("a.yaml", """
                    environment: production
                    relations: []
                    nodes:
                      - key: payments-api
                    """);

            assertThat(loader.load("production", dir).outcome())
                    .satisfies(outcome -> {
                        assertThat(outcome.status()).isEqualTo(OutcomeStatus.FAILED);
                        assertThat(outcome.reasons().getFirst()).contains("a.yaml").contains("relations");
                    });
        }

        @Test
        void an_unknown_node_key_fails_the_directory() throws IOException {
            write("a.yaml", """
                    environment: production
                    nodes:
                      - key: payments-api
                        metadata: { anything: here }
                    """);

            assertThat(loader.load("production", dir).outcome())
                    .satisfies(outcome -> {
                        assertThat(outcome.status()).isEqualTo(OutcomeStatus.FAILED);
                        assertThat(outcome.reasons().getFirst()).contains("metadata");
                    });
        }

        @Test
        void an_environment_that_disagrees_with_the_directory_fails() throws IOException {
            write("half-edited-copy.yaml", """
                    environment: production
                    nodes:
                      - key: payments-api
                    """);

            // ADR-0061: staging is written by copying production, so this is the characteristic
            // failure of the layout and the guard sits where the mistake is made.
            assertThat(loader.load("staging", dir).outcome())
                    .satisfies(outcome -> {
                        assertThat(outcome.status()).isEqualTo(OutcomeStatus.FAILED);
                        assertThat(outcome.reasons().getFirst())
                                .contains("half-edited-copy.yaml")
                                .contains("production")
                                .contains("staging");
                    });
        }

        @Test
        void a_missing_environment_declaration_fails() throws IOException {
            write("a.yaml", """
                    nodes:
                      - key: payments-api
                    """);

            assertThat(loader.load("production", dir).outcome().status()).isEqualTo(OutcomeStatus.FAILED);
        }

        @Test
        void a_key_declared_twice_across_two_files_fails_and_names_both() throws IOException {
            write("payments-platform.yaml", """
                    environment: production
                    nodes:
                      - key: payments-api
                    """);
            write("data-platform.yaml", """
                    environment: production
                    nodes:
                      - key: Payments-API
                    """);

            // ADR-0064: a contest here is always a bug — files have no creationTimestamp, and
            // ordering by filename would let renaming a file change a node's type. Compared
            // case-folded, per ADR-0020.
            assertThat(loader.load("production", dir).outcome())
                    .satisfies(outcome -> {
                        assertThat(outcome.status()).isEqualTo(OutcomeStatus.FAILED);
                        assertThat(outcome.reasons().getFirst())
                                .contains("payments-platform.yaml")
                                .contains("data-platform.yaml");
                    });
        }

        @Test
        void a_duplicate_key_inside_one_mapping_fails() throws IOException {
            write("a.yaml", """
                    environment: production
                    nodes:
                      - key: payments-api
                        type: service
                        type: worker
                    """);

            assertThat(loader.load("production", dir).outcome().status()).isEqualTo(OutcomeStatus.FAILED);
        }

        @Test
        void a_missing_directory_fails() {
            assertThat(loader.load("production", dir.resolve("absent")).outcome().status())
                    .isEqualTo(OutcomeStatus.FAILED);
        }

        @Test
        void an_owner_declared_twice_fails() throws IOException {
            write("a.yaml", """
                    environment: production
                    owners:
                      - key: data-platform
                    nodes:
                      - key: n
                    """);
            write("b.yaml", """
                    environment: production
                    owners:
                      - key: Data-Platform
                    """);

            assertThat(loader.load("production", dir).outcome().status()).isEqualTo(OutcomeStatus.FAILED);
        }
    }

    @Nested
    class TheZeroOutputGuard {

        @Test
        void a_directory_that_parses_but_holds_no_node_stanza_is_partial() throws IOException {
            write("owners-only.yaml", """
                    environment: production
                    owners:
                      - key: data-platform
                        displayName: Data Platform
                    """);

            // ADR-0047, ADR-0064: an empty scope, not an emptied topology. PARTIAL deletes nothing.
            DiscoveryResult result = loader.load("production", dir);

            assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.PARTIAL);
            assertThat(result.owners()).hasSize(1);
        }

        @Test
        void an_empty_directory_is_partial() {
            assertThat(loader.load("production", dir).outcome().status()).isEqualTo(OutcomeStatus.PARTIAL);
        }
    }
}
