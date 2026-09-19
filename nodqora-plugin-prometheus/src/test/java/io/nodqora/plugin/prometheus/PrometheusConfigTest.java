// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.prometheus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PrometheusConfigTest {

    @Test
    void neither_credential_is_in_what_the_config_prints() {
        // By the time this record exists its references have resolved, so a careless log line
        // would print the credential itself. It says that there is one.
        assertThat(new PrometheusConfig("https://prom.internal", new PrometheusConfig.Auth("scraper", "hunter2"), null))
                .asString()
                .contains("https://prom.internal", "scraper", "<redacted>")
                .doesNotContain("hunter2");
        assertThat(new PrometheusConfig("https://prom.internal", null, "hunter2"))
                .asString()
                .contains("bearerToken=<redacted>")
                .doesNotContain("hunter2");
    }
}
