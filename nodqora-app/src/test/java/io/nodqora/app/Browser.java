// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * What the sign-in tests drive in place of a browser (ADR-0178 has no browser runner): a client that
 * follows no redirect on its own and keeps cookies per origin, so every hop of the flow is a step a
 * test can assert.
 *
 * <p>It keeps a cookie whatever its {@code Secure} flag says, because the app under test is on plain
 * HTTP while its {@code base-url} may be {@code https}. That flag is asserted from the
 * {@code Set-Cookie} header itself.
 */
final class Browser {

    record Response(URI uri, int status, java.net.http.HttpHeaders headers, String body) {

        /** {@code Location}, resolved against the request as a browser resolves it. */
        URI location() {
            return uri.resolve(headers.firstValue("Location").orElseThrow(
                    () -> new AssertionError("no Location on a " + status + " from " + uri)));
        }

        String rawLocation() {
            return headers.firstValue("Location").orElse(null);
        }

        List<String> setCookies() {
            return headers.allValues("Set-Cookie");
        }
    }

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, Map<String, String>> jar = new ConcurrentHashMap<>();

    Response get(URI uri) {
        return get(uri, Map.of());
    }

    Response get(URI uri, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET();
        headers.forEach(request::header);
        return send(uri, request);
    }

    Response post(URI uri, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(field -> URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return send(uri, HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    Optional<String> cookie(URI origin, String name) {
        return Optional.ofNullable(jar.getOrDefault(origin.getRawAuthority(), Map.of()).get(name));
    }

    void setCookie(URI origin, String name, String value) {
        jar.computeIfAbsent(origin.getRawAuthority(), kept -> new LinkedHashMap<>()).put(name, value);
    }

    private Response send(URI uri, HttpRequest.Builder request) {
        Map<String, String> cookies = jar.getOrDefault(uri.getRawAuthority(), Map.of());
        if (!cookies.isEmpty()) {
            request.header("Cookie", cookies.entrySet().stream()
                    .map(cookie -> cookie.getKey() + "=" + cookie.getValue())
                    .collect(Collectors.joining("; ")));
        }
        HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        Map<String, String> kept = jar.computeIfAbsent(uri.getRawAuthority(), origin -> new LinkedHashMap<>());
        for (String header : response.headers().allValues("Set-Cookie")) {
            for (HttpCookie cookie : HttpCookie.parse(header)) {
                if (cookie.getMaxAge() == 0) {
                    kept.remove(cookie.getName());
                } else {
                    kept.put(cookie.getName(), cookie.getValue());
                }
            }
        }
        return new Response(uri, response.statusCode(), response.headers(), response.body());
    }
}
