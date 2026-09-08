// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * ADR-0152: what the image ships, asserted against the file rather than against a bound context.
 *
 * <p>A Spring context cannot make this assertion here, because the {@code test} task loads the demo
 * through {@code spring.config.additional-location} — every other test in this package depends on
 * that. So this reads the shipped resource directly, which is also the thing an operator's mounted
 * file merges with.
 */
class ShippedConfigurationTest {

    private final Properties shipped = shipped();

    /**
     * The decision's whole point. Spring merges property sources, so an environment declared here
     * could be overridden by a mounted file but never removed: its key would survive into
     * ADR-0055's roster and ADR-0085's {@code plugins[]}, and ADR-0093 would send bare {@code /} to
     * it on every install. A default a user cannot decline is not a default.
     */
    @Test
    void the_image_ships_no_environments() {
        assertThat(shipped.stringPropertyNames())
                .as("a baked-in environment cannot be deleted by a user's mounted file, only added to")
                .noneMatch(key -> key.startsWith("nodqora.environments"));
    }

    /**
     * The other half of the same contract. A mounted file carries {@code nodqora.environments} and
     * nothing else, which only works if everything else is still here to merge with — above all the
     * two plugin orders, which name the registered plugins and which
     * {@code BoundConfiguration.requireOrdersCoverEveryPlugin} rejects the process over.
     */
    @Test
    void the_image_ships_everything_a_mounted_file_should_not_have_to_restate() {
        assertThat(shipped)
                .containsKeys(
                        "nodqora.plugins.registry-order[0]",
                        "nodqora.plugins.precedence[0]",
                        "nodqora.refresh.discovery",
                        "nodqora.refresh.health",
                        "spring.flyway.enabled",
                        "spring.jackson.default-property-inclusion",
                        "spring.mvc.problemdetails.enabled",
                        "spring.web.resources.cache.cachecontrol.no-cache");
    }

    private static Properties shipped() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yaml"));
        yaml.afterPropertiesSet();
        return yaml.getObject();
    }
}
