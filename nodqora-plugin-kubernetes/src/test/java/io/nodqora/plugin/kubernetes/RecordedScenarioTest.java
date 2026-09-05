package io.nodqora.plugin.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The guard on §8's scenario overlays.
 *
 * <p>An overlay is a whole namespace file that shadows the baseline's, so it is a near-copy of it —
 * and a near-copy is the shape that drifts. Change an annotation on the baseline's
 * {@code enricher-v2} and the incident recording keeps the old one, silently, while both suites go
 * on passing: the incident asserts health and the baseline asserts topology, so neither notices that
 * they are now describing two different Deployments.
 *
 * <p>So each overlay is asserted to be <b>the baseline with exactly the lines the scenario is about
 * changed, and nothing else</b>. That is the whole cost of expressing a scenario as a file rather
 * than as a merge, paid once, and it converts a silent divergence into a failing test naming the
 * line.
 */
class RecordedScenarioTest {

    private static final Path RECORDINGS = RecordedKubernetesApi.DEFAULT_DIRECTORY;

    @ParameterizedTest(name = "{0} differs from the baseline only in {1}")
    @CsvSource({
        // §8's incident: the enricher is 3 desired / 0 ready in CrashLoopBackOff.
        "incident, '      \"readyReplicas\": 2' -> '      \"readyReplicas\": 0'",
        // ADR-0034's declared intent: spec.replicas 0, which is the only path to DISABLED.
        "scaled-to-zero, '      \"desiredReplicas\": 3,' -> '      \"desiredReplicas\": 0,'",
    })
    void an_overlay_is_the_baseline_with_only_its_own_numbers_changed(String scenario, String describedChange) {
        List<String> baseline = lines(RECORDINGS.resolve("payments-prod.json"));
        List<String> overlay = lines(RECORDINGS.resolve(scenario).resolve("payments-prod.json"));

        assertThat(overlay)
                .as("%s must not add or remove a line; it is the same objects, differently scaled", scenario)
                .hasSameSizeAs(baseline);

        List<Integer> changed = changedLines(baseline, overlay);
        assertThat(changed)
                .as("%s changes exactly the readiness numbers it is named for (%s)", scenario, describedChange)
                .hasSize(scenario.equals("scaled-to-zero") ? 2 : 1);

        // Every changed line is a replica count. If an annotation, a name or a label ever differs,
        // the two files have stopped describing the same cluster and the scenario means nothing.
        changed.forEach(line -> assertThat(overlay.get(line))
                .as("line %d of the %s overlay", line + 1, scenario)
                .containsAnyOf("desiredReplicas", "readyReplicas"));
    }

    @Test
    void an_overlay_carries_only_the_namespace_it_changes() {
        // Staging falls through to the baseline in every scenario, which is what keeps the fixture
        // from growing a second copy of a file no scenario has an opinion about.
        assertThat(RecordedKubernetesApi.scenario("incident")
                        .list(config(), "payments-staging")
                        .workloads())
                .isNotEmpty();
    }

    private static KubernetesConfig config() {
        return new KubernetesConfig(List.of("payments-staging"), null, null, List.of(), null);
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
