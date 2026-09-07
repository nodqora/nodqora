// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ADR-0099's <em>one</em> drift guard, and the only thing in the build that reads
 * {@code docs/reference-pipeline.md}.
 *
 * <p>Tests do not parse that file and it is not generated from the fixtures. Generating it would
 * destroy §10, which is pure argument and the most valuable part of it; parsing it would make the
 * markdown a schema, and the first prose edit would break the build for a reason nobody could read.
 * The document and the fixtures may disagree about everything <em>except the counts</em> — that is
 * the accepted cost, and §4's four numbers are what rot: a node added to the fixtures without a doc
 * edit makes them false silently.
 *
 * <p>This test needs no Spring context and no database. It compares two checked-in artifacts.
 */
class ReferencePipelineCountsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void section_four_agrees_with_the_golden_documents() throws IOException {
        Map<String, Counts> documented = parseSectionFour();

        for (String environmentKey : documented.keySet()) {
            JsonNode golden = Golden.read(mapper, "graph-" + environmentKey + ".json");

            assertThat(golden.get("nodes").size())
                    .as("docs/reference-pipeline.md §4 says %s has %d nodes", environmentKey,
                            documented.get(environmentKey).nodes())
                    .isEqualTo(documented.get(environmentKey).nodes());

            // Slice 4 closed the last gap this guard carried. Until `connect` existed the goldens
            // were short by §3's two `SOURCES_FROM` edges in production and one in staging, and the
            // guard held an allowance for exactly those; now it compares the two numbers outright.
            // The allowance is gone rather than set to zero, because a zeroed allowance is a place
            // for the next one to be added quietly.
            assertThat(golden.get("edges").size())
                    .as("docs/reference-pipeline.md §4 says %s has %d edges", environmentKey,
                            documented.get(environmentKey).edges())
                    .isEqualTo(documented.get(environmentKey).edges());
        }
    }

    private record Counts(int nodes, int edges) {}

    /** Reads the two rows of §4's table and nothing else about the file. */
    private static Map<String, Counts> parseSectionFour() throws IOException {
        String document = Files.readString(Path.of("docs", "reference-pipeline.md"));
        int[] nodes = row(document, "Nodes");
        int[] edges = row(document, "Edges");
        Map<String, Counts> counts = new LinkedHashMap<>();
        counts.put("production", new Counts(nodes[0], edges[0]));
        counts.put("staging", new Counts(nodes[1], edges[1]));
        return counts;
    }

    private static int[] row(String document, String label) {
        for (String line : document.lines().toList()) {
            String[] cells = line.split("\\|");
            if (cells.length >= 4 && cells[1].strip().equals(label)) {
                return new int[] {Integer.parseInt(cells[2].strip()), Integer.parseInt(cells[3].strip())};
            }
        }
        throw new AssertionError(
                "docs/reference-pipeline.md §4 no longer has a '" + label + "' row; this guard cannot run");
    }
}
