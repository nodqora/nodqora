// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.signin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Where {@code POST /logout} sends the browser once the session row is gone (ADR-0175 §5).
 *
 * <p>Where the provider advertises {@code end_session_endpoint}, there, with {@code id_token_hint}
 * and {@code post_logout_redirect_uri} set to {@code base-url} + {@code /}: RP-initiated logout, the
 * only form in which the next person at a shared screen cannot see the canvas. Otherwise, and while
 * discovery is failing — when whether the provider advertises one cannot be known (ADR-0176 §4) —
 * to {@code /}, signed out locally only.
 *
 * <p>Spring's {@code OidcClientInitiatedLogoutSuccessHandler} does the first half, and throws
 * through the second when its registration lookup fails, which is why this is written out.
 */
final class SignOut implements LogoutSuccessHandler {

    private final Discovery discovery;
    private final URI baseUrl;

    SignOut(Discovery discovery, URI baseUrl) {
        this.discovery = discovery;
        this.baseUrl = baseUrl;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication signedIn) {
        Responses.redirect(response, atTheProvider(signedIn).orElse("/"));
    }

    private Optional<String> atTheProvider(Authentication signedIn) {
        if (signedIn == null || !(signedIn.getPrincipal() instanceof OidcUser user)) {
            return Optional.empty();
        }
        return discovery.registration()
                .map(ClientRegistration::getProviderDetails)
                .map(provider -> provider.getConfigurationMetadata().get("end_session_endpoint"))
                .map(Object::toString)
                .map(endSession -> UriComponentsBuilder.fromUriString(endSession)
                        .queryParam("id_token_hint", user.getIdToken().getTokenValue())
                        .queryParam("post_logout_redirect_uri", baseUrl + "/")
                        .encode()
                        .build()
                        .toUriString());
    }
}
