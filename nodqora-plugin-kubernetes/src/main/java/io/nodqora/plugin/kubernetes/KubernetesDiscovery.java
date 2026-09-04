package io.nodqora.plugin.kubernetes;

import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.DiscoveredNode;
import io.nodqora.plugin.api.DiscoveryResult;
import io.nodqora.plugin.api.Link;
import io.nodqora.plugin.api.Outcome;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SequencedSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One environment's Kubernetes snapshot: list the enumerated namespaces, subtract what is
 * suppressed, resolve identity, and emit nodes.
 *
 * <p>Three things this plugin deliberately does <em>not</em> do:
 *
 * <ul>
 *   <li><b>No edges at all</b> (ADR-0033). Selector matching attaches Service and Ingress backings;
 *       it is not an edge, because a Service and its Deployment are backings of the same node.
 *       Label and service-name inference would have to assert guesses as facts, and ADR-0009 left
 *       nowhere to record that an edge is a guess.
 *   <li><b>No {@code displayName}</b> (ADR-0034) and <b>no kind-derived {@code type}</b>
 *       (ADR-0091). Every scalar this plugin emits is annotated or {@code null}: a kind is a
 *       deployment mechanism, not a component role, and a guessed default would manufacture a merge
 *       conflict with YAML rather than resolve one.
 *   <li><b>No {@code TypeDescriptor}</b>. It has no label, category or icon for an annotated type,
 *       and under ADR-0080 whatever a plugin emits registers a descriptor — so guessing here would
 *       put a type nobody chose into the global set served inside {@code /graph}.
 * </ul>
 */
class KubernetesDiscovery {

    static final String PLUGIN_ID = "kubernetes";

    /**
     * ADR-0013, ADR-0022: {@code Backing.plugin} names the object's <em>technology domain</em>, not
     * its discoverer. A consumer group is a Kafka object however we came to hear of it — and this
     * annotation is how {@code enrich-consumer-prod}'s lag reaches {@code payments-enricher} at all,
     * because the plugin that can read that lag is not the one that can say whose lag it is.
     */
    private static final String CONSUMER_GROUP_DOMAIN = "kafka";

    private static final String CONSUMER_GROUP_KIND = "consumer-group";
    private static final String SERVICE_KIND = "service";
    private static final String INGRESS_KIND = "ingress";

    private static final Logger log = LoggerFactory.getLogger(KubernetesDiscovery.class);

    private final KubernetesApi api;

    KubernetesDiscovery(KubernetesApi api) {
        this.api = api;
    }

    DiscoveryResult discover(String environmentKey, KubernetesConfig config) {
        List<DiscoveredNode> nodes = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        Suppressions suppressions = new Suppressions();
        int unreachable = 0;

        for (String namespace : config.namespaces()) {
            NamespaceObjects objects;
            try {
                objects = api.list(config, namespace);
            } catch (RuntimeException e) {
                unreachable++;
                reasons.add("namespace %s could not be listed: %s".formatted(namespace, message(e)));
                continue;
            }

            List<DiscoveredNode> found = read(namespace, objects, config, suppressions);
            if (found.isEmpty()) {
                // ADR-0047: the zero-output guard is a plugin obligation, because the engine cannot
                // tell an empty scope from an empty result — and a COMPLETE empty snapshot deletes
                // every node this plugin had in the environment.
                reasons.add("namespace %s produced no node-producing workload".formatted(namespace));
            }
            nodes.addAll(found);
        }

        // ADR-0031: each poll logs a count of suppressions by route. It is what makes "why isn't my
        // service on the graph?" answerable when the answer is in one of two places.
        log.info(
                "discovery {}/{}: {} node(s), {} suppressed by annotation, {} by config deny-list",
                environmentKey,
                PLUGIN_ID,
                nodes.size(),
                suppressions.byAnnotation,
                suppressions.byConfig);

        if (unreachable == config.namespaces().size()) {
            return DiscoveryResult.failed(String.join("; ", reasons));
        }
        return new DiscoveryResult(
                nodes.stream().sorted(Comparator.comparing(node -> folded(node.key()))).toList(),
                List.of(),
                List.of(),
                List.of(),
                reasons.isEmpty() ? Outcome.complete() : Outcome.partial(reasons));
    }

    // ---------------------------------------------------------------- one namespace

    private List<DiscoveredNode> read(
            String namespace, NamespaceObjects objects, KubernetesConfig config, Suppressions suppressions) {
        List<ObservedWorkload> admitted = new ArrayList<>();
        for (ObservedWorkload workload : objects.workloads()) {
            TopologyAnnotations.unknownKeys(workload).forEach(key -> log.warn(
                    "{} {} carries unrecognised annotation '{}'; ADR-0032's vocabulary is closed, so it is ignored",
                    workload.kind().lowercased(),
                    workload.reference(),
                    key));

            // ADR-0031: default-in with explicit subtraction, two routes, either sufficient. Both
            // exist because the annotation needs an edit in a repo owned by another team — and an
            // operator will revert it — while the deny-list needs a PR against the nodqora config.
            if (TopologyAnnotations.ignores(workload)) {
                suppressions.byAnnotation++;
            } else if (config.denies(workload)) {
                suppressions.byConfig++;
            } else {
                admitted.add(workload);
                continue;
            }
            log.debug("suppressed {} {}", workload.kind().lowercased(), workload.reference());
        }

        Attachments attachments = Attachments.of(objects, admitted);
        LinkComposer links = new LinkComposer(config.links());

        // ADR-0021: an ordered exact-match cascade, first match wins, and never fuzzy. Only this
        // plugin has a problem to solve — a topic is its own key, a connector is its own key, and
        // YAML states its key outright.
        Map<String, List<ObservedWorkload>> claims = new LinkedHashMap<>();
        for (ObservedWorkload workload : admitted) {
            claims.computeIfAbsent(folded(resolvedKey(workload)), ignored -> new ArrayList<>())
                    .add(workload);
        }
        return claims.values().stream()
                .map(claimants -> node(claimants, attachments, links))
                .toList();
    }

