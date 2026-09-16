// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.prometheus;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * ADR-0167's recipes: the plugin's half of a binding, which picks the series and fixes their units.
 * Declaration order <b>is</b> the precedence, most specific first, and adding one is a code change.
 *
 * <p>Each query is ADR-0167's table with two changes that leave a single binding's answer
 * untouched and let one query serve every binding sharing label names (ADR-0013):
 *
 * <ul>
 *   <li><b>{@code sum by (<labels>)}</b> rather than {@code sum}, so each bound label set comes back
 *       as its own sample.
 *   <li><b>{@code on (pod, thread_id, <labels>)}</b> in the Streams latency. Both sides of that
 *       product already agree on the bound labels, and saying so keeps two pods of the same name in
 *       different namespaces from colliding once one query reads both.
 * </ul>
 */
enum Recipe {

    /** Streams gauges are windowed by the client, so neither key needs a {@code rate()}. */
    KAFKA_STREAMS("kafka-streams") {
        @Override
        String rate(String by, String matcher) {
            return "sum by (%s) (kafka_stream_thread_process_rate{%s})".formatted(by, matcher);
        }

        /** Weighted by each thread's rate, because an unweighted mean under-weights the busy pod. */
        @Override
        String latency(String by, String matcher) {
            return ("sum by (%1$s) (kafka_stream_thread_process_latency_avg{%2$s}"
                            + " * on (pod, thread_id, %1$s) kafka_stream_thread_process_rate{%2$s})"
                            + " / sum by (%1$s) (kafka_stream_thread_process_rate{%2$s})")
                    .formatted(by, matcher);
        }
    },

    /** Probes and scrapes are Micrometer HTTP requests too, and they are not the service's work. */
    MICROMETER_HTTP("micrometer-http") {
        @Override
        String rate(String by, String matcher) {
            return "sum by (%s) (rate(http_server_requests_seconds_count{%s}[%s]))".formatted(by, http(matcher), WINDOW);
        }

        /** Micrometer records seconds; the card's unit is the millisecond. */
        @Override
        String latency(String by, String matcher) {
            return ("sum by (%1$s) (rate(http_server_requests_seconds_sum{%2$s}[%3$s]))"
                            + " / sum by (%1$s) (rate(http_server_requests_seconds_count{%2$s}[%3$s]))"
                            + " * 1000")
                    .formatted(by, http(matcher), WINDOW);
        }

        private static String http(String matcher) {
            return matcher + ",uri!~\"/actuator.*\"";
        }
    };

    /** One window for every {@code rate()} (ADR-0167). */
    static final String WINDOW = "2m";

    /** Precedence, as a list: the first recipe to yield any key supplies both. */
    static final List<Recipe> PRECEDENCE = List.of(values());

    private final String kind;

    Recipe(String kind) {
        this.kind = kind;
    }

    /** The backing {@code kind} that names this recipe. */
    String kind() {
        return kind;
    }

    static Optional<Recipe> of(String kind) {
        return Arrays.stream(values()).filter(recipe -> recipe.kind.equals(kind)).findFirst();
    }

    /** Per second, grouped by {@code by}, over the series {@code matcher} selects. */
    abstract String rate(String by, String matcher);

    /** Mean milliseconds per unit of work, grouped the same way. */
    abstract String latency(String by, String matcher);
}
