package io.nodqora.plugin.connect;

import static org.assertj.core.api.Assertions.assertThat;

import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.DiscoveredEdge;
import io.nodqora.plugin.api.DiscoveredNode;
import io.nodqora.plugin.api.DiscoveryRequest;
import io.nodqora.plugin.api.DiscoveryResult;
import io.nodqora.plugin.api.Link;
import io.nodqora.plugin.api.OutcomeStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code connect} plugin's discovery half, driven through {@link ConnectApi} — the seam the
 * fixture is recorded at, and the same one {@code nodqora-app} substitutes.
 */
class ConnectDiscoveryTest {

    private static final ConnectConfig.Links LINKS = new ConnectConfig.Links(
            "https://connect-ui.acme.io/prod/{name}",
            "https://grafana.acme.io/explore?connector={name}",
            "https://connect-ui.acme.io/prod/{name}/config");

    private static final ConnectConfig.Workload WORKLOAD =
            new ConnectConfig.Workload("kubernetes", "statefulset", "payments-prod/kafka-connect");

    @Test
    void the_recorded_cluster_yields_the_fixtures_two_connectors() {
        DiscoveryResult result = discover(recorded(), config(List.of("payments-"), List.of("payments-debug-reprocessor")));

        // ADR-0090: four connectors are on the cluster and two become nodes. `orders-jdbc-source` is
        // another team's, removed by the prefix; `payments-debug-reprocessor` matches the prefix and
        // is removed by exact name, which is the escape hatch the prefix alone cannot provide.
        assertThat(result.nodes().stream().map(DiscoveredNode::key))
                .containsExactly("payments-es-sink", "payments-iceberg-sink");
        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.COMPLETE);
    }

    @Test
    void the_last_two_edges_come_from_the_topics_key_and_run_topic_to_connector() {
        DiscoveryResult result = discover(recorded(), config(List.of("payments-"), List.of("payments-debug-reprocessor")));

        // ADR-0041 and ADR-0002. `SOURCES_FROM` is REVERSED, so the stored edge runs the way the
        // data flows — topic into connector — and downstream traversal never looks an orientation up.
        assertThat(result.edges())
                .extracting(DiscoveredEdge::fromKey, DiscoveredEdge::toKey, DiscoveredEdge::relation)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "payments.events.enriched.v1", "payments-es-sink", "SOURCES_FROM"),
                        org.assertj.core.groups.Tuple.tuple(
                                "payments.events.enriched.v1", "payments-iceberg-sink", "SOURCES_FROM"));
    }

    @Test
    void neither_destination_is_inferred_and_that_is_the_accepted_cost() {
        DiscoveryResult result = discover(recorded(), config(List.of("payments-"), List.of("payments-debug-reprocessor")));

        // ADR-0041's two halves, in one assertion. `payments-events-v1` is not in the ES sink's
        // config at all — `connection.url` is the cluster endpoint — so no plugin could reach it;
        // `analytics.payments_events` *is* in the Iceberg sink's config, verbatim, as the node key,
        // and is still not read, because `iceberg.tables` is a third party's vocabulary. Reading it
        // would produce destination edges for some classes and silently none for others, so a user
        // could not tell an unsupported class from a misconfigured connector.
        assertThat(result.edges().stream().map(DiscoveredEdge::toKey))
                .doesNotContain("payments-events-v1", "analytics.payments_events");
        assertThat(result.edges().stream().map(DiscoveredEdge::fromKey))
                .doesNotContain("payments-events-v1", "analytics.payments_events");
    }

    @Test
    void a_sink_carries_three_backings_and_each_is_a_different_plugins_domain() {
        DiscoveredNode sink = node(
                discover(recorded(), config(List.of("payments-es-"), List.of())), "payments-es-sink");

        // ADR-0022 in one node. `connect` knows the key, so it is the only plugin that could stamp
        // any of these — and two of the three are on behalf of plugins that could never have found
        // them: `kubernetes` suppresses the StatefulSet from node emission (ADR-0031), and `kafka`
        // attributes nothing at all.
        assertThat(sink.backings())
                .containsExactly(
                        new Backing("connect", "connector", "payments-es-sink"),
                        new Backing("kubernetes", "statefulset", "payments-prod/kafka-connect"),
                        new Backing("kafka", "consumer-group", "connect-payments-es-sink"));
    }

    @Test
    void a_source_connector_gets_no_consumer_group_and_that_is_not_an_omission() {
        DiscoveredNode source =
                node(discover(recorded(), config(List.of("orders-"), List.of())), "orders-jdbc-source");

        // A source produces, commits no offsets, and has no consumer group to name. Inventing
        // `connect-orders-jdbc-source` would route `kafka` at a group that does not exist and leave
        // the node UNKNOWN by a path that looks like a failure.
        assertThat(source.backings()).noneMatch(backing -> backing.kind().equals("consumer-group"));
    }

    @Test
    void a_group_override_wins_over_connects_own_default() {
        ConnectApi api = api(new ConnectorInfo(
                "payments-es-sink",
                "sink",
                Map.of("topics", "payments.events.enriched.v1", "consumer.override.group.id", "es-sink-bespoke")));

        // ADR-0022: `connect` emits its own group from its config, "which is authoritative including
        // when `consumer.override.group.id` overrides the default". Reading the default only would
        // route `kafka` at a group nobody uses.
        assertThat(node(discover(api, config(List.of("payments-"), List.of())), "payments-es-sink").backings())
                .contains(new Backing("kafka", "consumer-group", "es-sink-bespoke"));
    }

    @Test
    void the_allow_list_is_three_keys_and_the_config_never_leaves_the_plugin() {
        DiscoveredNode sink = node(
                discover(recorded(), config(List.of("payments-es-"), List.of())), "payments-es-sink");

        // ADR-0038, enumerated by name. `connection.url` and `type.name` are in the recording and
        // are not here, and neither is `topics` — it became edges, and storing it twice creates two
        // places to disagree. The rule matters more than it looks: `?expand=info` returns inlined
        // secrets unmasked, so an "everything except *.password" filter would be the wrong direction.
        assertThat(sink.metadata())
                .containsOnly(
                        Map.entry("class", "io.confluent.connect.elasticsearch.ElasticsearchSinkConnector"),
                        Map.entry("type", "sink"),
                        Map.entry("tasksMax", "3"));
    }

    @Test
    void the_type_is_one_constant_and_the_plugin_registers_its_own_descriptor() {
        DiscoveryResult result = discover(recorded(), config(List.of("payments-"), List.of("payments-debug-reprocessor")));

        // ADR-0038: one type for source and sink alike, because direction is already on the edges.
        // ADR-0001: unlike ADR-0091's annotated `service`, this type is the plugin's own constant,
        // so it knows how the type should draw and guessing does not come into it.
        assertThat(result.nodes()).allMatch(node -> node.type().equals("connect-connector"));
        assertThat(result.descriptors()).singleElement().satisfies(descriptor -> {
            assertThat(descriptor.type()).isEqualTo("connect-connector");
            assertThat(descriptor.source()).isEqualTo("connect");
        });
    }

    @Test
    void display_name_and_owner_are_null_rather_than_guessed() {
        DiscoveredNode sink = node(
                discover(recorded(), config(List.of("payments-es-"), List.of())), "payments-es-sink");

        // ADR-0038, following ADR-0034: not to resolve a merge conflict with YAML but to avoid
        // manufacturing one. Connect has nowhere to record an owner, and a display name would be
        // byte-identical to the key — indistinguishable from a name somebody chose.
        assertThat(sink.displayName()).isNull();
        assertThat(sink.ownerKey()).isNull();
    }

    @Test
    void links_are_composed_per_environment_and_absent_when_no_template_is() {
        DiscoveredNode withTemplates = node(
                discover(recorded(), config(List.of("payments-es-"), List.of())), "payments-es-sink");
        DiscoveredNode without = node(
                discover(recorded(), new ConnectConfig(
                        "https://connect-prod.internal:8083",
                        null,
                        new ConnectConfig.Connectors(List.of("payments-es-"), List.of()),
                        WORKLOAD,
                        null)),
                "payments-es-sink");

        // ADR-0039: §9's three links in §9's order, and "no template configured ⇒ no link, never a
        // half-composed URL" carried over from ADR-0032 unchanged.
        assertThat(withTemplates.links())
                .extracting(Link::rel, Link::label)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("connector", "Connect UI"),
                        org.assertj.core.groups.Tuple.tuple("logs", "Logs"),
                        org.assertj.core.groups.Tuple.tuple("config", "Config"));
        assertThat(without.links()).isEmpty();
    }

    @Test
    void an_absent_workload_costs_a_backing_rather_than_failing() {
        DiscoveredNode sink = node(
                discover(recorded(), new ConnectConfig(
                        "https://connect-prod.internal:8083",
                        null,
                        new ConnectConfig.Connectors(List.of("payments-es-"), List.of()),
                        null,
                        LINKS)),
                "payments-es-sink");

        // ADR-0042: `workload` is optional because a Connect cluster not on Kubernetes — MSK
        // Connect, bare metal, Docker — is normal, and requiring it would force operators to invent
        // a reference. Absent, the connector carries two backings and loses the readiness
        // contribution; it does not lose its node.
        assertThat(sink.backings()).hasSize(2);
        assertThat(sink.backings()).noneMatch(backing -> backing.plugin().equals("kubernetes"));
    }

    @Test
    void a_prefix_matching_nothing_is_a_partial_that_names_it() {
        DiscoveryResult result = discover(recorded(), config(List.of("payments-", "billing-"), List.of()));

        // ADR-0090: a declared prefix is a declared expectation, so zero matches is either an ACL
        // failure or a wrong prefix — an ambiguity that cannot be removed, so it shouts. Naming the
        // prefix is what the cluster-level "zero connectors" reason it replaced could not do.
        assertThat(result.outcome().status()).isEqualTo(OutcomeStatus.PARTIAL);
        assertThat(result.outcome().reasons()).containsExactly("connector include prefix 'billing-' matched no connector");
        // PARTIAL rather than empty: the connectors the working prefix found still land.
        assertThat(result.nodes()).isNotEmpty();
    }

    @Test
    void a_per_connector_expansion_failure_is_partial_and_a_dead_cluster_is_failed() {
        DiscoveryResult partial = discover(
                api(
                        new ConnectorInfo("payments-es-sink", "sink", Map.of("topics", "t")),
                        new ConnectorInfo("payments-iceberg-sink", null, Map.of(), "error_code 500")),
                config(List.of("payments-"), List.of()));
        DiscoveryResult failed = discover(unreachable(), config(List.of("payments-"), List.of()));

        // ADR-0042 reports HTTP truthfully, and the split is what ADR-0046 acts on: PARTIAL keeps
        // the connectors that did answer, while FAILED carries no entries and changes nothing in the
        // store — so a dead cluster goes visibly stale rather than deleting every connector.
        assertThat(partial.outcome().status()).isEqualTo(OutcomeStatus.PARTIAL);
        assertThat(partial.nodes()).hasSize(1);
        assertThat(failed.outcome().status()).isEqualTo(OutcomeStatus.FAILED);
        assertThat(failed.nodes()).isEmpty();
    }

    @Test
    void a_connector_routing_by_regex_contributes_no_edge_rather_than_a_guessed_one() {
        DiscoveryResult result = discover(
                api(new ConnectorInfo("payments-regex-sink", "sink", Map.of("topics.regex", "payments\\..*"))),
                config(List.of("payments-"), List.of()));

        // A regex names a pattern, not a topic, and resolving it needs the cluster's topic list —
        // which is another plugin's scope (ADR-0012). ADR-0041's line holds: the node exists, and
        // the edge does not.
        assertThat(result.nodes()).hasSize(1);
        assertThat(result.edges()).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private static ConnectConfig config(List<String> include, List<String> ignore) {
        return new ConnectConfig(
                "https://connect-prod.internal:8083",
                null,
                new ConnectConfig.Connectors(include, ignore),
                WORKLOAD,
                LINKS);
    }

    private static DiscoveryResult discover(ConnectApi api, ConnectConfig config) {
        return new ConnectPlugin(api).discover(new DiscoveryRequest<>("production", config));
    }

    private static ConnectApi recorded() {
        return new RecordedConnectApi();
    }

    /** A cluster answering exactly these connectors; statuses are unused by discovery. */
    private static ConnectApi api(ConnectorInfo... connectors) {
        return new ConnectApi() {
            @Override
            public List<ConnectorInfo> connectors(ConnectConfig config) {
                return List.of(connectors);
            }

            @Override
            public List<ConnectorStatus> statuses(ConnectConfig config) {
                return List.of();
            }
        };
    }

    /** A cluster that cannot be reached at all — ADR-0042's FAILED branch, with no mock. */
    private static ConnectApi unreachable() {
        return new ConnectApi() {
            @Override
            public List<ConnectorInfo> connectors(ConnectConfig config) {
                throw new ConnectApiException("connection refused");
            }

            @Override
            public List<ConnectorStatus> statuses(ConnectConfig config) {
                throw new ConnectApiException("connection refused");
            }
        };
    }

    private static DiscoveredNode node(DiscoveryResult result, String key) {
        return result.nodes().stream()
                .filter(node -> node.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no node keyed " + key));
    }
}
