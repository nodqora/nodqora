package io.nodqora.plugin.yaml;

/**
 * ADR-0014, ADR-0061: one directory per environment, bound from the config file at startup.
 *
 * <pre>yaml: { dir: /etc/nodqora/topology/production }</pre>
 */
public record YamlTopologyConfig(String dir) {}
