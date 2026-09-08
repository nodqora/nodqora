// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * ADR-0151: the deployable serves the frontend, and the server-side half of ADR-0092's grammar.
 *
 * <p>The documents under {@code src/test/resources/static/} stand in for the Vite output, which
 * ADR-0151 leaves outside Gradle and copies into the image's Gradle stage — so a clone has no
 * {@code index.html} to forward to and the test classpath has to supply one.
 */
class FrontendRoutingTest extends NodqoraIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Test
    void a_deep_link_refreshed_or_pasted_into_slack_returns_the_application() {
        // ADR-0092 made the environment a path segment precisely so a URL cannot lose its scope in
        // transit — which means the server sees it, and must hand back the app rather than a 404.
        ResponseEntity<String> response = http.getForEntity("/environments/production?node=orders", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("nodqora-frontend-stand-in");
    }

    @Test
    void a_trailing_slash_routes_the_same_way_the_client_router_does() {
        // routing/route.ts matches /^\/environments\/([^/]+)\/?$/, and Spring Boot 3 stopped
        // matching the trailing slash implicitly. Left alone, the two disagree and the URL is
        // routable in the client but a 404 on refresh.
        assertThat(http.getForEntity("/environments/production/", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void an_unknown_environment_is_the_frontends_screen_to_draw_not_the_servers() {
        // ADR-0093 renders a designed not-found naming the environments that do exist. The server
        // does not consult the roster: two places deciding this is two places that can disagree.
        ResponseEntity<String> response = http.getForEntity("/environments/no-such-environment", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("nodqora-frontend-stand-in");
    }

    @Test
    void a_content_hashed_bundle_is_immutable_and_the_document_naming_it_is_not() {
        // The two halves of ADR-0151's cache rule. Together they are what makes an upgrade safe:
        // the client always re-reads the file that names the hashes, and never re-reads a hash.
        ResponseEntity<String> bundle = http.getForEntity("/assets/index-a1b2c3.js", String.class);
        assertThat(bundle.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bundle.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL))
                .contains("max-age=31536000")
                .contains("immutable");

        ResponseEntity<String> document = http.getForEntity("/environments/production", String.class);
        assertThat(document.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("no-cache");
    }

    @Test
    void a_missing_bundle_is_a_404_rather_than_the_application_with_a_200() {
        // The reason ADR-0151 forwards a named grammar instead of installing a catch-all resolver.
        // Under a catch-all this returns index.html with a 200, and a broken deploy presents as a
        // blank page with no failing request anywhere in the network tab.
        assertThat(http.getForEntity("/assets/index-deadbee.js", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void a_path_outside_the_grammar_is_a_404() {
        assertThat(http.getForEntity("/environments", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/environments/production/nodes/orders", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void the_api_still_answers_in_problem_json_and_is_never_shadowed_by_the_forward() {
        // ADR-0060's error contract is the thing a catch-all would quietly launder into HTML.
        ResponseEntity<String> response =
                http.getForEntity("/api/environments/no-such-environment/graph", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).doesNotContain("nodqora-frontend-stand-in");
    }

    @Test
    void bare_root_serves_the_application_so_adr_0093_can_redirect_from_inside_it() {
        // ADR-0093 sends bare `/` to environments[0].key, and that decision lives in the client —
        // it needs the roster from /api/meta, which the server would have to duplicate to answer.
        ResponseEntity<String> response = http.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("nodqora-frontend-stand-in");
    }
}
