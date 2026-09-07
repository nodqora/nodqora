// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.nodqora.core.startup.ConfigMirrorReconciler;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * ADR-0101's one guard.
 *
 * <p>Almost every accepted cost on the map already had its defence considered and rejected, and none
 * of them should be re-litigated by a session that has read one ADR and not the argument behind it.
 * ADR-0074's cascade is the exception: it weighed itself against <em>"the same deletion written out
 * by hand across eight tables with a chance of missing one"</em> and took the cascade, but it never
 * weighed itself against <b>saying so</b>. It is the only cost that is all three of destructive,
 * triggered by an ordinary text edit, and silent.
 *
 * <p>So a WARN names the key and the row counts about to cascade, and stops there. It builds no
 * mechanism, adds no configuration, introduces no confirmation prompt, and does not touch the
 * invariant ADR-0074 exists to create — it converts a silent destructive act into a loud one.
 */
class CascadeGuardTest extends NodqoraIntegrationTest {

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private Logger reconcilerLog;

    @BeforeEach
    void captureLogs() {
        reconcilerLog = (Logger) LoggerFactory.getLogger(ConfigMirrorReconciler.class);
        logged.start();
        reconcilerLog.addAppender(logged);
    }

    @AfterEach
    void releaseLogs() {
        reconcilerLog.detachAppender(logged);
    }

    @Test
    void an_environment_leaving_the_config_is_loud_before_it_cascades() {
        // A typo in the environment config is indistinguishable from a removal, so this is what a
        // one-character edit to `application.yaml` does.
        jdbc.update("insert into environment (key, display_name) values ('produciton', 'Typo')");
        jdbc.update(
                """
                insert into node (environment_key, key, links, backings, metadata, sources,
                                  discovered_at, updated_at)
                values ('produciton', 'orphan', '[]', '[]', '{}', '[]', now(), now())
                """);

        reconciler.reconcile();

        List<String> warnings = logged.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        assertThat(warnings)
                .as("the WARN must name the key and the row counts, or it is not actionable")
                .anySatisfy(warning -> assertThat(warning)
                        .contains("produciton")
                        .contains("node=1"));
    }

    @Test
    void the_cascade_still_happens_because_the_guard_is_only_a_log_line() {
        jdbc.update("insert into environment (key, display_name) values ('produciton', 'Typo')");
        jdbc.update(
                """
                insert into node (environment_key, key, links, backings, metadata, sources,
                                  discovered_at, updated_at)
                values ('produciton', 'orphan', '[]', '[]', '{}', '[]', now(), now())
                """);

        reconciler.reconcile();

        // ADR-0074's invariant is untouched: the alternative to the cascade is not safety, it is the
        // same deletion written by hand across eight tables, which fails toward *immortal nodes* —
        // permanent, invisible, and corrupting drift-is-absence.
        assertThat(jdbc.queryForObject(
                        "select count(*) from node where environment_key = 'produciton'", Integer.class))
                .isZero();
    }

    @Test
    void a_configured_environment_is_never_warned_about() {
        reconciler.reconcile();

        assertThat(logged.list.stream()
                        .filter(event -> event.getLevel() == Level.WARN)
                        .map(ILoggingEvent::getFormattedMessage)
                        .toList())
                .as("the WARN fires on legitimate removals too, but never on a steady state")
                .isEmpty();
    }
}
