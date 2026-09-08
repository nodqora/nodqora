// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.connect;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * ADR-0042 and ADR-0090, bound from the config file and validated at startup (ADR-0014).
 *
 * <pre>
 * connect:
 *   url: https://connect-prod.internal:8083          # required
 *   auth: { username: nodqora, password: "${env:CONNECT_PROD_PASSWORD}" }   # optional
 *   connectors:
 *     include: [payments-]                           # required, non-empty, no empty-string element
 *     ignore: [payments-debug-reprocessor]           # exact connector names (ADR-0031)
 *   workload:                                        # optional (ADR-0022)
 *     plugin: kubernetes
 *     kind: statefulset                              # or `pods`, with a selector (ADR-0148)
 *     reference: payments-prod/kafka-connect
 *   links: { connector: ..., logs: ..., config: ... }
 * </pre>
 *
 * <p><b>Scope is required and there is no default</b>, because the default would have been
 * <em>everything</em> — on a shared Connect cluster, every team's connectors on the payments canvas.
 * Matching is exact leading-substring: no regex, no glob, no wildcard.
 *
 * <p><b>There is no in-band route</b> (ADR-0090). Nothing is read out of a connector's own config to
 * control node emission — no {@code topology.io.ignore} key, no annotation analogue — because that
 * would be Nodqora's vocabulary inside a third party's document, which is ADR-0041's line crossed
 * from the other side and worse, since it is a key that must be <em>invented</em> rather than one
 * that already exists.
 *
 * <p>ADR-0037 had to reject enumerated names for topics, on the ground that topics are created
 * constantly so a new pipeline topic goes silently missing. That reason does not hold here: a
 * connector is a rare, deliberate, operator-authored deployment. So on a dedicated cluster with no
 * naming convention the prefix list degenerates gracefully into an enumeration of connector names,
 * and that degenerate form is acceptable <em>because of the rarity property</em>.
 */
public record ConnectConfig(
        @NotBlank String url,
        @Valid Auth auth,
        @NotNull @Valid Connectors connectors,
        @Valid Workload workload,
        Links links) {

    public ConnectConfig {
        links = links == null ? Links.none() : links;
    }

    /**
     * Basic auth, optional. The password is an ADR-0014 {@code ${env:}} / {@code ${file:}} reference
     * resolved before binding, so the file holds a reference and never a secret.
     */
    public record Auth(@NotBlank String username, String password) {}

    /**
     * ADR-0090's include-prefix list with an exact-name {@code ignore}.
     *
     * <p>{@code @NotEmpty} on the list and {@code @NotBlank} on the element are the two halves of
     * "required, non-empty, no empty-string element" — and the second half matters more than it
     * looks, because {@code ""} is a prefix of every string, so one blank entry silently restores
     * the default this configuration exists to refuse.
     */
    public record Connectors(@NotEmpty List<@NotBlank String> include, List<String> ignore) {

        public Connectors {
            include = include == null ? List.of() : List.copyOf(include);
            ignore = ignore == null ? List.of() : ignore.stream().map(String::trim).toList();
        }

        public boolean admits(String connectorName) {
            return matches(connectorName) && !ignore.contains(connectorName);
        }

        /** ADR-0090: a prefix matching nothing is a named {@code PARTIAL} reason, so it is asked for. */
        public boolean matches(String connectorName, String prefix) {
            return connectorName.startsWith(prefix);
        }

        private boolean matches(String connectorName) {
            return include.stream().anyMatch(prefix -> matches(connectorName, prefix));
        }
    }

    /**
     * ADR-0022: the workload this Connect cluster runs on, stamped as a backing onto <b>every</b>
     * connector the plugin discovers. The plugin that knows the node key emits the backing, whatever
     * technology the object belongs to — so {@code connect} names a Kubernetes StatefulSet here, and
     * {@code kubernetes} is the one handed those nodes to observe.
     *
     * <p><b>Optional, and that is a decision.</b> A Connect cluster not on Kubernetes — MSK Connect,
     * bare metal, Docker — is normal, and requiring this would force operators to invent a
     * reference. Absent, connectors carry two backings instead of three and lose the readiness
     * contribution.
     *
     * <p><b>These three fields are opaque here and that is what keeps them cheap.</b> This plugin
     * never interprets them, so ADR-0148 could give {@code kubernetes} a second kind — {@code kind:
     * pods} with a {@code <namespace>/<label selector>} reference, for a Connect cluster whose owner
     * is a {@code StrimziPodSet} or any other CRD — without touching this record. The cost of the
     * same property is that nothing validates the reference at startup: a malformed one is found by
     * the plugin that reads it, per poll, in the log.
     */
    public record Workload(@NotBlank String plugin, @NotBlank String kind, @NotBlank String reference) {}

    /**
     * ADR-0039's per-environment link templates, with {@code {name}} as the only variable.
     *
     * <p>ADR-0032 needed four variables because a Kubernetes plugin watches several namespaces and
     * several kinds and reads ids out of annotations. Here everything except the connector's own
     * name is constant per environment — one cluster, no annotations, no kinds — so the cluster and
     * path are literal text the operator types in.
     *
     * <p>Carried over from ADR-0032 unchanged: <b>no template configured ⇒ no link, never a
     * half-composed URL</b>, and labels are plugin constants rather than configuration.
     *
     * <p><b>{@code config} must point at a UI, never at {@code GET /connectors/{name}/config}.</b>
     * Research #5 verified that endpoint applies no masking of any kind, so a connector with an
     * inlined password serves it in plaintext to whoever clicks. It is a template like any other and
     * cannot be technically prevented from here, which is exactly why ADR-0039 refused to leave it
     * implicit.
     */
    public record Links(String connector, String logs, String config) {

        public static Links none() {
            return new Links(null, null, null);
        }
    }
}
