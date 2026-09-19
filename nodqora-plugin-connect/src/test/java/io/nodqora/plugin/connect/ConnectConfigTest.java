// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.connect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConnectConfigTest {

    @Test
    void the_password_is_not_in_what_the_config_prints() {
        ConnectConfig config = new ConnectConfig(
                "https://connect.internal",
                new ConnectConfig.Auth("connect", "hunter2"),
                new ConnectConfig.Connectors(List.of("payments-"), List.of()),
                null,
                null);

        assertThat(config).asString().contains("https://connect.internal", "<redacted>").doesNotContain("hunter2");
    }
}
