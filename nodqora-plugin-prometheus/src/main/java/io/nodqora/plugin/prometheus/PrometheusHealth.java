// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.prometheus;

import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.Health;
import io.nodqora.plugin.api.HealthCapability.HealthResult;
import io.nodqora.plugin.api.HealthCapability.ObservableNode;
import io.nodqora.plugin.api.Outcome;
import io.nodqora.plugin.api.StateContribution;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One environment's Prometheus observation: {@code rate} and {@code latency} for every node someone
 * bound, and never a vote (ADR-0165, ADR-0167).
 *
 * <p><b>Batched by recipe and label names</b> (ADR-0013). Every binding of one recipe whose selector
 * names the same labels is read by one query per key: each label is matched against the alternation
 * of the values bound to it, and {@code sum by} those labels hands each binding back its own sample.
 * The alternation can match a combination nobody bound; that sample is read and discarded, because a
 * binding only ever takes the sample whose labels equal its own pairs. A template stamps every
 * workload with the same label names, so an environment costs recipes × keys queries, not workloads
 * × recipes × keys.
 *
 * <p><b>Silence is an omission</b>: no series, an unknown recipe or an unreadable selector leaves the
 * node out rather than sending {@code UNKNOWN} with nothing. Idle series are a measurement, so
 * {@code rate 0} is sent, and the {@code NaN} mean of no work is left off.
 */
class PrometheusHealth {

    static final String PLUGIN_ID = "prometheus";

    /** ADR-0165's closed vocabulary. */
    static final String RATE = "rate";

    static final String LATENCY = "latency";

    private static final Logger log = LoggerFactory.getLogger(PrometheusHealth.class);

    private static final Pattern REGEX_META = Pattern.compile("[\\\\.+*?()|\\[\\]{}^$]");

    private final PrometheusApi api;

    PrometheusHealth(PrometheusApi api) {
        this.api = api;
    }

    private record Binding(Recipe recipe, Selector selector) {

        Group group() {
            return new Group(recipe, selector.labels());
        }
    }

    private record Group(Recipe recipe, List<String> labels) {}

    /** What one group's two queries said, per bound label set; {@code null} when they failed. */
    private record Read(Map<SortedMap<String, String>, Double> rate, Map<SortedMap<String, String>, Double> latency) {}

    HealthResult observe(String environmentKey, List<ObservableNode> nodes, PrometheusConfig config) {
        Map<String, List<Binding>> bound = new LinkedHashMap<>();
        for (ObservableNode node : nodes) {
            List<Binding> bindings = bindings(environmentKey, node);
            if (!bindings.isEmpty()) {
                bound.put(node.key(), bindings);
            }
        }

        Map<Group, Set<Selector>> groups = new LinkedHashMap<>();
        bound.values().stream().flatMap(List::stream).forEach(binding -> groups
                .computeIfAbsent(binding.group(), group -> new LinkedHashSet<>())
                .add(binding.selector()));
        if (groups.isEmpty()) {
            return new HealthResult(Map.of(), Outcome.complete());
        }

        Map<Group, Read> reads = new HashMap<>();
        List<String> reasons = new ArrayList<>();
        groups.forEach((group, selectors) -> {
            try {
                reads.put(group, read(config, group, selectors));
            } catch (PrometheusApiException e) {
                // ADR-0026: say what could not be read, and leave the store to keep the last reading.
                reasons.add("%s over %s: %s".formatted(group.recipe().kind(), String.join(",", group.labels()), e.getMessage()));
            }
        });
        if (reads.isEmpty()) {
            return new HealthResult(Map.of(), Outcome.failed(String.join("; ", reasons)));
        }

        Map<String, StateContribution> contributions = new LinkedHashMap<>();
        bound.forEach((key, bindings) -> contribution(bindings, reads)
                .ifPresent(metrics -> contributions.put(key, new StateContribution(Health.UNKNOWN, null, metrics))));

        log.debug("health {}/{}: {} node(s) routed, {} bound, {} measured, {} quer(ies)",
                environmentKey, PLUGIN_ID, nodes.size(), bound.size(), contributions.size(), reads.size() * 2);

        return new HealthResult(contributions, reasons.isEmpty() ? Outcome.complete() : Outcome.partial(reasons));
    }

