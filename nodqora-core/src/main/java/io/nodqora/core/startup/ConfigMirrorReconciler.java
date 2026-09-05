package io.nodqora.core.startup;

import io.nodqora.core.config.BoundConfiguration;
import io.nodqora.core.store.HealthStore;
import io.nodqora.core.store.SnapshotStore;
import io.nodqora.plugin.api.Plugin;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles the {@code environment} and {@code plugin} config-mirror tables from the bound config
 * at startup: upsert what is configured, delete what is not (ADR-0074).
 *
 * <p>Every table carrying an environment key or a plugin id hangs off these by
 * {@code ON DELETE CASCADE}, which is what turns ADR-0043's two cleanup rules from a checklist into
 * an invariant — including for the table someone adds next year without reading ADR-0043.
 *
 * <p><b>ADR-0101's one guard lives here.</b> The cascade is the only cost on the map that is all
 * three of destructive, triggered by an ordinary text edit, and silent: a typo in the environment
 * config is indistinguishable from a removal, and one that self-heals within a cadence leaves no
 * trace that it happened. Every other accepted cost had its defence considered and rejected; this
 * one never weighed the cascade against <em>saying so</em>. So before deleting a mirror row we log a
 * WARN naming the key and the row counts about to cascade — no mechanism, no configuration, no
 * confirmation prompt, and no change to the invariant ADR-0074 exists to create. It fires on
 * legitimate removals too, and there is no way to tell them apart; it is a log line rather than an
 * alert precisely because it cannot know.
 */
@Component
public class ConfigMirrorReconciler {

    private static final Logger log = LoggerFactory.getLogger(ConfigMirrorReconciler.class);

    /** The tables whose rows a cascade would take, for the WARN's benefit only. */
    private static final List<String> CASCADING_BY_ENVIRONMENT =
            List.of("plugin_snapshot", "plugin_snapshot_entry", "health_run", "node", "edge", "owner");

    private final JdbcTemplate jdbc;
    private final BoundConfiguration configuration;
    private final SnapshotStore snapshots;
    private final HealthStore contributions;

    public ConfigMirrorReconciler(
            JdbcTemplate jdbc,
            BoundConfiguration configuration,
            SnapshotStore snapshots,
            HealthStore contributions) {
        this.jdbc = jdbc;
        this.configuration = configuration;
        this.snapshots = snapshots;
        this.contributions = contributions;
    }

    @Transactional
    public void reconcile() {
        reconcileEnvironments();
        reconcilePlugins();

        // ADR-0080: a payload written under a different contract version is discarded rather than
        // migrated, so the graph goes empty and repopulating rather than partial and wrong.
        //
        // Deliberately not a second WARN. ADR-0101 grants exactly one guard, to ADR-0074's cascade,
        // on the grounds that it is destructive *and* triggered by an ordinary text edit *and*
        // silent. A version bump fails the middle test — it is a deliberate developer act — which is
        // why ADR-0101 did not grant it one, and the empty graph it causes is on this slice's
        // must-not-fix list.
        // One constant across both stores, so a bump discards them together and the graph goes
        // empty and repopulating rather than partial and wrong. The fast half self-heals in thirty
        // seconds; the slow half takes a discovery cadence.
        int discarded = snapshots.discardStalePayloads() + contributions.discardStalePayloads();
        log.debug("discarded {} stored rows written under a different payload version", discarded);
    }

    private void reconcileEnvironments() {
        List<String> configured = configuration.environments().stream()
                .map(BoundConfiguration.EnvironmentRoster::key)
                .toList();

        configuration.environments().forEach(environment -> jdbc.update(
                """
                insert into environment (key, display_name) values (?, ?)
                on conflict (key) do update set display_name = excluded.display_name
                """,
                environment.key(),
                environment.displayName()));

        for (String stale : absent("select key from environment", configured)) {
            warnBeforeCascade("environment", stale, CASCADING_BY_ENVIRONMENT, "environment_key");
            jdbc.update("delete from environment where key = ?", stale);
        }
    }

    private void reconcilePlugins() {
        List<String> configured =
                configuration.registeredPlugins().stream().map(Plugin::id).toList();

        configured.forEach(pluginId ->
                jdbc.update("insert into plugin (id) values (?) on conflict (id) do nothing", pluginId));

        for (String stale : absent("select id from plugin", configured)) {
            warnBeforeCascade(
                    "plugin",
                    stale,
                    List.of("plugin_snapshot", "plugin_snapshot_entry", "health_run", "health_contribution"),
                    "plugin_id");
            jdbc.update("delete from plugin where id = ?", stale);
        }
    }

    private List<String> absent(String query, List<String> configured) {
        return jdbc.queryForList(query, String.class).stream()
                .filter(stored -> !configured.contains(stored))
                .toList();
    }

    private void warnBeforeCascade(String mirror, String key, List<String> tables, String column) {
        StringBuilder counts = new StringBuilder();
        for (String table : tables) {
            Integer rows = jdbc.queryForObject(
                    "select count(*) from " + table + " where " + column + " = ?", Integer.class, key);
            counts.append(counts.isEmpty() ? "" : ", ").append(table).append('=').append(rows);
        }
        log.warn(
                "removing {} '{}' from the config mirror; this cascades away its stored rows [{}]. "
                        + "If that key was a typo rather than a removal, restore it: the store rebuilds "
                        + "within one discovery cadence.",
                mirror,
                key,
                counts);
    }
}
