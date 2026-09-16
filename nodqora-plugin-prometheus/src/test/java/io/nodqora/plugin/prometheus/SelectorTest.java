// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.prometheus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ADR-0167's selector, read back off a backing. The grammar is a copy of the one `kubernetes` and
 * `yaml` write with (ADR-0015 rules out sharing it), so these cases are theirs.
 */
class SelectorTest {

    @Test
    void pairs_are_equality_only_and_sorted_by_label_name() {
        assertThat(Selector.parse(" namespace = market-demo , app=market-aggregator"))
                .hasValueSatisfying(selector -> {
                    assertThat(selector.pairs())
                            .containsExactly(Map.entry("app", "market-aggregator"), Map.entry("namespace", "market-demo"));
                    assertThat(selector.labels()).containsExactly("app", "namespace");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",                      // never empty: no pairs is not every series
        "   ",
        "app=",                  // an empty value
        "=x",                    // no label
        "app",                   // not a pair
        "app=x=y",               // a value holding '='
        "app=x;job=y",           // a value holding ';'
        "app=x,,job=y",          // an empty pair
        "1app=x",                // not a Prometheus label name
        "app-name=x",
        "app=x,app=y",           // one label twice can match nothing
        "app={name}",            // an unresolved placeholder
        "app!=x",                // not equality
    })
    void a_selector_that_cannot_be_read_is_rejected_whole(String reference) {
        assertThat(Selector.parse(reference)).isEmpty();
    }

    @Test
    void what_looks_like_a_regex_matcher_is_a_value_and_read_literally() {
        // `app=~x` is the label `app` equal to the text `~x`, exactly as `kubernetes` stores it. The
        // query quotes it as a literal, so it fails toward no series rather than toward a pattern.
        assertThat(Selector.parse("app=~x")).hasValueSatisfying(
                selector -> assertThat(selector.pairs()).containsExactly(Map.entry("app", "~x")));
    }

    @Test
    void a_null_reference_is_rejected() {
        assertThat(Selector.parse(null)).isEmpty();
    }
}
