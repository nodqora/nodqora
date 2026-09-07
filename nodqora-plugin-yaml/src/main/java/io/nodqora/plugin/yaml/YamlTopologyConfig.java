// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.yaml;

import jakarta.validation.constraints.NotBlank;

/**
 * ADR-0014, ADR-0061: one directory per environment, bound from the config file at startup and
 * validated there, so a missing {@code dir:} fails the process rather than the first poll.
 *
 * <pre>yaml: { dir: /etc/nodqora/topology/production }</pre>
 *
 * <p>Whether the directory <em>exists</em> is deliberately not a startup constraint: an unreadable
 * directory is a {@code FAILED} snapshot (ADR-0064), which retains the last good one, and a
 * mount that arrives late should not stop the process from serving the rest of the graph.
 */
public record YamlTopologyConfig(@NotBlank String dir) {}
