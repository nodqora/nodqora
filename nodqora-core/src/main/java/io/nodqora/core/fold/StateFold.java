package io.nodqora.core.fold;

import io.nodqora.core.graph.FoldedNodeState;
import io.nodqora.core.registry.PluginOrder;
import io.nodqora.plugin.api.Health;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The pure recomputation of an environment's {@code node_state} rows from the contribution store
 * (ADR-0072) — the fast half's {@link GraphFold}, and the same three properties hold: it is a fold
 * rather than a merge, it is order-independent, and its output is canonically ordered.
 *
 * <p>The first of those is the one that is <b>structural rather than stylistic here</b>. ADR-0024
 * discards abstentions before it collapses, so a composed row has already thrown away the
 * information a re-collapse would need: <b>you cannot recompute a collapse from its own output</b>.
 * Folding a new contribution into the previous {@code node_state} value is not discouraged, it does
 * not typecheck as an idea. It is also the shape in which a plugin could never <em>stop</em> saying
 * {@code DISABLED} — ADR-0034 emits it from {@code spec.replicas: 0}, and scaling back up would need
 * the old contribution found and cleared by hand rather than simply replaced.
 *
 * <p>Abstentions are discarded before anything else happens, and a node with nothing left has
 * <b>no row</b> (ADR-0104). That is ADR-0024's step 1 with its consequence carried all the way out:
 * there is exactly one way for a node to be {@code UNKNOWN}, which is having no row, so the value
 * always arrives by ADR-0028's outer join and never by a stored one carrying a freshness for an
 * observation nobody made.
 *
 * <p>Composition splits three ways and only one of them was ever hard:
 *
 * <ul>
 *   <li><b>{@code health}</b> — ADR-0024's collapse, which lives on {@link Health} because the same
 *       three steps also run inside a plugin.
 *   <li><b>{@code rawSignal}</b> — joined in <em>registry</em> order with {@code "; "} (ADR-0028),
 *       which is why {@code PluginOrder} carries two orders rather than one: precedence settles a
 *       contested scalar on the slow side, and it is a different question from what reads well.
 *   <li><b>{@code metrics}</b> — a union namespaced by plugin id (ADR-0006), so no plugin can write
 *       into another's map and the allow-listed key names never collide.
 * </ul>
 */
public class StateFold {

    private final PluginOrder order;

    public StateFold(PluginOrder order) {
        this.order = order;
    }

    public List<FoldedNodeState> fold(List<ContributionView> contributions) {
        Map<Long, List<ContributionView>> byNode = new LinkedHashMap<>();
        contributions.forEach(contribution ->
                byNode.computeIfAbsent(contribution.nodeId(), ignored -> new ArrayList<>()).add(contribution));

        List<FoldedNodeState> states = new ArrayList<>(byNode.size());
        byNode.forEach((nodeId, carried) -> {
            List<ContributionView> byRegistry = carried.stream()
                    // An abstention is an omission (ADR-0104), so it is dropped here as well as at
                    // the store. Both points, deliberately: the store is where it is enforced for
                    // every plugin, and this is where the fold stays correct as a function of its
                    // own inputs rather than of what happened to be written upstream.
                    .filter(contribution -> contribution.health() != Health.UNKNOWN)
                    .sorted(Comparator.comparingInt(
                                    (ContributionView contribution) -> order.registryRank(contribution.pluginId()))
                            .thenComparing(ContributionView::pluginId))
                    .toList();
            if (byRegistry.isEmpty()) {
                // No row at all, rather than a row reading UNKNOWN. ADR-0028's outer join
                // synthesizes UNKNOWN / null / {} / null on the way out, and a row here would carry
                // an `observedAt` for an observation nobody made.
                return;
            }
            states.add(new FoldedNodeState(
                    nodeId,
                    Health.collapse(byRegistry.stream().map(ContributionView::health).toList()),
                    rawSignal(byRegistry),
                    metrics(byRegistry),
                    observedAt(byRegistry)));
        });

        return states.stream()
                .sorted(Comparator.comparingLong(FoldedNodeState::nodeId))
                .toList();
    }

    /**
     * ADR-0028: registry order, fixed, always, joined by {@code "; "} — so a node observed by three
     * plugins reads its segments in the same sequence every poll, whatever order the polls landed
     * in. Registry order rather than precedence: precedence settles a contested scalar on the slow
     * side, and it is a different question from what reads well in a sentence.
     *
     * <p>A plugin that observed the node but has nothing to say contributes nothing to the line
     * rather than an empty segment; {@code null} when no plugin does, never a string describing
     * plugin absence, which ADR-0015 forbids the core from knowing about.
     */
    private static String rawSignal(List<ContributionView> byRegistry) {
        String joined = byRegistry.stream()
                .map(ContributionView::rawSignal)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(signal -> !signal.isEmpty())
                .collect(Collectors.joining("; "));
        return joined.isEmpty() ? null : joined;
    }

    /**
     * ADR-0006: each plugin's allow-listed metrics under its own id, so no plugin can write into
     * another's map and two plugins may use the same key name without colliding.
     *
     * <p>Sorted for a fold that is deterministic in its own right, though this is the one collection
     * whose order the storage layer decides for us: {@code jsonb} normalizes object keys by length
     * then bytes. ADR-0050's ordering obligation bites on the <em>arrays</em>, which it stores as
     * written.
     */
    private static Map<String, Object> metrics(List<ContributionView> byRegistry) {
        Map<String, Object> namespaced = new LinkedHashMap<>();
        byRegistry.forEach(contribution -> {
            if (!contribution.metrics().isEmpty()) {
                namespaced.put(contribution.pluginId(), new TreeMap<>(contribution.metrics()));
            }
        });
        return namespaced;
    }

    /**
     * ADR-0072: {@code min}, because the composed state is only as fresh as its stalest
     * contribution. {@code max} would let a five-second-old reading from one plugin present another
     * plugin's reading from three cycles ago as current, and the frontend renders this number as
     * "how old is this" — so it must never overstate.
     */
    private static Instant observedAt(List<ContributionView> byRegistry) {
        return byRegistry.stream()
                .map(ContributionView::observedAt)
                .min(Comparator.naturalOrder())
                .orElseThrow();
    }
}
