// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.signin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The filter chain, which fails closed on everything core does not own (ADR-0174 §4).
 *
 * <table>
 *   <tr><th>Path</th><th>A provider</th><th>{@code none}</th><th>Sign-in not configured</th></tr>
 *   <tr><td>{@code /api/**}</td><td>{@code 401} until signed in</td><td>served</td><td>{@code 401}</td></tr>
 *   <tr><td>{@code /session}, {@code /healthz}</td><td>served</td><td>served</td><td>served</td></tr>
 *   <tr><td>anything else</td><td>sign-in required</td><td>served</td><td>served, so the first-run
 *       screen can render</td></tr>
 * </table>
 *
 * <p>It matches every request and is ordered last, so an assembly adds its own public paths — the
 * frontend build's static assets — as a chain ahead of it. An assembly that forgets gets a page that
 * asks for sign-in, not a leak. There is no {@code @ConditionalOnMissingBean} and no customiser:
 * another assembly adds beans Spring Security already looks up, or drops the package (ADR-0174 §3).
 */
@Configuration
class SignInChain {

    private static final Logger log = LoggerFactory.getLogger(SignInChain.class);

    private static final RequestMatcher API = PathPatternRequestMatcher.withDefaults().matcher("/api/**");

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain signInFilterChain(HttpSecurity http, SignIn signIn, Clock clock, ObjectMapper json)
            throws Exception {
        Responses responses = new Responses(json);
        http.authorizeHttpRequests(paths -> paths
                // A forward to index.html and an error page are the same request, already decided.
                .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
                .requestMatchers("/session", "/healthz").permitAll());
        return switch (signIn) {
            case SignIn.NotConfigured notConfigured -> notConfigured(http, responses);
            case SignIn.Open open -> open(http);
            case SignIn.Provider provider -> provider(http, provider, clock, responses);
        };
    }

    /**
     * ADR-0173 §1: the process boots and serves nothing. The shell loads, reads {@code /session}, and
     * replaces itself with the first-run screen.
     */
    private static SecurityFilterChain notConfigured(HttpSecurity http, Responses responses) throws Exception {
        return withoutSessions(http)
                .authorizeHttpRequests(paths -> paths
                        .requestMatchers(API).denyAll()
                        .anyRequest().permitAll())
                .exceptionHandling(failures -> failures.authenticationEntryPoint(
                        (request, response, e) -> responses.notSignedIn(request, response)))
                .build();
    }

    /** ADR-0173 §2: everything is served without sign-in, as {@code v0.2.0} served it. */
    private static SecurityFilterChain open(HttpSecurity http) throws Exception {
        return withoutSessions(http)
                .authorizeHttpRequests(paths -> paths.anyRequest().permitAll())
                .build();
    }

    /** With nobody to sign in, nothing the chain does may create a session row. */
    private static HttpSecurity withoutSessions(HttpSecurity http) throws Exception {
        return http
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .logout(AbstractHttpConfigurer::disable);
    }

    private static SecurityFilterChain provider(
            HttpSecurity http, SignIn.Provider provider, Clock clock, Responses responses) throws Exception {
        Discovery discovery = new Discovery(provider.oidc());

        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        // A poll that got a 401 is not somewhere to come back to, and saving it would write a session
        // row for every unauthenticated request the canvas makes.
        requestCache.setRequestMatcher(new AndRequestMatcher(
                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/**"), new NegatedRequestMatcher(API)));

        DefaultOAuth2AuthorizationRequestResolver authorizationRequests = new DefaultOAuth2AuthorizationRequestResolver(
                discovery, OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        // ADR-0175 §2: every authorization request uses PKCE, confidential client or not.
        authorizationRequests.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());

        return http
                .authorizeHttpRequests(paths -> paths.anyRequest().authenticated())
                .requestCache(cache -> cache.requestCache(requestCache))
                .exceptionHandling(failures -> failures.authenticationEntryPoint((request, response, e) -> {
                    if (API.matches(request)) {
                        responses.notSignedIn(request, response);
                    } else {
                        // Relative, as every redirect the chain issues is (ADR-0176 §1).
                        Responses.redirect(response, Discovery.AUTHORIZATION_PATH);
                    }
                }))
                .oauth2Login(login -> login
                        // Naming the one provider's start as the login page is what stops Spring
                        // generating a chooser page at /login — a public page ADR-0173 §3 has no row for.
                        .loginPage(Discovery.AUTHORIZATION_PATH)
                        .clientRegistrationRepository(discovery)
                        .authorizedClientRepository(new KeepsNoToken())
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(authorizationRequests))
                        .successHandler(backToWhereItStarted(requestCache))
                        .failureHandler(failed(responses)))
                .logout(logout -> logout.logoutSuccessHandler(new SignOut(discovery, provider.oidc().baseUrl())))
                .addFilterAfter(new AbsoluteLifetime(provider.session().absolute(), clock), SecurityContextHolderFilter.class)
                .addFilterBefore(new DiscoveryGate(discovery, responses), OAuth2AuthorizationRequestRedirectFilter.class)
                .build();
    }

    /**
     * Back to the URL the browser first asked for, as a relative redirect (ADR-0176 §1). Spring's own
     * handler rebuilds it absolute from the request's scheme and host, which is the guess from the
     * socket that {@code base-url} exists to avoid.
     */
    private static AuthenticationSuccessHandler backToWhereItStarted(HttpSessionRequestCache requestCache) {
        return (request, response, authentication) -> {
            SavedRequest saved = requestCache.getRequest(request, response);
            requestCache.removeRequest(request, response);
            String target = saved instanceof DefaultSavedRequest started
                    ? started.getRequestURI() + (started.getQueryString() == null ? "" : "?" + started.getQueryString())
                    : "/";
            // A path that begins "//" or "/\" is read by a browser as another host.
            Responses.redirect(response, target.startsWith("//") || target.startsWith("/\\") ? "/" : target);
        };
    }

    private static AuthenticationFailureHandler failed(Responses responses) {
        return (request, response, e) -> {
            log.warn("Sign-in did not succeed: {}", e instanceof OAuth2AuthenticationException oauth
                    ? oauth.getError().getErrorCode() + " " + e.getMessage()
                    : e.getMessage());
            responses.signInFailed(response);
        };
    }

    /**
     * ADR-0176 §4: while discovery fails, a browser that must sign in is answered here, before Spring
     * asks for a registration it cannot have. The callback is gated too, because it may land on a
     * replica whose own discovery has not yet succeeded.
     */
    private static final class DiscoveryGate extends OncePerRequestFilter {

        private final Discovery discovery;
        private final Responses responses;

        DiscoveryGate(Discovery discovery, Responses responses) {
            this.discovery = discovery;
            this.responses = responses;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            return !path.equals(Discovery.AUTHORIZATION_PATH) && !path.equals(Discovery.CALLBACK_PATH);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (discovery.registration().isEmpty()) {
                responses.providerUnreachable(response, discovery.issuer());
                return;
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * ADR-0175 §2: the access and refresh tokens are dropped once sign-in completes, so a leaked
     * session row carries no credential usable at the provider. Spring's default keeps them in memory
     * keyed by principal, outside the session, growing with every user who ever signed in.
     */
    private static final class KeepsNoToken implements OAuth2AuthorizedClientRepository {

        @Override
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                String registrationId, Authentication principal,
                HttpServletRequest request) {
            return null;
        }

        @Override
        public void saveAuthorizedClient(
                OAuth2AuthorizedClient client, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {}

        @Override
        public void removeAuthorizedClient(
                String registrationId, Authentication principal,
                HttpServletRequest request, HttpServletResponse response) {}
    }
}
