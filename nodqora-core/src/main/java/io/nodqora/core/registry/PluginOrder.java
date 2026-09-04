package io.nodqora.core.registry;

import java.util.List;
import java.util.Objects;

/**
 * The two orders over plugin ids that the core needs, both supplied as configuration.
 *
 * <p>ADR-0011 is explicit that precedence is <em>"expressed as config over plugin ids, not baked
 * into the interface"</em>, and ADR-0015 forbids the core naming a plugin at all — so neither list
 * can be a constant in this module. ADR-0074 permits the core to hold plugin <em>ids</em>; what it
 * may not hold is knowledge of a plugin's shape.
 *
 * <ul>
 *   <li><b>registry order</b> — the canonical order of {@code sources[]} (ADR-0050) and the order
 *       {@code rawSignal} contributions join in (ADR-0028).
 *   <li><b>precedence</b> — first non-null wins a contested scalar, and orders {@code links[]}
 *       (ADR-0044, ADR-0050).
 * </ul>
 *
 * <p>They are genuinely different orders, which is why one list will not do.
 */
public record PluginOrder(List<String> registryOrder, List<String> precedence) {

    public PluginOrder {
        registryOrder = List.copyOf(Objects.requireNonNull(registryOrder, "registryOrder"));
        precedence = List.copyOf(Objects.requireNonNull(precedence, "precedence"));
    }

    /** An unlisted plugin sorts last rather than throwing: a fold must never fail over ordering. */
    public int registryRank(String pluginId) {
        return rank(registryOrder, pluginId);
    }

    public int precedenceRank(String pluginId) {
        return rank(precedence, pluginId);
    }

    private static int rank(List<String> order, String pluginId) {
        int index = order.indexOf(pluginId);
        return index < 0 ? order.size() : index;
    }
}
