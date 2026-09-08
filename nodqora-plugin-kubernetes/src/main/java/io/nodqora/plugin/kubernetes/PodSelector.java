// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kubernetes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A {@code pods} backing's reference, parsed: {@code <namespace>/<label selector>} (ADR-0148).
 *
 * <pre>
 * market-demo/strimzi.io/cluster=market,strimzi.io/kind=KafkaConnect
 * └─ namespace ┘└──────────────── selector, AND-ed ────────────────┘
 * </pre>
 *
 * <p><b>Split on the first slash and never on any other.</b> A label key is routinely a DNS
 * subdomain — {@code strimzi.io/cluster}, {@code app.kubernetes.io/name} — so slashes inside the
 * selector are the normal case rather than the awkward one, and the namespace is the only segment
 * that can be delimited unambiguously.
 *
 * <p><b>Equality only, AND-ed</b> — the {@code kubectl -l} shorthand, and exactly the semantics
 * ADR-0030 already applies to a Service's {@code spec.selector}: every entry must be present on the
 * pod's labels. Kubernetes' set-based operators ({@code !=}, {@code in}, {@code notin}, existence)
 * are deliberately absent. Matching runs here rather than at the API server, because the plugin
 * lists a whole namespace per poll (ADR-0012, ADR-0099) and a selector that reached the server would
 * make the recorded seam depend on the configuration being replayed.
 *
 * <p><b>Keys and values are checked against Kubernetes' own label charset</b>, which is what turns
 * {@code app!=api} into a rejected reference rather than into a key called {@code app!} that matches
 * nothing. Without the check, reaching for an operator the grammar does not have would cost a
 * silently unobserved node; with it, it costs a line in the log naming the string. The one legal
 * thing it refuses is an empty label value: expressible in Kubernetes, effectively unused as a
 * selector, and indistinguishable here from a truncated reference.
 *
 * <p>A reference that does not parse yields nothing rather than a selector matching everything. An
 * empty selector would be true of every pod in the namespace, so the safe direction here is the
 * strict one: no selector, no observation, and a named line in the log.
 */
record PodSelector(String namespace, Map<String, String> labels) {

    /** A DNS-subdomain prefix and a name, per Kubernetes' label syntax. */
    private static final Pattern KEY =
            Pattern.compile("(?:[a-z0-9]([-a-z0-9.]*[a-z0-9])?/)?[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?");

    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?");

    PodSelector {
        Objects.requireNonNull(namespace, "namespace");
        labels = Map.copyOf(labels);
    }

    static Optional<PodSelector> parse(String reference) {
        int slash = reference == null ? -1 : reference.indexOf('/');
        if (slash <= 0 || slash == reference.length() - 1) {
            return Optional.empty();
        }
        Map<String, String> labels = new LinkedHashMap<>();
        for (String term : reference.substring(slash + 1).split(",", -1)) {
            int equals = term.indexOf('=');
            if (equals < 0) {
                return Optional.empty();
            }
            String key = term.substring(0, equals).trim();
            String value = term.substring(equals + 1).trim();
            if (!KEY.matcher(key).matches() || !VALUE.matcher(value).matches()) {
                return Optional.empty();
            }
            labels.put(key, value);
        }
        return Optional.of(new PodSelector(reference.substring(0, slash), labels));
    }

    /** Namespace first: a selector is only ever asked about pods, and pods are namespaced. */
    boolean selects(ObservedPod pod) {
        return namespace.equals(pod.namespace())
                && labels.entrySet().stream()
                        .allMatch(entry -> entry.getValue().equals(pod.labels().get(entry.getKey())));
    }
}
