// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.signin;

import java.net.URI;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * The one provider's registration, discovered on first use (ADR-0176 §4).
 *
 * <p>Nodqora does not contact the provider at startup, so a provider that is down cannot stop it
 * booting or take the canvas from anyone already signed in. The discovery document is fetched by
 * the first request that needs it, <b>kept once it succeeds</b> and <b>not kept when it fails</b>, so
 * the next sign-in tries again. Each failure is an {@code ERROR} naming the issuer and the cause.
 *
 * <p>A document whose {@code issuer} does not match the declared one is a failure like any other:
 * Spring's discovery refuses it, and an issuer typo surfaces at the first sign-in with the same
 * {@code 503} and log line as an outage.
 */
final class Discovery implements ClientRegistrationRepository {

    /** ADR-0176 §1: a constant, so the callback is always {@code /login/oauth2/code/oidc}. */
    static final String REGISTRATION_ID = "oidc";

    static final String AUTHORIZATION_PATH = "/oauth2/authorization/" + REGISTRATION_ID;
    static final String CALLBACK_PATH = "/login/oauth2/code/" + REGISTRATION_ID;

    private static final Logger log = LoggerFactory.getLogger(Discovery.class);

    private final SignIn.Oidc oidc;

    private volatile ClientRegistration discovered;

    Discovery(SignIn.Oidc oidc) {
        this.oidc = oidc;
    }

    URI issuer() {
        return oidc.issuer();
    }

    /** The registration, if the provider has answered once; otherwise one more attempt. */
    Optional<ClientRegistration> registration() {
        ClientRegistration registration = discovered;
        if (registration != null) {
            return Optional.of(registration);
        }
        try {
            registration = registrationFrom(ClientRegistrations.fromIssuerLocation(oidc.issuer().toString()));
        } catch (RuntimeException e) {
            log.error("Sign-in cannot reach the identity provider at {}: {}", oidc.issuer(), e.getMessage());
            return Optional.empty();
        }
        discovered = registration;
        return Optional.of(registration);
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (!REGISTRATION_ID.equals(registrationId)) {
            return null;
        }
        return registration().orElseThrow(() -> new ProviderUnreachable(oidc.issuer()));
    }

    /**
     * ADR-0176 §1: {@code redirect_uri} is {@code base-url} plus the fixed callback, never a template
     * Spring fills from the request, so no forwarded header can reach it.
     */
    private ClientRegistration registrationFrom(ClientRegistration.Builder discovered) {
        return discovered
                .registrationId(REGISTRATION_ID)
                .clientId(oidc.clientId())
                .clientSecret(oidc.clientSecret().orElse(null))
                .clientAuthenticationMethod(oidc.clientSecret().isPresent()
                        ? ClientAuthenticationMethod.CLIENT_SECRET_BASIC
                        : ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(oidc.baseUrl() + CALLBACK_PATH)
                .scope(oidc.scopes())
                .build();
    }

    /** Thrown where Spring asks for the registration and there is none to give. */
    static final class ProviderUnreachable extends RuntimeException {

        ProviderUnreachable(URI issuer) {
            super("the identity provider at " + issuer + " could not be reached");
        }
    }
}
