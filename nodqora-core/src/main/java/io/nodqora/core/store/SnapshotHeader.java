package io.nodqora.core.store;

import io.nodqora.plugin.api.OutcomeStatus;
import java.time.Instant;
import java.util.List;

/**
 * One {@code (plugin, environment)} pair's last poll (ADR-0071, ADR-0086).
 *
 * <p>The header is written on <em>every</em> poll, whatever the outcome, which is what gives the
 * store three readings where it would otherwise have one:
 *
 * <ul>
 *   <li>no header at all — nobody has ever polled this pair;
 *   <li>a {@code FAILED} header with no entries — we tried and could not look;
 *   <li>a {@code COMPLETE} header with no entries — we looked and there is genuinely nothing here.
 * </ul>
 *
 * <p>Collapsing the first two tells an operator with a typo'd credential to wait five minutes for a
 * poll that will never succeed.
 */
public record SnapshotHeader(
        String environmentKey, String pluginId, OutcomeStatus outcome, List<String> reasons, Instant recordedAt) {}
