package io.nodqora.core.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ADR-0014: secrets are <em>references</em>, never values. Two forms, resolved at use time and never
 * persisted:
 *
 * <pre>
 * "${env:KAFKA_PROD_PASSWORD}"    the process environment
 * "${file:/etc/nodqora/kubeconfig}" a mounted file
 * </pre>
 *
 * <p>These are file config's native habitat and how the product actually deploys — a ConfigMap plus
 * a mounted Secret. Vault, AWS Secrets Manager and Key Vault slot in behind the same syntax later
 * without touching a plugin.
 */
public final class SecretReferences {

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{(env|file):([^}]+)}");

    private final Function<String, String> environment;

    public SecretReferences(Function<String, String> environment) {
        this.environment = environment;
    }

    public static SecretReferences fromProcess() {
        return new SecretReferences(System::getenv);
    }

    /** Resolves every reference in a plugin's config slice, leaving the structure alone. */
    private Object resolve(Object value) {
        return switch (value) {
            case String text -> resolveText(text);
            case Map<?, ?> map -> {
                Map<String, Object> resolved = new LinkedHashMap<>();
                map.forEach((key, element) -> resolved.put(String.valueOf(key), resolve(element)));
                yield resolved;
            }
            case List<?> list -> list.stream().map(this::resolve).toList();
            case null -> null;
            default -> value;
        };
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveConfig(Map<String, Object> config) {
        return (Map<String, Object>) resolve(config);
    }

    private String resolveText(String text) {
        Matcher matcher = REFERENCE.matcher(text);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(lookUp(matcher.group(1), matcher.group(2))));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private String lookUp(String scheme, String reference) {
        return switch (scheme) {
            case "env" -> {
                String value = environment.apply(reference);
                if (value == null) {
                    throw new IllegalStateException(
                            "config references ${env:%s}, which is not set".formatted(reference));
                }
                yield value;
            }
            case "file" -> {
                try {
                    yield Files.readString(Path.of(reference)).strip();
                } catch (IOException e) {
                    throw new UncheckedIOException(
                            "config references ${file:%s}, which is unreadable".formatted(reference), e);
                }
            }
            default -> throw new IllegalStateException("unreachable secret scheme " + scheme);
        };
    }
}
