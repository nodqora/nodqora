// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.prometheus;

import static io.nodqora.plugin.prometheus.StubPrometheus.sample;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The one place a real Prometheus is spoken to, against a stub of its HTTP API. */
class HttpPrometheusApiTest {

    private final StubPrometheus prometheus = new StubPrometheus();
    private final HttpPrometheusApi api = new HttpPrometheusApi();

    @AfterEach
    void stop() {
        prometheus.close();
    }

    @Test
    void an_instant_query_is_a_form_post_and_reads_back_as_labelled_samples() {
        prometheus.answer(
                "sum by (app) (up)",
                sample(Map.of("app", "market-aggregator"), "34.67284567778374"),
                sample(Map.of("app", "market-connect"), "0"));

        List<PrometheusApi.Sample> samples = api.query(config(null, null), "sum by (app) (up)");

        assertThat(samples).containsExactlyInAnyOrder(
                new PrometheusApi.Sample(Map.of("app", "market-aggregator"), 34.67284567778374),
                new PrometheusApi.Sample(Map.of("app", "market-connect"), 0.0));
        // A POST form, because a batched selector can outgrow what a proxy lets through in a URL;
        // the query endpoint accepts both verbs and neither writes anything.
        assertThat(prometheus.received()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/api/v1/query");
            assertThat(request.contentType()).isEqualTo("application/x-www-form-urlencoded");
            assertThat(request.authorization()).isNull();
        });
    }

    @Test
    void a_non_finite_value_is_carried_as_the_double_it_names() {
        prometheus.answer(
                "q",
                sample(Map.of("app", "a"), "NaN"),
                sample(Map.of("app", "b"), "+Inf"),
                sample(Map.of("app", "c"), "-Inf"));

        assertThat(api.query(config(null, null), "q"))
                .extracting(PrometheusApi.Sample::value)
                .containsExactlyInAnyOrder(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
    }

    @Test
    void basic_auth_is_sent_when_configured() {
        api.query(config(new PrometheusConfig.Auth("nodqora", "s3cret"), null), "q");

        assertThat(prometheus.received()).singleElement().extracting(StubPrometheus.Received::authorization)
                .isEqualTo("Basic " + Base64.getEncoder().encodeToString("nodqora:s3cret".getBytes()));
    }

    @Test
    void a_bearer_token_is_sent_when_configured() {
        api.query(config(null, "t0ken"), "q");

        assertThat(prometheus.received()).singleElement().extracting(StubPrometheus.Received::authorization)
                .isEqualTo("Bearer t0ken");
    }

    @Test
    void an_error_response_names_prometheus_own_reason() {
        prometheus.answerRaw(
                "bad(",
                400,
                "{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"1:5: parse error: unclosed left parenthesis\"}");

        assertThatThrownBy(() -> api.query(config(null, null), "bad("))
                .isInstanceOf(PrometheusApiException.class)
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("unclosed left parenthesis");
    }

    @Test
    void a_body_that_is_not_a_vector_is_an_error() {
        prometheus.answerRaw("scalar(1)", 200,
                "{\"status\":\"success\",\"data\":{\"resultType\":\"scalar\",\"result\":[1,\"1\"]}}");

        assertThatThrownBy(() -> api.query(config(null, null), "scalar(1)"))
                .isInstanceOf(PrometheusApiException.class)
                .hasMessageContaining("scalar");
    }

    @Test
    void an_unreachable_server_is_an_error() {
        String url = prometheus.url();
        prometheus.close();

        assertThatThrownBy(() -> api.query(new PrometheusConfig(url, null, null), "q"))
                .isInstanceOf(PrometheusApiException.class)
                .hasMessageContaining("unreachable");
    }

    private PrometheusConfig config(PrometheusConfig.Auth auth, String bearerToken) {
        return new PrometheusConfig(prometheus.url(), auth, bearerToken);
    }
}
