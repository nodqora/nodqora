package io.nodqora.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nodqora.core.registry.PluginOrder;
import io.nodqora.plugin.api.DiscoveryCapability;
import io.nodqora.plugin.api.HealthCapability;
import io.nodqora.plugin.api.Plugin;
import jakarta.annotation.PostConstruct;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The bound config: which environments exist, which plugins are configured in each, and each
 * plugin's own config object.
 *
 * <p>Plugins are collected by injecting {@code List<Plugin>} — ADR-0015's registration mechanism, and
 * the reason a future out-of-process plugin would change only that mechanism and not the interfaces.
 * A capability is present <em>iff</em> the bean implements the interface (ADR-0010); there is no
 * negotiation and no declaration to keep in step.
 *
 * <p>Binding happens once, at startup, so bad configuration fails there rather than at first poll.
 */
@Component
public class BoundConfiguration {

    private final NodqoraProperties properties;
    private final List<Plugin<?>> plugins;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final SecretReferences secrets;

    private final Map<String, Map<String, Object>> boundConfigs = new LinkedHashMap<>();

    @Autowired
    public BoundConfiguration(
            NodqoraProperties properties, List<Plugin<?>> plugins, ObjectMapper mapper, Validator validator) {
        this(properties, plugins, mapper, validator, SecretReferences.fromProcess());
    }

    BoundConfiguration(
            NodqoraProperties properties,
            List<Plugin<?>> plugins,
            ObjectMapper mapper,
            Validator validator,
            SecretReferences secrets) {
        this.properties = properties;
        this.plugins = List.copyOf(plugins);
        this.mapper = mapper;
        this.validator = validator;
        this.secrets = secrets;
    }

    @PostConstruct
    void bind() {
        requireOrdersCoverEveryPlugin();
        properties.environments().forEach((environmentKey, environment) -> {
            Map<String, Object> bound = new LinkedHashMap<>();
            environment.plugins().forEach((pluginId, slice) -> {
                Plugin<?> plugin = plugin(pluginId).orElseThrow(() -> new IllegalStateException(
                        "environment %s configures unknown plugin '%s'".formatted(environmentKey, pluginId)));
                bound.put(pluginId, validated(environmentKey, plugin, slice));
            });
            boundConfigs.put(environmentKey, bound);
        });
    }

    /**
     * ADR-0014: a plugin declares {@code Class<C> configType()} and the core binds its slice of the
     * file into it <em>with Bean Validation</em>, so bad configuration fails at startup rather than
     * at first poll. Secret references resolve first, because a reference is not a value until it
     * has been looked up and a constraint on the value would otherwise see {@code "${env:...}"}.
     */
    private Object validated(String environmentKey, Plugin<?> plugin, Map<String, Object> slice) {
        Object config = mapper.convertValue(
                ConfigLists.restore(secrets.resolveConfig(slice)), plugin.configType());
        Set<ConstraintViolation<Object>> violations = validator.validate(config);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("environment %s, plugin %s: %s".formatted(
                    environmentKey,
                    plugin.id(),
                    violations.stream()
                            .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                            .sorted()
                            .collect(Collectors.joining("; "))));
        }
        return config;
    }

    /**
     * The two orders must name exactly the registered plugins. A typo in either is otherwise silent:
     * a misspelled id sorts last, and the graph goes on being folded with the wrong precedence.
     */
    private void requireOrdersCoverEveryPlugin() {
        List<String> registered = plugins.stream().map(Plugin::id).sorted().toList();
        for (Map.Entry<String, List<String>> order : Map.of(
                        "nodqora.plugins.registry-order", properties.plugins().registryOrder(),
                        "nodqora.plugins.precedence", properties.plugins().precedence())
                .entrySet()) {
            List<String> configured = order.getValue().stream().sorted().toList();
            if (!configured.equals(registered)) {
                throw new IllegalStateException("%s is %s but the registered plugins are %s"
                        .formatted(order.getKey(), order.getValue(), registered));
            }
        }
    }

    public PluginOrder order() {
        return new PluginOrder(properties.plugins().registryOrder(), properties.plugins().precedence());
    }

    /** ADR-0055: the roster the API publishes is {@code {key, displayName}} and nothing else. */
    public List<EnvironmentRoster> environments() {
        return properties.environments().entrySet().stream()
                .map(entry -> new EnvironmentRoster(
                        entry.getKey(),
                        Optional.ofNullable(entry.getValue().displayName()).orElse(entry.getKey())))
                .toList();
    }

    public boolean knows(String environmentKey) {
        return properties.environments().containsKey(environmentKey);
    }

    public NodqoraProperties.Refresh refresh() {
        return properties.refresh();
    }

    public List<Plugin<?>> registeredPlugins() {
        return plugins.stream()
                .sorted(Comparator.comparingInt(plugin -> order().registryRank(plugin.id())))
                .toList();
    }

    public Optional<Plugin<?>> plugin(String pluginId) {
        return plugins.stream().filter(plugin -> plugin.id().equals(pluginId)).findFirst();
    }

    /** Every {@code (plugin, environment)} pair configured for discovery, in registry order. */
    public List<ConfiguredCapability> discoveryPairs(String environmentKey) {
        return configuredPairs(environmentKey, DiscoveryCapability.class);
    }

    public List<ConfiguredCapability> healthPairs(String environmentKey) {
        return configuredPairs(environmentKey, HealthCapability.class);
    }

    private List<ConfiguredCapability> configuredPairs(String environmentKey, Class<?> capability) {
        List<ConfiguredCapability> pairs = new ArrayList<>();
        boundConfigs.getOrDefault(environmentKey, Map.of()).forEach((pluginId, config) -> plugin(pluginId)
                .filter(capability::isInstance)
                .ifPresent(plugin -> pairs.add(new ConfiguredCapability(environmentKey, plugin, config))));
        pairs.sort(Comparator.comparingInt(pair -> order().registryRank(pair.plugin().id())));
        return pairs;
    }

    /** ADR-0077's second tiebreak: the environment order as declared in the config file. */
    public Comparator<String> byEnvironmentOrder() {
        List<String> declared = List.copyOf(properties.environments().keySet());
        return Comparator.comparingInt(key -> {
            int index = declared.indexOf(key);
            return index < 0 ? declared.size() : index;
        });
    }

    public Comparator<String> byPluginPrecedence() {
        PluginOrder order = order();
        return Comparator.comparingInt(order::precedenceRank);
    }

    public record EnvironmentRoster(String key, String displayName) {}

    public record ConfiguredCapability(String environmentKey, Plugin<?> plugin, Object config) {}
}