    /** Tier 1 the {@code topology.io/node} annotation, tier 2 the object's own name. No tier 3. */
    private static String resolvedKey(ObservedWorkload workload) {
        String annotated = TopologyAnnotations.value(workload, TopologyAnnotations.NODE);
        return annotated == null ? workload.name() : annotated;
    }

    /**
     * ADR-0021, when two objects resolve to one key: one node, backings unioned so ADR-0013's health
     * routing still reaches every claimant, and scalars from the newest object by
     * {@code creationTimestamp}, ties broken by name ascending.
     *
     * <p>Determinism is the point. Processing one claimant and silently skipping the rest is
     * nondeterministic — the Kubernetes list order is not stable, so the winner would flip between
     * polls and the node's type, links and health would flap with no cause visible to anyone.
     * Newest-first is stable across polls and, during a progressive rollout, names the current
     * workload.
     */
    private DiscoveredNode node(List<ObservedWorkload> claimants, Attachments attachments, LinkComposer links) {
        List<ObservedWorkload> byRecency = claimants.stream()
                .sorted(Comparator.comparing(
                                ObservedWorkload::creationTimestamp,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ObservedWorkload::name))
                .toList();
        ObservedWorkload winner = byRecency.getFirst();

        List<Link> composed = new ArrayList<>(links.fromAnnotations(winner));
        SequencedSet<Backing> backings = new LinkedHashSet<>();
        for (ObservedWorkload claimant : byRecency) {
            backings.add(new Backing(PLUGIN_ID, claimant.kind().lowercased(), claimant.reference()));
            TopologyAnnotations.consumerGroups(claimant).forEach(group ->
                    backings.add(new Backing(CONSUMER_GROUP_DOMAIN, CONSUMER_GROUP_KIND, group)));
            backings.addAll(attachments.of(claimant));
            composed.addAll(links.fromObject(claimant));
        }

        return new DiscoveredNode(
                resolvedKey(winner).trim(),
                TopologyAnnotations.value(winner, TopologyAnnotations.TYPE),
                null,
                null,
                TopologyAnnotations.value(winner, TopologyAnnotations.OWNER),
                composed,
                List.copyOf(backings),
                contest(byRecency));
    }

    /**
     * ADR-0021: the contest is recorded under this plugin's own metadata namespace (ADR-0006) and
     * logged. It is deliberately not reported through {@code outcome}: the snapshot is complete, and
     * a {@code PARTIAL} would trip the deletion fail-safes ADR-0046 builds on failure signals.
     */
    private static Map<String, Object> contest(List<ObservedWorkload> byRecency) {
        if (byRecency.size() < 2) {
            return Map.of();
        }
        List<String> claimants = byRecency.stream()
                .map(workload -> workload.kind().lowercased() + "/" + workload.name())
                .toList();
        log.warn(
                "node key {} is claimed by {}; scalars come from {} (newest)",
                resolvedKey(byRecency.getFirst()),
                claimants,
                claimants.getFirst());
        return Map.of("contestedBy", claimants);
    }

    /**
     * Which Service and Ingress backings attach to each workload (ADR-0030):
     *
     * <pre>Ingress --(backend service name)--&gt; Service --(selector ⊇ pod labels)--&gt; workload</pre>
     *
     * <p>A Service selecting pods from two workloads backs both nodes; a Service selecting nothing
     * attaches to nothing and produces no node. Only admitted workloads are matched — a suppressed
     * object emits no node, so there is nothing for its Service to back.
     */
    private record Attachments(Map<String, List<Backing>> byWorkload) {

        static Attachments of(NamespaceObjects objects, List<ObservedWorkload> admitted) {
            Map<String, List<ObservedWorkload>> selected = new LinkedHashMap<>();
            Map<String, List<Backing>> byWorkload = new LinkedHashMap<>();
            for (ObservedService service : objects.services()) {
                List<ObservedWorkload> matched =
                        admitted.stream().filter(service::selects).toList();
                selected.put(service.name(), matched);
                matched.forEach(workload -> byWorkload
                        .computeIfAbsent(workload.reference(), ignored -> new ArrayList<>())
                        .add(new Backing(PLUGIN_ID, SERVICE_KIND, service.reference())));
            }
            for (ObservedIngress ingress : objects.ingresses()) {
                ingress.services().stream()
                        .flatMap(name -> selected.getOrDefault(name, List.of()).stream())
                        .distinct()
                        .forEach(workload -> byWorkload
                                .computeIfAbsent(workload.reference(), ignored -> new ArrayList<>())
                                .add(new Backing(PLUGIN_ID, INGRESS_KIND, ingress.reference())));
            }
            return new Attachments(byWorkload);
        }

        List<Backing> of(ObservedWorkload workload) {
            return byWorkload.getOrDefault(workload.reference(), List.of());
        }
    }

    /** ADR-0031's per-poll count, by route. Two places to look is the cost; this is what pays it. */
    private static final class Suppressions {
        private int byAnnotation;
        private int byConfig;
    }

    /** ADR-0020: keys are compared case-folded, so the plugin's own contest detection folds too. */
    private static String folded(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }

    private static String message(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
