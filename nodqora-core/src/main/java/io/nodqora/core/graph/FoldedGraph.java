package io.nodqora.core.graph;

import java.util.List;

/** One environment's whole slow half, recomputed from the snapshot store (ADR-0043). */
public record FoldedGraph(List<FoldedNode> nodes, List<FoldedEdge> edges, List<FoldedOwner> owners) {

    public static FoldedGraph empty() {
        return new FoldedGraph(List.of(), List.of(), List.of());
    }
}
