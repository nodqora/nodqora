package io.nodqora.plugin.kafka;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.Map;

/**
 * ADR-0037, ADR-0039 and ADR-0042, bound from the config file and validated at startup (ADR-0014).
 *
 * <pre>
 * kafka:
 *   bootstrap: kafka-prod.internal:9092              # required
 *   properties:                                      # passthrough to AdminClient
 *     security.protocol: SASL_SSL
 *     sasl.jaas.config: "${env:KAFKA_PROD_JAAS}"
 *   topics:
 *     include: [payments.]                           # required, non-empty, no empty-string element
 *     ignore: [connect-offsets]                      # exact names (ADR-0031)
 *   lag:
 *     default: 10000                                 # required, non-negative
 *     groups: { enrich-consumer-prod: 10000 }        # optional per-group override
 *   links: { topic: ..., consumers: ..., dashboard: ... }
 * </pre>
 *
 * <p><b>{@code properties} is an untyped passthrough, and that is not a hole in ADR-0014.</b> Kafka
 * security configuration is open-ended — SASL mechanisms, SSL stores, OAuth callbacks, each with its
 * own keys — so a typed class cannot enumerate it. The distinction that keeps this consistent:
 * <em>allow-listing governs what leaves the plugin, not what enters it.</em> ADR-0014 banned
 * everything-except-{@code *.password} for output, because that is what reaches the API and the
 * canvas; operator-authored input has to be open, and {@code ${env:}} / {@code ${file:}} is what
 * keeps secrets out of the file.
 *
 * <p><b>{@code lag.default} is required and there is no built-in number.</b> A built-in turns nodes
 * amber on the canvas because of a threshold nobody chose; requiring it once means every amber node
 * traces to a human decision.
 */
public record KafkaConfig(
        @NotBlank String bootstrap,
        Map<String, String> properties,
        @NotNull @Valid Topics topics,
        @NotNull @Valid Lag lag,
        Links links) {

    public KafkaConfig {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        links = links == null ? Links.none() : links;
    }

    /**
     * ADR-0037: an ordered list of topic-name prefixes, matched exactly on a leading substring. No
     * regex, no glob, no default.
     *
     * <p>Kafka's only organising convention is the dotted name, and a prefix is the closest thing it
     * has to a namespace — which is what makes new topics under an owned prefix appear on their own,
     * and what distinguishes discovery from enumeration. Enumerated topic names were rejected for
     * exactly that reason: topics are created constantly, so a new pipeline topic would go silently
     * missing.
     *
     * <p><b>{@code listInternal=false} is set but is not sufficient.</b> It hides
     * {@code __consumer_offsets} and {@code __transaction_state}, but Connect's
     * {@code connect-configs} / {@code connect-offsets} / {@code connect-status} are ordinary topics
     * as far as Kafka is concerned. {@code ignore} is what removes them.
     */
    public record Topics(@NotEmpty List<@NotBlank String> include, List<String> ignore) {

        public Topics {
            include = include == null ? List.of() : List.copyOf(include);
            ignore = ignore == null ? List.of() : ignore.stream().map(String::trim).toList();
        }

        public boolean admits(String topic) {
            return matches(topic) && !ignore.contains(topic);
        }

        /** ADR-0042: a prefix matching nothing is a named {@code PARTIAL} reason, so it is asked for. */
        public boolean matches(String topic, String prefix) {
            return topic.startsWith(prefix);
        }

        private boolean matches(String topic) {
            return include.stream().anyMatch(prefix -> matches(topic, prefix));
        }
    }

    /**
     * ADR-0025: <b>one</b> threshold, not a pair, keyed by consumer group.
     *
     * <p>There is no {@code UNHEALTHY} threshold and adding one would be wrong rather than merely
     * unnecessary. Across both fixture scenarios every lag-derived value is {@code HEALTHY} or
     * {@code DEGRADED}, including 2,100,000 and growing, because <em>lag means behind, not
     * broken</em>: a consumer that is behind is still doing its job, and broken-ness arrives from the
     * workload crash-looping or the tasks failing, which are other plugins' signals.
     *
     * <p>Keyed by group rather than by node so that the core never sees a threshold (ADR-0015) and
     * this plugin never needs to know whose lag it is (ADR-0022).
     *
     * <p>ADR-0025 sketched this block as {@code degradedThreshold} / {@code perGroup} and ADR-0042
     * later fixed the whole config file with {@code default} / {@code groups}. The later spelling
     * wins, and ADR-0106 records why rather than leaving two ADRs describing one block differently.
     */
    public record Lag(
            // `default` is ADR-0042's key and a Java keyword, so the field is named and the key is
            // stated. Renaming the *file* key to suit the language would be the tail wagging the dog.
            @JsonProperty("default") @NotNull @PositiveOrZero Long defaultThreshold,
            Map<String, Long> groups) {

        public Lag {
            groups = groups == null ? Map.of() : Map.copyOf(groups);
        }

        public long thresholdFor(String groupId) {
            return groups.getOrDefault(groupId, defaultThreshold);
        }
    }

    /**
     * ADR-0039's per-environment link templates, with {@code {name}} as the only variable — the
     * topic's name, since everything else is constant per environment.
     *
     * <p>No template configured ⇒ no link, never a half-composed URL; labels are plugin constants.
     */
    public record Links(String topic, String consumers, String dashboard) {

        public static Links none() {
            return new Links(null, null, null);
        }
    }
}
