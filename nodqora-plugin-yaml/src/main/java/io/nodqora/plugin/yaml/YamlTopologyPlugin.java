package io.nodqora.plugin.yaml;

import io.nodqora.plugin.api.DiscoveryCapability;
import io.nodqora.plugin.api.DiscoveryRequest;
import io.nodqora.plugin.api.DiscoveryResult;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * The YAML topology loader is a plugin like any other (ADR-0011): id {@code yaml}, declaring
 * Discovery and <em>not</em> Health, contributing descriptors and owners in the same result.
 *
 * <p>Its nodes being permanently {@code UNKNOWN} is a consequence of the capability set, not a rule
 * anyone wrote. It is polled on the topology cadence like everything else, re-reading its directory
 * each run rather than being driven by a file watcher.
 */
@Component
public class YamlTopologyPlugin implements DiscoveryCapability<YamlTopologyConfig> {

    private final YamlTopologyLoader loader = new YamlTopologyLoader();

    @Override
    public String id() {
        return YamlTopologyLoader.PLUGIN_ID;
    }

    @Override
    public String displayLabel() {
        return "YAML topology";
    }

    @Override
    public Class<YamlTopologyConfig> configType() {
        return YamlTopologyConfig.class;
    }

    @Override
    public DiscoveryResult discover(DiscoveryRequest<YamlTopologyConfig> request) {
        return loader.load(request.environmentKey(), Path.of(request.config().dir()));
    }
}
