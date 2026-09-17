// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.signin;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** What {@code nodqora.authentication} declares (ADR-0173, ADR-0176). */
public sealed interface SignIn {

    /** No key at all: the install is closed and explains itself (ADR-0173 §1). */
    record NotConfigured() implements SignIn {}

    /** {@code none}: everything is served without sign-in, and the startup log says so (ADR-0173 §2). */
    record Open() implements SignIn {}

    /** One OIDC provider and the session limits that go with it (ADR-0176 §1). */
    record Provider(Oidc oidc, Session session) implements SignIn {}

    /**
     * {@code client-secret} present makes a confidential client, absent a public one whose only proof
     * is PKCE (ADR-0175 §2).
     */
    record Oidc(
            URI baseUrl,
            URI issuer,
            String clientId,
            Optional<String> clientSecret,
            List<String> scopes,
            String nameClaim) {

        public Oidc {
            scopes = List.copyOf(scopes);
        }

        /** The resolved secret is a credential, and a record's {@code toString} ends up in logs. */
        @Override
        public String toString() {
            return "Oidc[baseUrl=%s, issuer=%s, clientId=%s, clientSecret=%s, scopes=%s, nameClaim=%s]"
                    .formatted(baseUrl, issuer, clientId, clientSecret.map(secret -> "<redacted>").orElse("<none>"),
                            scopes, nameClaim);
        }
    }

    /** ADR-0175 §3's two lifetimes. */
    record Session(Duration idle, Duration absolute) {}
}
