// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kafka;

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
 * and a near-copy is the shape that drifts. Rename a topic in the baseline and the incident
 * recording keeps the old name, silently, while both suites go on passing: the incident asserts
 * health and the baseline asserts topology, so neither notices that they are now describing two
 * different clusters.
 *
 * <p>So the overlay is asserted to be <b>the baseline with exactly the lines the scenario is about
 * changed, and nothing else</b>. For this plugin that is a sharper claim than it looks, because the
 * two numbers on a partition line do different jobs: the incident moves {@code endOffset}, which is
 * the producer's side, and must leave {@code committedOffset} alone, which is the consumer's. A
 * scenario that moved both would describe a consumer that kept up, and the lag it exists to produce
 * would quietly be the baseline's.
 */
class RecordedScenarioTest {

    private static final Path RECORDINGS = RecordedKafkaApi.DEFAULT_DIRECTORY;

    private static final String CLUSTER = "kafka-prod.internal.json";

    @Test
    void the_incident_overlay_is_the_baseline_with_only_its_end_offsets_changed() {
        List<String> baseline = lines(RECORDINGS.resolve(CLUSTER));
        List<String> overlay = lines(RECORDINGS.resolve("incident").resolve(CLUSTER));

        assertThat(overlay)
                .as("the incident must not add or remove a line; it is the same topics and groups")
                .hasSameSizeAs(baseline);

        List<Integer> changed = changedLines(baseline, overlay);

        // §8's incident: the enricher has stopped, so `enrich-consumer-prod` falls 2.1M behind
        // across its three partitions. The two sink groups are untouched on purpose — their lag
        // stays frozen at the baseline's, which is exactly why `enriched.v1` reads HEALTHY through
        // the incident and why that reading is honest rather than a gap.
        assertThat(changed)
                .as("the incident moves the three partitions of one group and no others")
                .hasSize(3);

        changed.forEach(line -> {
            assertThat(overlay.get(line))
                    .as("line %d of the incident overlay", line + 1)
                    .contains("\"endOffset\"");
            // The half that catches the mistake worth catching. A partition line carries both
            // numbers, so an overlay can move the consumer's without changing its own line count.
            assertThat(committedOffset(overlay.get(line)))
                    .as("line %d must leave the committed offset where the baseline put it", line + 1)
                    .isEqualTo(committedOffset(baseline.get(line)));
        });
    }

    @Test
    void an_overlay_carries_only_the_cluster_it_changes() {
        // Staging falls through to the baseline, which is what keeps the fixture from growing a
        // second copy of a file no scenario has an opinion about. `kafka/incident/` holds one file;
        // asking it for staging must reach the baseline rather than throw.
        assertThat(RecordedKafkaApi.scenario("incident").listTopics(staging())).isNotEmpty();
    }

    private static KafkaConfig staging() {
        return new KafkaConfig(
                "kafka-staging.internal:9092",
                null,
                new KafkaConfig.Topics(List.of("payments."), List.of()),
                new KafkaConfig.Lag(10000L, null),
                KafkaConfig.Links.none());
    }

    private static String committedOffset(String line) {
        int at = line.indexOf("\"committedOffset\"");
        if (at < 0) {
            throw new AssertionError("not a partition line: " + line);
        }
        int end = line.indexOf(',', at);
        return line.substring(at, end < 0 ? line.length() : end);
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