    /** The node's readable {@code prometheus} bindings; the rest abstain here, with a log line. */
    private static List<Binding> bindings(String environmentKey, ObservableNode node) {
        List<Binding> bindings = new ArrayList<>();
        for (Backing backing : node.backings()) {
            if (!PLUGIN_ID.equals(backing.plugin())) {
                continue;
            }
            Optional<Recipe> recipe = Recipe.of(backing.kind());
            Optional<Selector> selector = Selector.parse(backing.reference());
            if (recipe.isEmpty() || selector.isEmpty()) {
                log.warn("{}/{}: prometheus binding '{}:{}' names {}; not read",
                        environmentKey, node.key(), backing.kind(), backing.reference(),
                        recipe.isEmpty() ? "no known recipe" : "no readable selector");
                continue;
            }
            bindings.add(new Binding(recipe.get(), selector.get()));
        }
        return bindings;
    }

    private Read read(PrometheusConfig config, Group group, Set<Selector> selectors) {
        String by = String.join(", ", group.labels());
        String matcher = matcher(group.labels(), selectors);
        Map<SortedMap<String, String>, Double> rate = samples(config, group, group.recipe().rate(by, matcher));
        Map<SortedMap<String, String>, Double> latency = samples(config, group, group.recipe().latency(by, matcher));
        return new Read(rate, latency);
    }

    /**
     * Exact equality where one value is bound to a label, an anchored alternation where several are.
     * PromQL anchors {@code =~} at both ends, so an escaped alternation matches those values and no
     * others.
     */
    private static String matcher(List<String> labels, Set<Selector> selectors) {
        return labels.stream()
                .map(label -> {
                    TreeSet<String> values = selectors.stream()
                            .map(selector -> selector.pairs().get(label))
                            .collect(Collectors.toCollection(TreeSet::new));
                    return values.size() == 1
                            ? "%s=\"%s\"".formatted(label, literal(values.first()))
                            : "%s=~\"%s\"".formatted(label, literal(values.stream()
                                    .map(value -> REGEX_META.matcher(value).replaceAll("\\\\$0"))
                                    .collect(Collectors.joining("|"))));
                })
                .collect(Collectors.joining(","));
    }

    /** A PromQL double-quoted string literal's body. */
    private static String literal(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** Finite values only, keyed by the group's labels as the sample carries them. */
    private Map<SortedMap<String, String>, Double> samples(PrometheusConfig config, Group group, String promql) {
        Map<SortedMap<String, String>, Double> values = new HashMap<>();
        for (PrometheusApi.Sample sample : api.query(config, promql)) {
            if (!Double.isFinite(sample.value())) {
                continue;
            }
            SortedMap<String, String> labels = new TreeMap<>();
            group.labels().forEach(label -> labels.put(label, sample.labels().get(label)));
            values.put(labels, sample.value());
        }
        return values;
    }

    /**
     * ADR-0167's precedence: recipes in order, and within one recipe the node's bindings by selector,
     * so the answer does not depend on the order backings arrived in. The first binding to yield any
     * key supplies both. A recipe whose read failed stops the walk, because what it would have said
     * is unknown and the next recipe's numbers would be a guess about precedence.
     */
    private static Optional<Map<String, Object>> contribution(List<Binding> bindings, Map<Group, Read> reads) {
        for (Recipe recipe : Recipe.PRECEDENCE) {
            List<Binding> ofRecipe = bindings.stream()
                    .filter(binding -> binding.recipe() == recipe)
                    .sorted(Comparator.comparing(binding -> binding.selector().toString()))
                    .toList();
            for (Binding binding : ofRecipe) {
                Read read = reads.get(binding.group());
                if (read == null) {
                    return Optional.empty();
                }
                Map<String, Object> metrics = new TreeMap<>();
                Optional.ofNullable(read.rate().get(binding.selector().pairs())).ifPresent(value -> metrics.put(RATE, value));
                Optional.ofNullable(read.latency().get(binding.selector().pairs())).ifPresent(value -> metrics.put(LATENCY, value));
                if (!metrics.isEmpty()) {
                    return Optional.of(metrics);
                }
            }
        }
        return Optional.empty();
    }
}
