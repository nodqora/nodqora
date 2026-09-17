// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.prometheus;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * ADR-0167's selector, the operator's half of a binding: {@code label=value[,label=value…]},
 * equality only, AND-ed.
 *
 * <p><b>This is a copy, and it has to stay one.</b> {@code kubernetes} and {@code yaml} write the
 * same grammar and ADR-0015 rules out sharing code between plugins. They are the copies that must
 * agree with each other, because ADR-0022's union dedupes on the stored string; this one only has
 * to refuse what they would have refused. It re-reads the reference rather than trusting it because
 * a backing is a plain string by the time it arrives, and a selector read loosely here is a query
 * that matches too much.
 *
 * <p>Everything unreadable fails toward <em>no series</em>: an empty selector, an empty value, a
 * value holding {@code , = ;}, an unresolved {@code {…}}, a label that is not a Prometheus label
 * name, or a label named twice.
 */
record Selector(SortedMap<String, String> pairs) {

    private static final Pattern UNFILLED = Pattern.compile("\\{[^}]*}");
    private static final Pattern LABEL_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    Selector {
        pairs = Collections.unmodifiableSortedMap(new TreeMap<>(pairs));
    }

    static Optional<Selector> parse(String reference) {
        if (reference == null || UNFILLED.matcher(reference).find() || reference.contains(";")) {
            return Optional.empty();
        }
        TreeMap<String, String> pairs = new TreeMap<>();
        for (String pair : reference.split(",", -1)) {
            String[] parts = pair.split("=", -1);
            if (parts.length != 2) {
                return Optional.empty();
            }
            String label = parts[0].trim();
            String value = parts[1].trim();
            if (!LABEL_NAME.matcher(label).matches() || value.isEmpty() || pairs.put(label, value) != null) {
                return Optional.empty();
            }
        }
        return Optional.of(new Selector(pairs));
    }

    /** The label names, sorted. Bindings sharing them can be read by one query (ADR-0013). */
    List<String> labels() {
        return List.copyOf(pairs.keySet());
    }

    @Override
    public String toString() {
        return String.join(",", pairs.entrySet().stream().map(pair -> pair.getKey() + "=" + pair.getValue()).toList());
    }
}
