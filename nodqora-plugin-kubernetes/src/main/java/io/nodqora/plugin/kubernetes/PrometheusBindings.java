// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kubernetes;

import io.nodqora.plugin.api.Backing;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-0164 and ADR-0167: the {@code prometheus} backings this plugin stamps onto a workload it
 * discovered, though it will never read a series itself.
 *
 * <p>A backing is {@code (prometheus, <recipe>, <selector>)}. The recipe is the reading plugin's to
 * interpret and is passed through unjudged — an unknown one abstains over there, where the recipes
 * live. The selector is this plugin's to get right, because it is the binding:
 *
 * <ul>
 *   <li><b>Two routes, and the annotation replaces the template.</b> The template is a guess about a
 *       single fact and {@code topology.io/prometheus} is that guess corrected, so a workload
 *       carrying the annotation gets nothing from the template — even when every binding in the
 *       annotation is dropped, since falling back would read series someone has said are wrong.
 *   <li><b>Equality only, stored sorted by label name</b>, so ADR-0022's union on
 *       {@code (plugin, kind, reference)} dedupes the same pairs written in another order.
 *   <li><b>A binding that cannot be read is dropped whole</b> with a log line, never partly applied:
 *       an unresolved placeholder, an empty selector or value, a value holding {@code , = ;}, or a
 *       label named twice. Each fails toward <em>no series</em>, never toward a matcher matching
 *       everything.
 * </ul>
 */
final class PrometheusBindings {

    static final String DOMAIN = "prometheus";

    private static final Logger log = LoggerFactory.getLogger(PrometheusBindings.class);

    private static final Pattern UNFILLED = Pattern.compile("\\{[^}]*}");
    private static final Pattern LABEL_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    private final Map<String, String> templates;

    PrometheusBindings(Map<String, String> templates) {
        this.templates = templates;
    }

    List<Backing> of(ObservedWorkload workload) {
        String annotated = TopologyAnnotations.value(workload, TopologyAnnotations.PROMETHEUS);
        List<Backing> backings = new ArrayList<>();
        if (annotated == null) {
            templates.forEach((recipe, template) -> add(backings, workload, recipe, template));
            return backings;
        }
        for (String binding : annotated.split(";")) {
            if (binding.isBlank()) {
                continue;
            }
            int colon = binding.indexOf(':');
            if (colon < 0) {
                log.warn("{} {}: prometheus binding '{}' is not <recipe>:<selector>; dropped",
                        workload.kind().lowercased(), workload.reference(), binding.trim());
                continue;
            }
            add(backings, workload, binding.substring(0, colon), binding.substring(colon + 1));
        }
        return backings;
    }

    private static void add(List<Backing> backings, ObservedWorkload workload, String recipe, String template) {
        String selector = selector(workload, template);
        if (recipe.isBlank() || selector == null) {
            log.warn("{} {}: prometheus binding '{}:{}' cannot be read; dropped",
                    workload.kind().lowercased(), workload.reference(), recipe.trim(), template == null ? "" : template.trim());
            return;
        }
        backings.add(new Backing(DOMAIN, recipe.trim(), selector));
    }

    /** The canonical selector, or {@code null} when any part of it cannot be read. */
    private static String selector(ObservedWorkload workload, String template) {
        if (template == null) {
            return null;
        }
        String interpolated = template
                .replace("{name}", workload.name())
                .replace("{namespace}", workload.namespace())
                .replace("{kind}", workload.kind().lowercased());
        if (UNFILLED.matcher(interpolated).find() || interpolated.contains(";")) {
            return null;
        }
        TreeMap<String, String> pairs = new TreeMap<>();
        for (String pair : interpolated.split(",", -1)) {
            String[] parts = pair.split("=", -1);
            if (parts.length != 2) {
                return null;
            }
            String label = parts[0].trim();
            String value = parts[1].trim();
            if (!LABEL_NAME.matcher(label).matches() || value.isEmpty() || pairs.put(label, value) != null) {
                return null;
            }
        }
        return String.join(",", pairs.entrySet().stream().map(pair -> pair.getKey() + "=" + pair.getValue()).toList());
    }
}
