// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.connect;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The guard on §8's scenario overlays, and the sibling of the {@code kubernetes} plugin's test of
 * the same name.
 *
 * <p>An overlay is a whole cluster file that shadows the baseline's, so it is a near-copy of it —
 * and a near-copy is the shape that drifts. Add a connector to the baseline and the incident
 * recording keeps the old roster, silently, while both suites go on passing: the incident asserts
 * health and the baseline asserts topology, so neither notices that they are now describing two
 * different clusters.
 *
 * <p>So the overlay is asserted to be <b>the baseline with exactly the lines the scenario is about
 * changed, and nothing else</b>. Here that means state, and only state: the incident is two sinks
 * somebody paused, which changes what the connectors are <em>doing</em> and nothing about what they
 * <em>are</em>. A changed {@code config} block would mean the edges disagree between scenarios,
 * which is precisely ADR-0003's wall failing where no other test looks.
 */
class RecordedScenarioTest {

    private static final Path RECORDINGS = RecordedConnectApi.DEFAULT_DIRECTORY;

    private static final String CLUSTER = "connect-prod.internal.json";

    @Test
    void the_incident_overlay_is_the_baseline_with_only_its_states_changed() {
        List<String> baseline = lines(RECORDINGS.resolve(CLUSTER));
        List<String> overlay = lines(RECORDINGS.resolve("incident").resolve(CLUSTER));

        assertThat(overlay)
                .as("the incident must not add or remove a line; it is the same connectors, paused")
                .hasSameSizeAs(baseline);

        List<Integer> changed = changedLines(baseline, overlay);

        // Two connector states and the five tasks under them that were RUNNING. `payments-es-sink`
        // has three; `payments-iceberg-sink` has two, because its third was already FAILED in the
        // baseline and stays FAILED here — pausing a connector does not repair a task, and a
        // recording that quietly healed one would be describing a Connect that does not exist.
        assertThat(changed)
                .as("the incident changes two connector states and the five running tasks beneath them")
                .hasSize(7);

        changed.forEach(line -> assertThat(overlay.get(line))
                .as("line %d of the incident overlay", line + 1)
                .contains("\"state\""));

        // The scope's two rejects are untouched in both files, so the incident cannot accidentally
        // become a test of the prefix list.
        assertThat(overlay).contains(lineNaming(baseline, "payments-debug-reprocessor"));
        assertThat(overlay).contains(lineNaming(baseline, "orders-jdbc-source"));
    }

    @Test
    void an_overlay_carries_only_the_cluster_it_changes() {
        // Staging falls through to the baseline, which is what keeps the fixture from growing a
        // second copy of a file no scenario has an opinion about. `connect/incident/` holds one
        // file; asking it for staging must reach the baseline rather than throw.
        assertThat(RecordedConnectApi.scenario("incident").connectors(staging()))
                .isNotEmpty();
    }

    private static ConnectConfig staging() {
        return new ConnectConfig(
                "https://connect-staging.internal:8083",
                null,
                new ConnectConfig.Connectors(List.of("payments-"), List.of()),
                null,
                ConnectConfig.Links.none());
    }

    private static String lineNaming(List<String> lines, String connector) {
        return lines.stream()
                .filter(line -> line.contains("\"name\"") && line.contains(connector))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the baseline no longer records " + connector));
    }

    private static List<Integer> changedLines(List<String> baseline, List<String> overlay) {
        return java.util.stream.IntStream.range(0, baseline.size())
                .filter(line -> !baseline.get(line).equals(overlay.get(line)))
                .boxed()
                .toList();
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
