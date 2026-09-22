// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.signin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * ADR-0175 §3's absolute limit, against core's {@link Clock} and not the system's (ADR-0178 §4). The
 * wiring is proven in {@code nodqora-app}; this is the check itself, which needs neither an assembly
 * nor a database (ADR-0178 §7).
 */
class AbsoluteLifetimeTest {

    private final MockHttpSession session = new MockHttpSession();
    private final Instant began = Instant.ofEpochMilli(session.getCreationTime());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void a_session_inside_its_limit_is_left_alone() throws Exception {
        signedIn();

        filterAt(began.plus(Duration.ofHours(12)).minusMillis(1));

        assertThat(session.isInvalid()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    void a_session_at_its_limit_ends_and_the_request_goes_on_unauthenticated() throws Exception {
        signedIn();

        MockFilterChain chain = filterAt(began.plus(Duration.ofHours(12)));

        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(chain.getRequest()).as("the request is not refused here; the chain decides").isNotNull();
    }

    @Test
    void a_request_without_a_session_is_not_given_one() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        new AbsoluteLifetime(Duration.ofHours(12), Clock.fixed(began.plus(Duration.ofDays(1)), ZoneOffset.UTC))
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getSession(false)).isNull();
    }

    private void signedIn() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("ada", null, "OIDC_USER"));
    }

    private MockFilterChain filterAt(Instant now) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        MockFilterChain chain = new MockFilterChain();
        new AbsoluteLifetime(Duration.ofHours(12), Clock.fixed(now, ZoneOffset.UTC))
                .doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }
}
