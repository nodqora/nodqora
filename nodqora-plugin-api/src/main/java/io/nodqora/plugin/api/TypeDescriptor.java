package io.nodqora.plugin.api;

import java.util.Objects;

/**
 * Presentation for one node type (ADR-0001). {@code icon} is a <em>name</em> from a fixed frontend
 * icon set, never a shipped asset; {@code source} is a plugin id.
 *
 * <p>Descriptors ride in the DiscoveryResult and are stored as snapshot entries, so the global set
 * served inside {@code /graph} is a read-time projection and is as mortal as everything else
 * (ADR-0077).
 */
public record TypeDescriptor(String type, String label, String category, String icon, String source) {

    public TypeDescriptor {
        Objects.requireNonNull(type, "type");
    }
}
