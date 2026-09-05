package io.nodqora.core.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-environment plugin configuration, declared in a file and bound at startup (ADR-0014). The
 * database holds only the environment roster; a write API for integration configuration on an
 * unauthenticated service is a credential-entry form open to anyone who reaches the ingress.
 *
 * <p>{@code environments} preserves declaration order, which is the tiebreak ADR-0077 uses for a
 * contested type descriptor after plugin precedence.
 */
@ConfigurationProperties("nodqora")
public record NodqoraProperties(Plugins plugins, Refresh refresh, Map<String, Environment> environments) {

    public NodqoraProperties {
        plugins = plugins == null ? new Plugins(List.of(), List.of()) : plugins;
        refresh = refresh == null ? new Refresh(null, null) : refresh;
        environments = environments == null ? Map.of() : new LinkedHashMap<>(environments);
    }

    /**
     * The two orders over plugin ids (ADR-0011, ADR-0044, ADR-0050). They are configuration rather
     * than constants because ADR-0011 says precedence is "expressed as config over plugin ids", and
     * because ADR-0015 forbids the core naming a plugin in its own source at all.
     *
     * <p>Neither list carries a Bean Validation constraint, because
     * {@code BoundConfiguration.requireOrdersCoverEveryPlugin} already checks something strictly
     * stronger than non-emptiness — that each names <em>exactly</em> the registered plugins — and
     * says which ids are missing when it fails.
     */
    public record Plugins(List<String> registryOrder, List<String> precedence) {}

    /**
     * ADR-0035, ADR-0042: 5 minute discovery, 30 second health, both file-declared and both global
     * (ADR-0103). The timeout is derived as half the interval rather than configured, so it cannot
     * be misconfigured above it.
     */
    public record Refresh(Duration discovery, Duration health) {

        public Refresh {
            discovery = discovery == null ? Duration.ofMinutes(5) : discovery;
            health = health == null ? Duration.ofSeconds(30) : health;
        }
    }

    /**
     * One environment. {@code plugins} is keyed by plugin id and holds that plugin's own config
     * slice, bound into its declared {@code configType()} — so bad configuration fails at startup
     * rather than at first poll.
     */
    public record Environment(String displayName, Map<String, Map<String, Object>> plugins) {

        public Environment {
            plugins = plugins == null ? Map.of() : new LinkedHashMap<>(plugins);
        }
    }
}
