// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.signin;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.util.HtmlUtils;

/** The few bodies the chain writes itself, before any controller is reached. */
final class Responses {

    private final ObjectMapper json;

    Responses(ObjectMapper json) {
        this.json = json;
    }

    /**
     * ADR-0175 §4: an expired session and a request that never signed in are the same {@code 401}, in
     * ADR-0060's problem+json, and the shell asks {@code /session} why (ADR-0177 §4).
     */
    void notSignedIn(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Not signed in.");
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        json.writeValue(response.getOutputStream(), problem);
    }

    /**
     * ADR-0176 §4: a browser that must sign in while discovery fails is told which issuer could not be
     * reached, and nothing else about the install. The redirect it replaces would have disclosed the
     * same URL.
     */
    void providerUnreachable(HttpServletResponse response, URI issuer) throws IOException {
        page(response, HttpStatus.SERVICE_UNAVAILABLE, "Nodqora cannot reach its identity provider at <code>"
                + HtmlUtils.htmlEscape(issuer.toString()) + "</code>. Signing in will work again once it answers.");
    }

    /**
     * A callback the provider answered with an error, or whose code or ID token did not check out. It
     * is a page and not a redirect: sending the browser back to the provider would loop for as long as
     * the fault lasts, silently where the provider's own session is alive.
     */
    void signInFailed(HttpServletResponse response) throws IOException {
        page(response, HttpStatus.UNAUTHORIZED, "Signing in did not succeed. Reload to try again.");
    }

    /**
     * A redirect whose {@code Location} is exactly what is given. {@code sendRedirect} would hand a
     * relative path to Tomcat, which Boot configures to make it absolute from the request's
     * {@code Host} — a scheme and host guessed from the socket, which ADR-0176 §1 keeps out of every
     * redirect sign-in issues.
     */
    static void redirect(HttpServletResponse response, String location) {
        response.setStatus(HttpStatus.FOUND.value());
        response.setHeader(HttpHeaders.LOCATION, location);
    }

    private static void page(HttpServletResponse response, HttpStatus status, String sentence) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.getWriter().write("""
                <!doctype html>
                <html lang="en">
                <meta charset="utf-8">
                <meta name="color-scheme" content="light dark">
                <title>Nodqora</title>
                <p>%s</p>
                </html>
                """.formatted(sentence));
    }
}
