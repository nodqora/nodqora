package io.nodqora.plugin.kubernetes;

/**
 * A namespace could not be listed. The plugin catches this per namespace, because ADR-0026 asks it
 * to tell "the API server is unreachable" ({@code FAILED}) from "some list calls succeeded and
 * others did not" ({@code PARTIAL}) — a distinction only the caller of this seam can make.
 */
public class KubernetesApiException extends RuntimeException {

    public KubernetesApiException(String message, Throwable cause) {
        super(message, cause);
    }

    public KubernetesApiException(String message) {
        super(message);
    }
}
