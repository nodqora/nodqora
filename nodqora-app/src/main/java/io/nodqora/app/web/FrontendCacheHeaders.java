// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app.web;

import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * ADR-0151: {@code /assets/**} is immutable for a year; everything else static revalidates.
 *
 * <p>Vite content-hashes the bundles it emits ({@code assets/index-a1b2c3.js}), so for those the
 * filename <em>is</em> the version and re-fetching one can only ever return the same bytes.
 * {@code index.html} has a fixed name and names the hashes, so it is the one file that must be
 * checked — otherwise an upgraded image serves a client an {@code index.html} it cached yesterday,
 * pointing at bundles that no longer exist in the new image. The symptom is a blank canvas that only
 * a hard refresh clears, and ADR-0147 makes the latest release the whole support surface, so it
 * would arrive as a support request rather than a bug report.
 *
 * <p>Only {@code /assets/**} is registered here. Everything else keeps Spring Boot's own
 * {@code /**} handler, whose {@code Cache-Control} is set to {@code no-cache} in
 * {@code application.yaml} — the pair is deliberate rather than clumsy: re-registering {@code /**}
 * would replace Boot's handler and take the welcome-page wiring with it, and pattern matching is by
 * specificity rather than registration order, so the narrower pattern wins here regardless.
 */
@Configuration
class FrontendCacheHeaders implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
    }
}
