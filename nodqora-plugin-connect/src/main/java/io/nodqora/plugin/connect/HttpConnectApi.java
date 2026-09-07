// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
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
 * The one place a real Connect cluster is spoken to (ADR-0099), and <b>the one class in this
 * repository that ADR-0042's GET-only rule is about</b>.
 *
 * <p>Every request in this file is built by {@link #get(ConnectConfig, String)} and there is no
 * other builder. That is not tidiness: research #5 verified there is no read-only Connect credential
 * and no per-endpoint authorization, so whatever credential Nodqora holds can delete every connector
 * on the cluster. The only real mitigation is an operator-side GET-only reverse proxy, which is
 * outside this codebase — so the half that <em>is</em> inside it is made provably harmless and
 * checked by {@code GetOnlyTest} rather than by a reviewer remembering.
 *
 * <p>The two responses are Connect's {@code expand} form, {@code {name: {info|status: …}}}. A
 * per-connector expansion failure arrives as an {@code error_code} object in place of the expanded
 * body, and is carried through as {@code error} on the entry rather than thrown: the listing
 * succeeded, and ADR-0042 makes that {@code PARTIAL} naming the connector rather than {@code FAILED}.
 */
@Component
class HttpConnectApi implements ConnectApi {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public List<ConnectorInfo> connectors(ConnectConfig config) {
        JsonNode expanded = get(config, "/connectors?expand=info");
        List<ConnectorInfo> connectors = new ArrayList<>();
        expanded.fields().forEachRemaining(entry -> {
            JsonNode info = entry.getValue().path("info");
            String error = errorIn(info);
            connectors.add(error != null
                    ? new ConnectorInfo(entry.getKey(), null, Map.of(), error)
                    : new ConnectorInfo(entry.getKey(), text(info, "type"), strings(info.path("config"))));
        });
        return connectors;
    }

    @Override
    public List<ConnectorStatus> statuses(ConnectConfig config) {
        JsonNode expanded = get(config, "/connectors?expand=status");
        List<ConnectorStatus> statuses = new ArrayList<>();
        expanded.fields().forEachRemaining(entry -> {
            JsonNode status = entry.getValue().path("status");
            String error = errorIn(status);
            if (error != null) {
                statuses.add(new ConnectorStatus(entry.getKey(), null, List.of(), error));
                return;
            }
            List<TaskStatus> tasks = new ArrayList<>();
            status.path("tasks")
                    .forEach(task -> tasks.add(new TaskStatus(
                            task.path("id").asInt(), text(task, "state"), text(task, "trace"))));
            statuses.add(new ConnectorStatus(entry.getKey(), text(status.path("connector"), "state"), tasks));
        });
        return statuses;
    }

    /**
     * The only request builder in this module, and the only verb it can express.
     *
     * <p>{@code HttpRequest.Builder} defaults to {@code GET}, so this method names it explicitly
     * anyway: a default is a thing a future edit can change without looking like a change, and the
     * whole point of ADR-0042's rule is that the verb is visible at the one place it is decided.
     */
    private JsonNode get(ConnectConfig config, String path) {
        URI uri = URI.create(config.url().replaceAll("/+$", "") + path);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json");
        if (config.auth() != null) {
            String credential = config.auth().username() + ":"
                    + (config.auth().password() == null ? "" : config.auth().password());
            request.header(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8)));
        }

        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ConnectApiException("%s is unreachable".formatted(uri), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectApiException("%s was interrupted".formatted(uri), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new ConnectApiException("%s returned HTTP %d".formatted(uri, response.statusCode()));
        }
        try {
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new ConnectApiException("%s returned a body that is not JSON".formatted(uri), e);
        }
    }

    /** Connect answers a failed expansion in place, with an {@code error_code} rather than a status. */
    private static String errorIn(JsonNode expanded) {
        if (expanded.isMissingNode() || expanded.isNull()) {
            return "the response carried no expansion";
        }
        if (expanded.has("error_code")) {
            String message = text(expanded, "message");
            return message == null ? "error_code " + expanded.path("error_code").asInt() : message;
        }
        return null;
    }

    private static Map<String, String> strings(JsonNode object) {
        Map<String, String> values = new LinkedHashMap<>();
        object.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        return values;
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
