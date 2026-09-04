package io.nodqora.core.api;

import io.nodqora.core.registry.RelationDescriptor;
import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.Link;
import io.nodqora.plugin.api.TypeDescriptor;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The wire shapes for ADR-0053's three GETs.
 *
 * <p>No version segment, no envelope (ADR-0060). The payloads already carry their own
 * {@code plugins[]} outcome block and their own freshness, so an envelope would be a second, emptier
 * home for the same kind of thing.
 *
 * <p><b>Nulls go over the wire unresolved</b> (ADR-0058). The API never fabricates a value no plugin
 * supplied: {@code payments-api}'s real YAML-supplied {@code displayName} <em>is</em> the string
 * {@code payments-api}, and a server-side fallback would make that permanently indistinguishable
 * from a node nobody has ever named. The frontend renders {@code displayName ?? key}.
 */
public final class ApiDocuments {

    private ApiDocuments() {}

    // ---------------------------------------------------------------- /api/meta

    /**
     * ADR-0055: split by lifetime. Both rosters are static until a restart (ADR-0014), so they are
     * fetched once here, while descriptors appear as discovery runs and therefore ride with the
     * graph instead.
     */
    public record MetaDocument(List<EnvironmentRef> environments, List<PluginRef> plugins, Refresh refresh) {}

    /** ADR-0014, ADR-0055: key and display name only. Never connection config, never secrets. */
    public record EnvironmentRef(String key, String displayName) {}

    public record PluginRef(String id, String displayLabel, List<String> capabilities) {}

    /**
     * ADR-0059: the server publishes its own intervals so the frontend does not hold a copy of file
     * config that disagrees, silently, the first time anyone tunes a plugin's cadence.
     */
    public record Refresh(long graphSeconds, long stateSeconds) {}

    // ---------------------------------------------------------------- /graph

    /**
     * The whole slow half for one environment: complete Nodes, Edges and Owners, plus the descriptors
     * needed to render them and the outcome that explains them (ADR-0053).
     *
     * <p>Full nodes remove a loading state from the drawer: a lazily-fetched inspector makes "No
     * owner recorded" and "not loaded yet" the same pixels.
     *
     * <p>There is no ETag (ADR-0076). {@code confirmedAt} advances for every node on every healthy
     * poll, so the document changes every cadence by construction and a 304 would fire roughly never
     * — while hashing only the folded content would return 304 through a plugin going blind, which
     * is the exact failure the outcome block exists to prevent, implemented as a status code.
     */
    public record GraphDocument(
            EnvironmentRef environment,
            List<NodeDocument> nodes,
            List<EdgeDocument> edges,
            List<OwnerDocument> owners,
            List<TypeDescriptor> typeDescriptors,
            List<RelationDescriptor> relationDescriptors,
            List<PluginOutcome> plugins) {}

    public record NodeDocument(
            String key,
            String type,
            String displayName,
            String description,
            String ownerKey,
            List<Link> links,
            List<Backing> backings,
            Map<String, Object> metadata,
            List<Source> sources,
            Instant discoveredAt,
            Instant updatedAt) {}

    /**
     * ADR-0056: {@code sources[]} widens on the wire from a plugin id to
     * {@code {plugin, confirmedAt}}, where {@code confirmedAt} is when that plugin's snapshot last
     * actually carried the key. It is a read-time projection over the snapshot store, never a stored
     * field — a timestamp inside the folded row would change on every poll and turn ADR-0050's
     * {@code updatedAt} into the poll clock it exists to prevent.
     */
    public record Source(String plugin, Instant confirmedAt) {}

    /** An edge's {@code sources[]} stays a plain list: ADR-0056 widened nodes, and only nodes. */
    public record EdgeDocument(
            String fromKey,
            String toKey,
            String relation,
            Map<String, Object> metadata,
            List<String> sources,
            Instant discoveredAt,
            Instant updatedAt) {}

    public record OwnerDocument(String key, String displayName, String channel, String onCall) {}

    /**
     * ADR-0085: a config roster left-joined with the store, carrying an entry for every configured
     * {@code (plugin, capability)} pair whether or not it has ever reported.
     *
     * <p>An unreported pair ships {@code outcome: null} and {@code recordedAt: null}. A missing entry
     * could not mean "never looked", because under ADR-0046 an absent thing already means the plugin
     * looked and it was not there — the same collision ADR-0057 resolved one level down. A fourth
     * {@code PENDING} outcome is disqualified rather than beaten: every {@code outcome} value names a
     * store effect, and this state is the absence of a result.
     */
    public record PluginOutcome(
            String plugin, String capability, String outcome, List<String> reasons, Instant recordedAt) {}

    // ---------------------------------------------------------------- /state

    /**
     * ADR-0057: an entry for <b>every</b> node key in the environment, synthesizing the ADR-0028 join
     * result for the unobserved. Absence is already load-bearing and already means something else —
     * a key missing from {@code /graph} means <em>deleted</em> — so two payloads polled at different
     * rates must not disagree about what a missing key means.
     */
    public record StateDocument(
            String environment, Instant observedAt, List<NodeStateDocument> nodes, List<PluginOutcome> plugins) {}

    /** Individual StateContributions are never exposed; the API serves the composed row only. */
    public record NodeStateDocument(
            String nodeKey, String health, String rawSignal, Map<String, Object> metrics, Instant observedAt) {}
}
