// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.prometheus;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * A Prometheus HTTP API that answers {@code /api/v1/query} from a table keyed by the exact PromQL,
 * so a test states the query ADR-0167 prescribes and what a real server would have returned for it.
 * An unlisted query gets an empty vector, which is what Prometheus says when nothing matches.
 */
final class StubPrometheus implements AutoCloseable {

    record Received(String method, String path, String query, String authorization, String contentType) {}

    private record Answer(int status, String body) {}

    private final HttpServer server;
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();

    StubPrometheus() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String query = form(body).get("query");
            received.add(new Received(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    query,
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type")));
            Answer answer = answers.getOrDefault(query, new Answer(200, vector()));
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:%d/".formatted(server.getAddress().getPort());
    }

    /** Answers {@code query} with a vector of {@code samples}. */
    StubPrometheus answer(String query, String... samples) {
        answers.put(query, new Answer(200, vector(samples)));
        return this;
    }

    StubPrometheus answerRaw(String query, int status, String body) {
        answers.put(query, new Answer(status, body));
        return this;
    }

    /** One vector sample, as the API writes it: labels, then {@code [time, "value"]}. */
    static String sample(Map<String, String> labels, String value) {
        String metric = labels.entrySet().stream()
                .map(label -> "\"%s\":\"%s\"".formatted(label.getKey(), label.getValue()))
                .collect(Collectors.joining(","));
        return "{\"metric\":{%s},\"value\":[1789572690.073,\"%s\"]}".formatted(metric, value);
    }

    static String vector(String... samples) {
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[%s]}}"
                .formatted(String.join(",", samples));
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    List<String> queries() {
        return received.stream().map(Received::query).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static Map<String, String> form(String body) {
        Map<String, String> fields = new ConcurrentHashMap<>();
        for (String field : body.split("&")) {
            int equals = field.indexOf('=');
            if (equals > 0) {
                fields.put(
                        URLDecoder.decode(field.substring(0, equals), StandardCharsets.UTF_8),
                        URLDecoder.decode(field.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return fields;
    }
}
