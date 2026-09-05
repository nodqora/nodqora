package io.nodqora.plugin.kafka;

import io.nodqora.plugin.api.Link;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-0039's composer for topics: ADR-0032's mechanism with one variable instead of four.
 *
 * <p>Everything except the topic's own name is constant per environment — one cluster, no
 * annotations, no kinds — and the template is already per-environment, so the cluster and path are
 * literal text the operator types in and {@code {name}} is the only thing that varies.
 *
 * <p>Carried over from ADR-0032 unchanged: no template configured ⇒ no link, never a half-composed
 * URL; labels are plugin constants rather than configuration.
 */
final class KafkaLinks {

    private static final Logger log = LoggerFactory.getLogger(KafkaLinks.class);

    private static final Pattern UNFILLED = Pattern.compile("\\{[^}]*}");

    private final KafkaConfig.Links templates;

    KafkaLinks(KafkaConfig.Links templates) {
        this.templates = templates;
    }

    List<Link> of(String topic) {
        List<Link> links = new ArrayList<>();
        add(links, "dashboard", "Grafana", templates.dashboard(), topic);
        add(links, "topic", "Topic", templates.topic(), topic);
        add(links, "consumers", "Consumer Groups", templates.consumers(), topic);
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
