package io.nodqora.plugin.kubernetes;

import io.nodqora.plugin.api.Link;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ADR-0032: annotations hold ids, links are composed per environment.
 *
 * <p>{@code topology.io/grafana: payments-enricher-overview} is not a URL, it is a dashboard id, and
 * a {@link Link} needs a URL — so something has to compose one. That is the whole reason the
 * vocabulary is shaped the way it is rather than being a matter of taste.
 *
 * <p>Two rules run everything here:
 *
 * <ul>
 *   <li><b>No template configured ⇒ no link.</b> Never a half-composed URL — which is also why a
 *       template naming a placeholder this plugin cannot fill yields nothing but a warning.
 *   <li><b>Labels are plugin constants</b>, not configurable. A per-environment label would let one
 *       environment call the same link something else.
 * </ul>
 *
 * <p>{@code repository} / {@code runbook} / {@code docs} need no template — their values are URLs,
 * and {@code https://} is prepended when no scheme is present.
 */
final class LinkComposer {

    private static final Logger log = LoggerFactory.getLogger(LinkComposer.class);

    private static final Pattern UNFILLED = Pattern.compile("\\{[^}]*}");
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://");

    private final KubernetesConfig.Links templates;

    LinkComposer(KubernetesConfig.Links templates) {
        this.templates = templates;
    }

    /**
     * The links an object's own annotations produce. These come from the node's <em>winning</em>
     * claimant only: they are the annotation author's statement about the node, and ADR-0021 takes
     * every such statement from the newest object rather than blending two.
     */
    List<Link> fromAnnotations(ObservedWorkload workload) {
        List<Link> links = new ArrayList<>();
        add(links, "repository", "Repository", absolute(TopologyAnnotations.value(workload, TopologyAnnotations.REPOSITORY)));
        add(links, "runbook", "Runbook", absolute(TopologyAnnotations.value(workload, TopologyAnnotations.RUNBOOK)));
        add(links, "docs", "Docs", absolute(TopologyAnnotations.value(workload, TopologyAnnotations.DOCS)));
        add(links, "dashboard", "Grafana", fromValue(
                templates.dashboard(), TopologyAnnotations.value(workload, TopologyAnnotations.GRAFANA)));
        add(links, "gitops", "Argo CD", fromValue(
                templates.gitops(), workload.labels().get(TopologyAnnotations.ARGOCD_INSTANCE)));
        return links;
    }

    /**
     * The links the object itself produces — <b>one set per workload backing</b> (ADR-0032, and
     * ADR-0044's note that a contested key can therefore leave a node carrying two {@code workload}
     * links). Normally there is exactly one. {@code logs} joins them rather than the annotation set
     * because it is composed from the same object coordinates: there is no sense in which one
     * claimant's logs stand for the other's.
     */
    List<Link> fromObject(ObservedWorkload workload) {
        Map<String, String> values = Map.of(
                "namespace", workload.namespace(),
                "kind", workload.kind().lowercased(),
                "name", workload.name());
        List<Link> links = new ArrayList<>();
        add(links, "workload", "Workload", composed(templates.workload(), values));
        add(links, "pods", "Pods", composed(templates.pods(), values));
        add(links, "logs", "Logs", composed(templates.logs(), values));
        return links;
    }

    private static void add(List<Link> links, String rel, String label, String url) {
        if (url != null) {
            links.add(new Link(rel, label, url));
        }
    }

    /** {@code {value}} is the id half of ADR-0032's split: no id and no template alike mean no link. */
    private static String fromValue(String template, String value) {
        return value == null ? null : composed(template, Map.of("value", value));
    }

    private static String composed(String template, Map<String, String> values) {
        if (template == null || template.isBlank()) {
            return null;
        }
        String url = template;
        for (Map.Entry<String, String> value : values.entrySet()) {
            url = url.replace("{" + value.getKey() + "}", value.getValue());
        }
        if (UNFILLED.matcher(url).find()) {
            log.warn("link template '{}' names a placeholder this plugin cannot fill; no link composed", template);
            return null;
        }
        return url;
    }

    private static String absolute(String url) {
        return url == null || SCHEME.matcher(url).find() ? url : "https://" + url;
    }
}
