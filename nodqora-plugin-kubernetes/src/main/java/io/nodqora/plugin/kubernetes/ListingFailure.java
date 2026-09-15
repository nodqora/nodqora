// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kubernetes;

import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLException;

/**
 * Why a namespace could not be listed, in words a reader can act on and the UI can safely show.
 *
 * <p>fabric8's outer message for a failed list is {@code Operation: [list] for kind: [Deployment] …
 * failed.} whatever went wrong. A missing kubeconfig, a {@code server:} of {@code 127.0.0.1} inside a
 * container, a TLS hostname mismatch, an expired credential and an RBAC denial all read identically
 * there and need five different fixes, so the reason walks the cause chain for the part that differs.
 *
 * <p><b>A message is quoted only when its type cannot carry configuration.</b> The reason is served by
 * the API and rendered in the UI, and SnakeYAML's parse error prints the offending kubeconfig line —
 * which can be the token. So the chain renders from four sources and nothing else:
 *
 * <ul>
 *   <li>a {@link KubernetesApiException}'s message, which this plugin wrote;
 *   <li>fabric8's resource name and the HTTP status the API server returned;
 *   <li>network and TLS failures, whose messages name a host, an address or a certificate;
 *   <li>otherwise the root cause's class name, and never its message.
 * </ul>
 *
 * <p>This and {@link Fabric8KubernetesApi} are the only main classes that name a fabric8 type: that one
 * lets fabric8's exceptions through the seam, and this one reads them.
 */
final class ListingFailure {

    private static final List<Class<? extends Throwable>> QUOTABLE = List.of(
            UnknownHostException.class, SocketException.class, InterruptedIOException.class, SSLException.class);

    private ListingFailure() {}

    /** {@code namespace n8n could not be listed: …} — the one reason discovery and health both give. */
    static String reason(String namespace, Throwable failure) {
        return "namespace %s could not be listed: %s".formatted(namespace, describe(failure));
    }

    static String describe(Throwable failure) {
        List<String> parts = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable deepest = failure;
        boolean ours = false;
        boolean quoted = false;

        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            deepest = cause;
            ours = false;
            if (cause instanceof KubernetesApiException) {
                add(parts, cause.getMessage());
                ours = true;
            } else if (cause instanceof KubernetesClientException client) {
                if (client.getResourcePlural() != null && !client.getResourcePlural().isBlank()) {
                    add(parts, "listing %s failed".formatted(client.getResourcePlural()));
                }
                if (client.getCode() > 0) {
                    // Its cause, when there is one, is the same response a second time.
                    add(parts, status(client));
                    return String.join(": ", parts);
                }
                ours = true;
            } else if (quotable(cause)) {
                String message = oneLine(cause.getMessage());
                add(parts, quoted ? message : cause.getClass().getSimpleName() + (message == null ? "" : ": " + message));
                quoted = true;
            }
        }

        // Nothing quotable said why, and the chain ends somewhere whose message may not be shown.
        if (!quoted && !ours) {
            add(parts, deepest.getClass().getSimpleName());
        }
        return parts.isEmpty() ? failure.getClass().getSimpleName() : String.join(": ", parts);
    }

    private static boolean quotable(Throwable cause) {
        return QUOTABLE.stream().anyMatch(type -> type.isInstance(cause));
    }

    /** {@code HTTP 403 Forbidden: deployments.apps is forbidden: User …} — the API server's own words. */
    private static String status(KubernetesClientException client) {
        Status status = client.getStatus();
        StringBuilder rendered = new StringBuilder("HTTP ").append(client.getCode());
        if (status != null && status.getReason() != null && !status.getReason().isBlank()) {
            rendered.append(' ').append(status.getReason());
        }
        String message = status == null ? null : oneLine(status.getMessage());
        if (message != null && !message.equals(status.getReason())) {
            rendered.append(": ").append(message);
        }
        return rendered.toString();
    }

    /** A wrapper that repeats what is already said — fabric8 and the JDK both do it — adds nothing. */
    private static void add(List<String> parts, String part) {
        String line = oneLine(part);
        if (line == null
                || (!parts.isEmpty() && (parts.getLast().equals(line) || parts.getLast().endsWith(": " + line)))) {
            return;
        }
        parts.add(line);
    }

    /** A TLS peer failure spans five lines, and a reason is one. */
    private static String oneLine(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        return message.strip().replaceAll("\\s+", " ");
    }
}
