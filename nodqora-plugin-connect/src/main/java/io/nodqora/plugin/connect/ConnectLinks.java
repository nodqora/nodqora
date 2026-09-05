// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.connect;

import io.nodqora.plugin.api.Link;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-0039's composer: ADR-0032's mechanism with one variable instead of four.
 *
 * <p>The labels are constants here rather than configuration, exactly as in {@code kubernetes}: a
 * per-environment label would let one environment call the same link something else, and the
 * inspector's "Connect UI" button would read differently in staging for no reason a user could act
 * on.
 *
 * <p><b>{@code connect} composes links only for the connectors it owns, never for destination
 * nodes.</b> The fixture's Kibana link on {@code payments-events-v1} belongs to a declared node no
 * plugin observes and comes from YAML. Writing links onto a node this plugin merely points an edge
 * at would make it a second writer for a field it knows nothing about.
 */
final class ConnectLinks {

    private static final Logger log = LoggerFactory.getLogger(ConnectLinks.class);

    private static final Pattern UNFILLED = Pattern.compile("\\{[^}]*}");

    private final ConnectConfig.Links templates;

    ConnectLinks(ConnectConfig.Links templates) {
        this.templates = templates;
    }

    /** §9's order for a connector: Connect UI, Logs, Config. */
    List<Link> of(String connectorName) {
        List<Link> links = new ArrayList<>();
        add(links, "connector", "Connect UI", templates.connector(), connectorName);
        add(links, "logs", "Logs", templates.logs(), connectorName);
        add(links, "config", "Config", templates.config(), connectorName);
        return links;
    }

    private static void add(List<Link> links, String rel, String label, String template, String name) {
        if (template == null || template.isBlank()) {
            return;
        }
        String url = template.replace("{name}", name);
        if (UNFILLED.matcher(url).find()) {
            log.warn("link template '{}' names a placeholder this plugin cannot fill; {name} is the only "
                    + "variable ADR-0039 gives it, so no link is composed", template);
            return;
        }
        links.add(new Link(rel, label, url));
    }
}
