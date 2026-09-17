// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.prometheus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The one place a real Prometheus is spoken to: {@code POST /api/v1/query}, an instant query
 * (ADR-0167).
 *
 * <p><b>POST, and still read-only.</b> The query endpoint accepts GET and POST alike and evaluates
 * the same expression either way. A batched selector names every bound workload's value in one
 * matcher (see the recipes), so on a large environment the query outgrows the URL length a proxy
 * in front of Prometheus will accept; a form body does not. Nothing this class can send writes.
 */
@Component
class HttpPrometheusApi implements PrometheusApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public List<Sample> query(PrometheusConfig config, String promql) {
        URI uri = URI.create(config.url().replaceAll("/+$", "") + "/api/v1/query");
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .POST(HttpRequest.BodyPublishers.ofString("query=" + URLEncoder.encode(promql, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded");
        if (config.auth() != null) {
            String credential = config.auth().username() + ":"
                    + (config.auth().password() == null ? "" : config.auth().password());
            request.header(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8)));
        } else if (config.bearerToken() != null) {
            request.header("Authorization", "Bearer " + config.bearerToken().strip());
        }

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new PrometheusApiException("%s is unreachable".formatted(uri), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PrometheusApiException("%s was interrupted".formatted(uri), e);
        }

        JsonNode body = parse(response.body());
        if (response.statusCode() / 100 != 2 || !"success".equals(body.path("status").asText())) {
            String error = body.path("error").asText("");
            throw new PrometheusApiException("%s returned HTTP %d%s".formatted(
                    uri, response.statusCode(), error.isEmpty() ? "" : ": " + error));
        }
        JsonNode data = body.path("data");
        String type = data.path("resultType").asText();
        if (!"vector".equals(type)) {
            throw new PrometheusApiException("%s answered with a %s, not a vector".formatted(uri, type));
        }

        List<Sample> samples = new ArrayList<>();
        for (JsonNode element : data.path("result")) {
            Map<String, String> labels = new LinkedHashMap<>();
            element.path("metric").fields().forEachRemaining(label -> labels.put(label.getKey(), label.getValue().asText()));
            samples.add(new Sample(labels, value(element.path("value").path(1).asText())));
        }
        return samples;
    }

    /** A 4xx still carries Prometheus's JSON error; a proxy's error page does not, and reads as empty. */
    private static JsonNode parse(String body) {
        try {
            JsonNode parsed = MAPPER.readTree(body);
            return parsed == null ? MAPPER.createObjectNode() : parsed;
        } catch (IOException e) {
            return MAPPER.createObjectNode();
        }
    }

    /** Prometheus writes floats in Go's spelling, so its infinities need translating. */
    private static double value(String text) {
        return switch (text) {
            case "+Inf" -> Double.POSITIVE_INFINITY;
            case "-Inf" -> Double.NEGATIVE_INFINITY;
            default -> {
                try {
                    yield Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    yield Double.NaN;
                }
            }
        };
    }
}
