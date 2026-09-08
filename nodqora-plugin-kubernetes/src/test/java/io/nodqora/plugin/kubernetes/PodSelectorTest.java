// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ADR-0148's reference format, {@code <namespace>/<key>=<value>[,…]}.
 *
 * <p>The grammar is small on purpose and the parse is strict on purpose: this string is
 * configuration a human wrote, bound as an opaque reference that no startup validation can reach
 * (ADR-0022), so the only place it can be wrong is here.
 */
class PodSelectorTest {

    @Test
    void the_namespace_is_split_off_at_the_first_slash_and_no_other() {
        // The rule the whole format turns on. A label key is routinely a DNS subdomain, so slashes
        // inside the selector are the normal case — `strimzi.io/cluster`, `app.kubernetes.io/name`
        // — and any other split point would make the commonest real selector unwritable.
        PodSelector selector =
                PodSelector.parse("market-demo/strimzi.io/cluster=market,strimzi.io/kind=KafkaConnect").orElseThrow();

        assertThat(selector.namespace()).isEqualTo("market-demo");
        assertThat(selector.labels())
                .isEqualTo(Map.of("strimzi.io/cluster", "market", "strimzi.io/kind", "KafkaConnect"));
    }

    @Test
    void every_entry_must_match_and_the_namespace_must_match_too() {
        PodSelector selector = PodSelector.parse("market-demo/app=connect,tier=stream").orElseThrow();

        assertThat(selector.selects(pod("market-demo", Map.of("app", "connect", "tier", "stream", "pod", "0"))))
                .as("a superset of the selector matches — ADR-0030's Service semantics, unchanged")
                .isTrue();
        assertThat(selector.selects(pod("market-demo", Map.of("app", "connect"))))
                .as("AND-ed: one missing entry is not a match")
                .isFalse();
        assertThat(selector.selects(pod("market-staging", Map.of("app", "connect", "tier", "stream"))))
                .as("labels alone must never let one namespace answer for another")
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "payments-prod", // no selector at all
                "payments-prod/", // a namespace and an empty selector, which would match everything
                "/app=api", // no namespace
                "payments-prod/app", // a bare label key: existence matching is not in the grammar
                "payments-prod/app=", // an empty value is not the same as "any value"
                "payments-prod/=api", // an empty key
                "payments-prod/app=api,", // a trailing comma leaves an empty term
                "payments-prod/app!=api", // set-based operators are deliberately absent
            })
    void anything_that_is_not_the_grammar_yields_nothing_rather_than_matching_everything(String reference) {
        // Failing toward "no selector" is the safe direction: an empty label map would be true of
        // every pod in the namespace, so a typo would silently attach a node to the whole cluster.
        assertThat(PodSelector.parse(reference)).isEmpty();
    }

    @Test
    void a_null_reference_is_not_a_crash() {
        assertThat(PodSelector.parse(null)).isEqualTo(Optional.empty());
    }

    private static ObservedPod pod(String namespace, Map<String, String> labels) {
        return new ObservedPod(namespace, "pod-0", labels, "Running", true, false);
    }
}
