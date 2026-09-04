package io.nodqora.core.api;

import io.nodqora.core.api.ApiDocuments.GraphDocument;
import io.nodqora.core.api.ApiDocuments.MetaDocument;
import io.nodqora.core.api.ApiDocuments.StateDocument;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The entire MVP API (ADR-0052, ADR-0053):
 *
 * <pre>
 * GET /api/meta
 * GET /api/environments/{envKey}/graph
 * GET /api/environments/{envKey}/state
 * </pre>
 *
 * <p>Environment is the scoping resource, so a path that cannot express an unscoped node cannot
 * express that bug; environment-as-query-parameter was rejected for the opposite reason, that a
 * forgotten parameter is a cross-environment leak rather than a 404.
 *
 * <p>Two bulk endpoints at the two real cadences, because ADR-0003's wall is a cadence boundary: one
 * combined endpoint would have to be served at 30 seconds, refetching topology 120x more often than
 * it changes. There is no per-node route, no {@code /metrics} route, no traversal or search endpoint
 * (ADR-0054 — the client already holds the whole environment), and no write of any kind.
 */
@RestController
@RequestMapping("/api")
public class GraphController {

    private final GraphDocuments documents;

    public GraphController(GraphDocuments documents) {
        this.documents = documents;
    }

    @GetMapping("/meta")
    public MetaDocument meta() {
        return documents.meta();
    }

    @GetMapping("/environments/{environmentKey}/graph")
    public GraphDocument graph(@PathVariable String environmentKey) {
        return documents.graph(environmentKey);
    }

    @GetMapping("/environments/{environmentKey}/state")
    public StateDocument state(@PathVariable String environmentKey) {
        return documents.state(environmentKey);
    }
}
