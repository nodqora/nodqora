// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.signin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializationFailedException;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * A session is a row in Postgres behind one opaque cookie, and a cache that may be lost at any time
 * (ADR-0175). Spring Session JDBC stores it; its tables come from core's own migration.
 */
@Configuration
class Sessions {

    private static final Logger log = LoggerFactory.getLogger(Sessions.class);

    /**
     * ADR-0175 §1: one cookie, opaque, {@code HttpOnly} and {@code SameSite=Lax}. It is {@code Secure}
     * when {@code base-url} is {@code https} — the scheme the browser reaches Nodqora on, which the
     * socket behind a TLS-terminating proxy does not know (ADR-0176 §1).
     */
    @Bean
    CookieSerializer sessionCookie(SignIn signIn) {
        DefaultCookieSerializer cookie = new DefaultCookieSerializer();
        cookie.setUseHttpOnlyCookie(true);
        cookie.setSameSite("Lax");
        if (signIn instanceof SignIn.Provider provider) {
            cookie.setUseSecureCookie("https".equals(provider.oidc().baseUrl().getScheme()));
        }
        return cookie;
    }

    /**
     * ADR-0175 §3's idle limit is Spring Session's own, so what Nodqora owns is passing
     * {@code session.idle} through. Ordered last, after Boot's customizer applies
     * {@code server.servlet.session.timeout}, which is not a key Nodqora reads.
     */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SessionRepositoryCustomizer<JdbcIndexedSessionRepository> idleLimit(SignIn signIn) {
        return sessions -> {
            if (signIn instanceof SignIn.Provider provider) {
                sessions.setDefaultMaxInactiveInterval(provider.session().idle());
            }
        };
    }

    /**
     * ADR-0175 §7: a session row that cannot be read is no session. Spring Session JDBC keeps
     * attributes in JDK serialization, whose compatibility is Spring Security's; an upgrade that
     * changes it would otherwise throw on every request that carries an old cookie. An attribute that
     * does not deserialize reads as absent, so the request goes on unauthenticated and signs in again,
     * and the row is overwritten by that sign-in or expires on its idle limit.
     *
     * <p>Spring Session looks this up by name.
     */
    @Bean("springSessionConversionService")
    GenericConversionService unreadableSessionsAreNoSessions() {
        DeserializingConverter deserializer = new DeserializingConverter(Sessions.class.getClassLoader());
        GenericConversionService conversions = new GenericConversionService();
        conversions.addConverter(Object.class, byte[].class, new SerializingConverter());
        conversions.addConverter(byte[].class, Object.class, bytes -> {
            try {
                return deserializer.convert(bytes);
            } catch (SerializationFailedException e) {
                log.info("A session attribute could not be read, and is treated as absent: {}", e.getMessage());
                return null;
            }
        });
        return conversions;
    }
}
