package io.nodqora.core.api;

/**
 * ADR-0060: an unknown or unconfigured environment key is a plain 404, rendered as RFC 9457
 * {@code application/problem+json}.
 */
public class UnknownEnvironmentException extends RuntimeException {

    private final String environmentKey;

    public UnknownEnvironmentException(String environmentKey) {
        super("no environment '%s' is configured".formatted(environmentKey));
        this.environmentKey = environmentKey;
    }

    public String environmentKey() {
        return environmentKey;
    }
}
