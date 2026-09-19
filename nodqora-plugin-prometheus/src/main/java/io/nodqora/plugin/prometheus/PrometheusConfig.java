// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.prometheus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

/**
 * Bound from the config file and validated at startup (ADR-0014).
 *
 * <pre>
 * prometheus:
 *   url: http://prometheus.monitoring:9090                                   # required
 *   auth: { username: nodqora, password: "${env:PROMETHEUS_PROD_PASSWORD}" }  # optional
 *   bearerToken: "${file:/etc/nodqora/prometheus-token}"                     # optional, or auth
 * </pre>
 *
 * <p><b>Only where to read and how to authenticate.</b> Which series describe which node is not
 * here: the recipe is a plugin constant and the selector arrives on the backing, stamped by whoever
 * knows the node (ADR-0164, ADR-0167). There is nothing to scope, because this plugin discovers
 * nothing and is handed only nodes someone else bound.
 *
 * <p>Both credentials are ADR-0014 references resolved before binding, so the file holds a
 * reference and never a secret. Prometheus itself authenticates nothing; these are for the proxy
 * that usually stands in front of it, which speaks one scheme or the other.
 */
public record PrometheusConfig(@NotBlank String url, @Valid Auth auth, String bearerToken) {

    /** Basic auth. */
    public record Auth(@NotBlank String username, String password) {

        /** A resolved secret is a credential, and a record's {@code toString} ends up in logs. */
        @Override
        public String toString() {
            return "Auth[username=%s, password=%s]".formatted(username, password == null ? "<none>" : "<redacted>");
        }
    }

    /** As {@link Auth#toString()}: the bearer token is the credential here. */
    @Override
    public String toString() {
        return "PrometheusConfig[url=%s, auth=%s, bearerToken=%s]"
                .formatted(url, auth, bearerToken == null ? "<none>" : "<redacted>");
    }

    /** Two credentials is a configuration nobody can mean, so it fails at startup. */
    @AssertTrue(message = "configure auth or bearerToken, not both")
    boolean isOneCredentialAtMost() {
        return auth == null || bearerToken == null;
    }
}
