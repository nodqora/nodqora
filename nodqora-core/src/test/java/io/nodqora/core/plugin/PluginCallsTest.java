// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PluginCallsTest {

    @Test
    void a_failure_keeps_its_cause_and_loses_the_credentials_in_any_url_it_quotes() {
        // What a plugin throws becomes a reason the API serves and the shell shows. A client that
        // quotes the URL it could not reach quotes the userinfo an operator wrote into it.
        String reason = PluginCalls.within(
                Duration.ofSeconds(5),
                "prometheus health",
                () -> {
                    throw new IllegalStateException(
                            "GET https://scraper:hunter2@prom.internal:9090/api/v1/query refused");
                },
                message -> message);

        assertThat(reason)
                .contains("prometheus health threw IllegalStateException")
                .contains("https://****@prom.internal:9090/api/v1/query refused")
                .doesNotContain("hunter2")
                .doesNotContain("scraper");
    }

    @Test
    void a_url_with_no_userinfo_is_quoted_as_it_was() {
        String reason = PluginCalls.within(
                Duration.ofSeconds(5),
                "connect discovery",
                () -> {
                    throw new IllegalStateException("GET http://connect.internal:8083/connectors?x=a@b refused");
                },
                message -> message);

        assertThat(reason).contains("http://connect.internal:8083/connectors?x=a@b refused");
    }
}
