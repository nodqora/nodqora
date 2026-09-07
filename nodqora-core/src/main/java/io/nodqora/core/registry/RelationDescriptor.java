// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.registry;

/**
 * Registry entry for one relation: orientation and phrasing for both reading directions
 * (ADR-0002).
 *
 * <p>Orientation affects <em>wording only</em>. It never affects traversal — downstream is always
 * "follow outgoing edges", because edges are stored flow-directed.
 */
public record RelationDescriptor(
        String relation, Orientation orientation, String forwardPhrasing, String reversePhrasing) {

    public enum Orientation {
        /** Reads with the flow. */
        FORWARD,
        /** Reads against the flow: the consumer is the grammatical subject. */
        REVERSED,
    }
}
