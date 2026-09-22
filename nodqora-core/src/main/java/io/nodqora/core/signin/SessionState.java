// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.signin;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two public paths outside {@code /api} (ADR-0173 §3, ADR-0177 §1–2).
 *
 * <p>{@code GET /session} is what the shell asks to learn <em>why</em> it got a {@code 401}, so it
 * never answers one itself. It says which of four states this request is in, and carries a name and
 * a CSRF token only when someone is signed in. {@code GET /healthz} says the process is up, and
 * nothing else: not the version, not the roster, and not whether the provider is reachable.
 */
@RestController
class SessionState {

    enum State {
        NOT_CONFIGURED,
        OPEN,
        SIGNED_OUT,
        SIGNED_IN
    }

    /** {@code name} and {@code csrf} are absent, never {@code null}, outside {@code SIGNED_IN}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Document(State state, String name, Csrf csrf) {}

    /** Both names travel, so the sign-out form does not hard-code Spring's defaults. */
    record Csrf(String headerName, String parameterName, String token) {}

    private final SignIn signIn;

    SessionState(SignIn signIn) {
        this.signIn = signIn;
    }

    @GetMapping("/session")
    ResponseEntity<Document> session(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(document(request));
    }

    @GetMapping("/healthz")
    ResponseEntity<Void> healthz() {
        return ResponseEntity.ok().build();
    }

    private Document document(HttpServletRequest request) {
        return switch (signIn) {
            case SignIn.NotConfigured notConfigured -> new Document(State.NOT_CONFIGURED, null, null);
            case SignIn.Open open -> new Document(State.OPEN, null, null);
            case SignIn.Provider provider -> signedIn()
                    .map(user -> new Document(State.SIGNED_IN, name(user, provider.oidc().nameClaim()), csrf(request)))
                    .orElse(new Document(State.SIGNED_OUT, null, null));
        };
    }

    private static Optional<OidcUser> signedIn() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof OidcUser user
                ? Optional.of(user)
                : Optional.empty();
    }

    /**
     * ADR-0176 §1: {@code name-claim}, then {@code preferred_username}, then {@code sub}, which every
     * ID token carries. The fallback is not configurable, and the shell never sees a claim.
     */
    static String name(OidcUser user, String nameClaim) {
        for (String claim : new String[] {nameClaim, StandardClaimNames.PREFERRED_USERNAME}) {
            Object value = user.getClaims().get(claim);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return user.getSubject();
    }

    private static Csrf csrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return new Csrf(token.getHeaderName(), token.getParameterName(), token.getToken());
    }
}
