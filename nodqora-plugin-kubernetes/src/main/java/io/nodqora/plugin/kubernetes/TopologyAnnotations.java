// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kubernetes;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ADR-0032's <b>closed</b> vocabulary of nine keys. An unknown {@code topology.io/*} key is ignored
 * and logged — never turned into a link, into metadata, or into anything else.
 *
 * <p>Closed rather than open because half the values are not URLs, so "open" could only have meant
 * "open to absolute URLs", which is the one thing an annotation must not hold. It also matches
 * ADR-0014's precedent: allow-list by name, because that is the rule that fails safe when a new key
 * appears.
 *
 * <p>The worst failure this design has is a typo'd {@code topology.io/node}: it falls silently to
 * ADR-0021's tier 2, the node takes the object's own name, and the node it should have merged into
 * is absent — fake drift under ADR-0004. The log line for unrecognised keys is the only defence and
 * cannot be more, because ADR-0021 forbids reporting a resolution problem through {@code outcome}.
 */
final class TopologyAnnotations {

    static final String PREFIX = "topology.io/";

    static final String NODE = PREFIX + "node";
    static final String TYPE = PREFIX + "type";
    static final String OWNER = PREFIX + "owner";
    static final String IGNORE = PREFIX + "ignore";
    static final String CONSUMER_GROUPS = PREFIX + "consumer-groups";
    static final String REPOSITORY = PREFIX + "repository";
    static final String RUNBOOK = PREFIX + "runbook";
    static final String DOCS = PREFIX + "docs";
    static final String GRAFANA = PREFIX + "grafana";

    static final Set<String> CLOSED =
            Set.of(NODE, TYPE, OWNER, IGNORE, CONSUMER_GROUPS, REPOSITORY, RUNBOOK, DOCS, GRAFANA);

    /**
     * Argo CD's own label, not ours (ADR-0032). Reading it explains the fixture's deliberate
     * unevenness — Argo manages {@code payments-api} and not the enricher — at zero annotation cost,
     * using a convention that already exists on every Argo-managed object.
     */
    static final String ARGOCD_INSTANCE = "argocd.argoproj.io/instance";

    private TopologyAnnotations() {}

    static String value(ObservedWorkload workload, String key) {
        String value = workload.annotations().get(key);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * ADR-0031: the opt-out is an annotation with the value {@code "true"} and nothing else.
     * Matched exactly, for the same failure-direction reason the deny-list is: a value this does
     * not recognise leaves the node on the graph, which is visible, rather than removing it, which
     * ADR-0004 reads as drift.
     */
    static boolean ignores(ObservedWorkload workload) {
        return "true".equals(value(workload, IGNORE));
    }

    /** ADR-0032: plural, because nothing says a workload consumes one topic. */
    static List<String> consumerGroups(ObservedWorkload workload) {
        String value = value(workload, CONSUMER_GROUPS);
        return value == null
                ? List.of()
                : Arrays.stream(value.split(",")).map(String::trim).filter(group -> !group.isEmpty()).toList();
    }

    /** Every {@code topology.io/*} key on the object that the vocabulary does not admit. */
    static List<String> unknownKeys(ObservedWorkload workload) {
        return workload.annotations().keySet().stream()
                .filter(key -> key.startsWith(PREFIX))
                .filter(key -> !CLOSED.contains(key))
                .sorted()
                .toList();
    }
}
