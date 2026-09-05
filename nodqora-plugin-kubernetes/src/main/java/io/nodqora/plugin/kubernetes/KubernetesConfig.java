// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kubernetes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Locale;

/**
 * ADR-0035, bound from the config file and validated at startup (ADR-0014).
 *
 * <pre>
 * kubernetes:
 *   namespaces: [payments-prod]                      # required, non-empty, no wildcard
 *   kubeconfig: "${file:/etc/nodqora/kubeconfig}"    # optional; absent ⇒ in-cluster
 *   context: prod-cluster                            # optional
 *   ignore: [statefulset/kafka-connect]              # ADR-0031, exact kind/name
 *   links: { workload: ..., pods: ..., logs: ..., dashboard: ..., gitops: ... }
 * </pre>
 *
 * <p><b>Namespace scope is the one narrowing knob that cannot be avoided</b> — you must say where to
 * look — so it is made loud rather than removed: no default, no wildcard, and startup failure on
 * empty. A forgotten namespace then reads as "half my graph is gone" rather than as one subtly
 * absent node, which under ADR-0004 would be indistinguishable from real drift.
 *
 * <p>ADR-0035's per-plugin cadence keys are deliberately absent. Its numbers — 5m discovery, 30s
 * health — are already the engine's file-declared defaults under {@code nodqora.refresh}, and
 * per-plugin overrides are core scheduling machinery that no slice has built. Binding keys nothing
 * reads would be configuration that silently does nothing.
 */
public record KubernetesConfig(
        @NotEmpty List<@NotBlank String> namespaces,
        String kubeconfig,
        String context,
        List<String> ignore,
        Links links) {

    public KubernetesConfig {
        namespaces = namespaces == null ? List.of() : List.copyOf(namespaces);
        // Only the kind half is folded, so a `StatefulSet/kafka-connect` entry still means what it
        // looks like while the name stays an exact match — see ObservedWorkload.denyListEntry().
        ignore = ignore == null ? List.of() : ignore.stream().map(KubernetesConfig::foldKind).toList();
        links = links == null ? Links.none() : links;
    }

    public boolean denies(ObservedWorkload workload) {
        return ignore.contains(workload.denyListEntry());
    }

    private static String foldKind(String entry) {
        String trimmed = entry.trim();
        int slash = trimmed.indexOf('/');
        return slash < 0
                ? trimmed.toLowerCase(Locale.ROOT)
                : trimmed.substring(0, slash).toLowerCase(Locale.ROOT) + trimmed.substring(slash);
    }

    /**
     * ADR-0032's per-environment link templates. Half the annotation values are <em>ids</em> rather
     * than URLs, and an absolute URL in an annotation is the wrong thing anyway: the same manifest
     * is deployed to production and staging, so a hardcoded Grafana URL points staging's node at
     * production's dashboard. The id-plus-per-environment-template split is what makes one
     * annotation render correctly in both.
     *
     * <p>{@code workload}, {@code pods} and {@code logs} are composed from the object —
     * {@code {namespace}}, {@code {kind}}, {@code {name}}. {@code dashboard} and {@code gitops} take
     * {@code {value}}: the {@code topology.io/grafana} id and Argo CD's own
     * {@code argocd.argoproj.io/instance} label. A template that is not configured yields no link,
     * never a half-composed URL.
     */
    public record Links(String workload, String pods, String logs, String dashboard, String gitops) {

        public static Links none() {
            return new Links(null, null, null, null, null);
        }
    }
}
