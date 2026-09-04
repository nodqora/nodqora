package io.nodqora.plugin.yaml;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ADR-0062: an edge is a verb key on the node that performs it. Six verbs, closed for {@code yaml},
 * with no escape hatch and no {@code from} / {@code to}.
 *
 * <p>The subject is the enclosing node and in all six relations the subject is the <em>actor</em>,
 * so the author never writes a direction and therefore can never write one backwards. This table
 * applies ADR-0002's orientation instead: a reversed verb stores {@code target → node}.
 *
 * <p>The vocabulary is closed here rather than in the core because a closed vocabulary is a plugin
 * property (ADR-0062, ADR-0065). Adding {@code SCHEDULES} is a code change, deliberately.
 */
enum Verb {
    CALLS("calls", "CALLS", false),
    PRODUCES_TO("producesTo", "PRODUCES_TO", false),
    WRITES_TO("writesTo", "WRITES_TO", false),
    CONSUMES_FROM("consumesFrom", "CONSUMES_FROM", true),
    SOURCES_FROM("sourcesFrom", "SOURCES_FROM", true),
    QUERIES("queries", "QUERIES", true);

    private static final Map<String, Verb> BY_KEY = new LinkedHashMap<>();

    static {
        for (Verb verb : values()) {
            BY_KEY.put(verb.key, verb);
        }
    }

    private final String key;
    private final String relation;
    private final boolean reversed;

    Verb(String key, String relation, boolean reversed) {
        this.key = key;
        this.relation = relation;
        this.reversed = reversed;
    }

    static Verb byKey(String key) {
        return BY_KEY.get(key);
    }

    static Iterable<String> keys() {
        return BY_KEY.keySet();
    }

    String key() {
        return key;
    }

    String relation() {
        return relation;
    }

    /** Data flows from here. Reversed verbs make the target the producer. */
    String fromKey(String subject, String target) {
        return reversed ? target : subject;
    }

    String toKey(String subject, String target) {
        return reversed ? subject : target;
    }
}
