// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * ADR-0174 §4: the assembly adds its own public paths. These are the frontend build's static assets —
 * the public Apache-2.0 bundle and its icons, carrying nothing install-specific (ADR-0173 §3) — and
 * they are served whether or not anyone has signed in, so the first-run screen and the shell can load
 * before sign-in says anything.
 *
 * <p>{@code index.html} is not among them. It is the application, reached through {@code /} and
 * {@link CommunityFrontend}'s routes, and those ask for sign-in.
 *
 * <p>Core's chain matches every request and is ordered last, so this one, matching only these paths,
 * is consulted first. Adding a public frontend path is two edits here: the forward or the file, and
 * this list. Forgetting the second fails closed.
 *
 * <p>Spring Security's own {@code Cache-Control} is off here, because it would replace
 * {@link FrontendCacheHeaders}' year of immutability on the content-hashed bundles.
 */
@Configuration
class CommunityFrontendAssets {

    @Bean
    @Order(0)
    SecurityFilterChain publicFrontendAssets(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/assets/**", "/icon.png", "/mark.png", "/apple-touch-icon.png")
                .authorizeHttpRequests(paths -> paths.anyRequest().permitAll())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers.cacheControl(HeadersConfigurer.CacheControlConfig::disable))
                .build();
    }
}
