package io.nodqora.plugin.kafka;

import java.util.List;
import java.util.Objects;

/**
 * One routed consumer group's committed offsets, partition by partition.
 *
 * <p><b>There is no state field and no member count</b>, and both absences are decisions.
 * ADR-0029 forbids this plugin ever emitting {@code DISABLED}, because an {@code EMPTY} group is the
 * exact state of a deliberately scaled-down consumer and of a crashed one alike — so the group's
 * state cannot be read into health without inventing evidence of intent. ADR-0028's metric
 * allow-list for {@code kafka} is {@code maxConsumerLag}, singular, so there is no key a member count
 * could leave through either. Carrying either field would be data with nowhere to go.
 */
public record ObservedGroup(String groupId, List<PartitionOffsets> partitions) {

    public ObservedGroup {
        Objects.requireNonNull(groupId, "groupId");
        partitions = partitions == null ? List.of() : List.copyOf(partitions);
    }
}
