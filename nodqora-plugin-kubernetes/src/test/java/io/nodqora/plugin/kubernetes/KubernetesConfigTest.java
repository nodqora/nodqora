// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KubernetesConfigTest {

    @Test
    void the_kubeconfig_is_not_in_what_the_config_prints() {
        KubernetesConfig config = new KubernetesConfig(
                List.of("payments-prod"), "users:\n- user:\n    token: hunter2\n", "homelab", null, null, null);

        assertThat(config).asString().contains("payments-prod", "kubeconfig=<redacted>").doesNotContain("hunter2");
    }
}
