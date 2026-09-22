// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.signin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * ADR-0175 §3's absolute limit: a session ends a fixed time after it began, however busy it is.
 *
 * <p>The canvas polls, so an open tab touches its session every few seconds and Spring Session's
 * idle limit never fires while it is open. This one does, and it is the revocation bound: a user
 * disabled at the provider keeps reading until it passes, because nothing the session holds lets
 * Nodqora ask the provider again.
 *
 * <p>It reads core's {@link Clock} rather than the system's, so a test moves time instead of waiting
 * for it (ADR-0178 §4). A session past its limit is invalidated and the request goes on
 * unauthenticated, which the chain turns into a {@code 401} or a sign-in like any other.
 */
final class AbsoluteLifetime extends OncePerRequestFilter {

    private final Duration absolute;
    private final Clock clock;

    AbsoluteLifetime(Duration absolute, Clock clock) {
        this.absolute = absolute;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && expired(session)) {
            session.invalidate();
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }

    private boolean expired(HttpSession session) {
        return !clock.instant().isBefore(Instant.ofEpochMilli(session.getCreationTime()).plus(absolute));
    }
}
