// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.prometheus;

import java.util.List;
import java.util.Map;

/** The plugin's one outbound seam: an instant query, answered with a vector. */
public interface PrometheusApi {

    /** One element of an instant vector. The value may be {@code NaN} or infinite, as PromQL's can. */
    record Sample(Map<String, String> labels, double value) {

        public Sample {
            labels = Map.copyOf(labels);
        }
    }

    /**
     * Evaluates {@code promql} at the current time.
     *
     * @throws PrometheusApiException if the server cannot be reached, refuses the query, or answers
     *     with anything but a vector
     */
    List<Sample> query(PrometheusConfig config, String promql);
}
