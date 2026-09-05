package io.nodqora.plugin.kafka;

import java.util.Objects;

/**
 * One partition's committed offset beside its high watermark — the two numbers lag is the difference
 * of, recorded rather than the difference itself.
 *
 * <p>Recording the pair rather than a lag keeps ADR-0025's two caveats <b>above</b> the seam, where
 * they can be tested:
 *
 * <ul>
 *   <li>{@code committedOffset == null} means the group has committed nothing on this partition. It
 *       is <b>skipped</b>, never counted as zero — a group that has not started is not a group that
 *       is caught up.
 *   <li>A committed offset compared against a watermark sampled a moment earlier legitimately goes
 *       <b>negative</b>. It clamps to zero and reads {@code HEALTHY}.
 * </ul>
 */
public record PartitionOffsets(String topic, int partition, Long committedOffset, Long endOffset) {

    public PartitionOffsets {
        Objects.requireNonNull(topic, "topic");
    }

    /** {@code null} when this partition abstains — no commit, or no watermark to compare it to. */
    public Long lag() {
        if (committedOffset == null || endOffset == null) {
            return null;
        }
        return Math.max(0L, endOffset - committedOffset);
    }
}
