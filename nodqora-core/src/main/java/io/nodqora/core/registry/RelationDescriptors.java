// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.registry;

import static io.nodqora.core.registry.RelationDescriptor.Orientation.FORWARD;
import static io.nodqora.core.registry.RelationDescriptor.Orientation.REVERSED;

import java.util.List;

/**
 * The six MVP relations, seeded at startup as core built-ins rather than registered by a plugin
 * (ADR-0062).
 *
 * <p>{@code relation} stays an open string in the model; the registry is simply not empty. Two
 * reasons it could not be plugin-registered: with no escape-hatch verb a plugin-registered relation
 * would be configuration nothing could emit, and {@code SOURCES_FROM} is emitted by two plugins, so
 * registration would give one relation two definitions whose phrasing could disagree — making the
 * descriptor a node renders with depend on poll order.
 *
 * <p>Naming relations here is not a breach of ADR-0015: a relation is core vocabulary, and nothing
 * in this list names a technology.
 */
public final class RelationDescriptors {

    private static final List<RelationDescriptor> BUILT_INS = List.of(
            new RelationDescriptor("CALLS", FORWARD, "calls", "is called by"),
            new RelationDescriptor("PRODUCES_TO", FORWARD, "produces to", "is produced by"),
            new RelationDescriptor("CONSUMES_FROM", REVERSED, "delivers to", "consumes from"),
            new RelationDescriptor("SOURCES_FROM", REVERSED, "feeds", "sources from"),
            new RelationDescriptor("WRITES_TO", FORWARD, "writes to", "is written by"),
            new RelationDescriptor("QUERIES", REVERSED, "is queried by", "queries"));

    private RelationDescriptors() {}

    public static List<RelationDescriptor> builtIns() {
        return BUILT_INS;
    }
}
