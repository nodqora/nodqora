// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.signin;

import io.nodqora.core.config.SecretReferences;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.core.env.Environment;

/**
 * Reads {@code nodqora.authentication} into a {@link SignIn} (ADR-0176).
 *
 * <p>Only the absent key boots and explains (ADR-0173 §1). A key that is there and wrong stops
 * startup with a message naming it, because guessing past it would either fail open or ignore what
 * the operator wrote.
 *
 * <p>It binds with no placeholder resolver, as {@code NodqoraProperties} binds
 * {@code nodqora.environments}, so {@code ${env:}} and {@code ${file:}} reach
 * {@link SecretReferences} as written rather than being read as Spring's {@code ${name:default}}
 * (ADR-0165).
 */
final class SignInBinder {

    private static final String KEY = "nodqora.authentication";
    private static final ConfigurationPropertyName ROOT = ConfigurationPropertyName.of(KEY);
    private static final ConfigurationPropertyName OIDC = ConfigurationPropertyName.of(KEY + ".oidc");
    private static final ConfigurationPropertyName SESSION = ConfigurationPropertyName.of(KEY + ".session");

    /** Boot's own client grammar, which backs off once {@code signin} supplies a registration. */
    private static final ConfigurationPropertyName SPRING_CLIENT =
            ConfigurationPropertyName.of("spring.security.oauth2.client");

    private static final List<ConfigurationPropertyName> GRAMMAR = Stream.of(
                    "oidc.base-url",
                    "oidc.issuer",
                    "oidc.client-id",
                    "oidc.client-secret",
                    "oidc.scopes",
                    "oidc.name-claim",
                    "session.idle",
                    "session.absolute")
            .map(key -> ConfigurationPropertyName.of(KEY + "." + key))
            .toList();

    /** The key as written, before defaults, so a missing value can still be told from a default. */
    record DeclaredOidc(
            String baseUrl,
            String issuer,
            String clientId,
            String clientSecret,
            List<String> scopes,
            String nameClaim) {}

    record DeclaredSession(Duration idle, Duration absolute) {}

    private SignInBinder() {}

    static SignIn bind(Environment environment, SecretReferences secrets) {
        Iterable<ConfigurationPropertySource> sources = ConfigurationPropertySources.get(environment);
        List<ConfigurationPropertyName> declared = declaredNames(sources);
        refuseSpringClientKeys(declared);
        refuseKeysOutsideTheGrammar(declared);

        Binder literal = new Binder(sources);
        Optional<String> scalar = Optional.ofNullable(literal.bind(KEY, String.class).orElse(null));
        boolean oidc = declared.stream().anyMatch(OIDC::isAncestorOf);
        boolean session = declared.stream().anyMatch(SESSION::isAncestorOf);

        if (scalar.isPresent()) {
            if (!scalar.get().equals("none")) {
                throw new IllegalStateException(
                        "%s is '%s': it takes none, or an oidc block".formatted(KEY, scalar.get()));
            }
            if (oidc || session) {
                throw new IllegalStateException("%s is none, and %s.%s is declared too: remove one of them"
                        .formatted(KEY, KEY, oidc ? "oidc" : "session"));
            }
            return new SignIn.Open();
        }
        if (oidc) {
            return new SignIn.Provider(
                    provider(literal.bind(OIDC, Bindable.of(DeclaredOidc.class)).get(), secrets),
                    session(literal.bind(SESSION, Bindable.of(DeclaredSession.class))
                            .orElse(new DeclaredSession(null, null))));
        }
        if (session) {
            throw new IllegalStateException(
                    "%s.session is declared without %s.oidc: session limits belong to a provider".formatted(KEY, KEY));
        }
        return new SignIn.NotConfigured();
    }

    /**
     * Every name under the two roots this binder polices, from every source. The process environment
     * is iterable, which is what lets {@code NODQORA_AUTHENTICATION=none} be seen beside a file's
     * {@code oidc:} block (ADR-0152).
     */
    private static List<ConfigurationPropertyName> declaredNames(Iterable<ConfigurationPropertySource> sources) {
        return StreamSupport.stream(sources.spliterator(), false)
                .filter(IterableConfigurationPropertySource.class::isInstance)
                .map(IterableConfigurationPropertySource.class::cast)
                .flatMap(IterableConfigurationPropertySource::stream)
                .filter(name -> ROOT.isAncestorOf(name) || ROOT.equals(name) || SPRING_CLIENT.isAncestorOf(name))
                .distinct()
                .toList();
    }

    private static void refuseSpringClientKeys(List<ConfigurationPropertyName> declared) {
        declared.stream().filter(SPRING_CLIENT::isAncestorOf).findFirst().ifPresent(name -> {
            throw new IllegalStateException(
                    "%s is not read by Nodqora: declare sign-in under %s.oidc".formatted(name, KEY));
        });
    }

    private static void refuseKeysOutsideTheGrammar(List<ConfigurationPropertyName> declared) {
        declared.stream()
                .filter(ROOT::isAncestorOf)
                .filter(name -> GRAMMAR.stream().noneMatch(key -> key.equals(name) || key.isAncestorOf(name)))
                .findFirst()
                .ifPresent(name -> {
                    throw new IllegalStateException("%s is not a sign-in key".formatted(name));
                });
    }

    private static SignIn.Oidc provider(DeclaredOidc declared, SecretReferences secrets) {
        return new SignIn.Oidc(
                baseUrl(url("base-url", resolved("base-url", required("base-url", declared.baseUrl()), secrets))),
                url("issuer", resolved("issuer", required("issuer", declared.issuer()), secrets)),
                resolved("client-id", required("client-id", declared.clientId()), secrets),
                Optional.ofNullable(declared.clientSecret()).map(secret -> resolved("client-secret", secret, secrets)),
                scopes(declared.scopes()),
                declared.nameClaim() == null ? "name" : declared.nameClaim());
    }

    private static String required(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("%s.oidc.%s is required".formatted(KEY, key));
        }
        return value;
    }

    /**
     * ADR-0176 §2: every value under {@code oidc} goes through ADR-0014's resolver, as a plugin
     * slice's does, and one that does not resolve stops startup naming the key it sits under.
     */
    private static String resolved(String key, String value, SecretReferences secrets) {
        try {
            return (String) secrets.resolveConfig(Map.of(key, value)).get(key);
        } catch (RuntimeException e) {
            throw new IllegalStateException("%s.oidc.%s: %s".formatted(KEY, key, e.getMessage()), e);
        }
    }

    private static URI url(String key, String value) {
        try {
            URI url = new URI(value);
            if (("http".equals(url.getScheme()) || "https".equals(url.getScheme())) && url.getHost() != null) {
                return url;
            }
        } catch (URISyntaxException e) {
            // Reported below, in the operator's terms rather than the parser's.
        }
        throw new IllegalStateException(
                "%s.oidc.%s is '%s', which is not an absolute http or https URL".formatted(KEY, key, value));
    }

    /**
     * {@code redirect_uri} is {@code base-url} plus a fixed path, so Nodqora is served from the root
     * of the URL a browser reaches it on. A trailing slash is not a path.
     */
    private static URI baseUrl(URI url) {
        String path = url.getRawPath();
        if (url.getRawQuery() != null || url.getRawFragment() != null || !(path.isEmpty() || path.equals("/"))) {
            throw new IllegalStateException("%s.oidc.base-url is '%s', which has a path".formatted(KEY, url));
        }
        return URI.create(url.getScheme() + "://" + url.getRawAuthority());
    }

    /** {@code openid} is what makes the flow OIDC, so a list without it has it added (ADR-0176 §1). */
    private static List<String> scopes(List<String> declared) {
        if (declared == null) {
            return List.of("openid", "profile");
        }
        return Stream.concat(Stream.of("openid"), declared.stream()).distinct().toList();
    }

    private static SignIn.Session session(DeclaredSession declared) {
        Duration idle = positive("idle", declared.idle() == null ? Duration.ofMinutes(30) : declared.idle());
        Duration absolute =
                positive("absolute", declared.absolute() == null ? Duration.ofHours(12) : declared.absolute());
        if (idle.compareTo(absolute) > 0) {
            throw new IllegalStateException("%s.session.idle (%s) is longer than %s.session.absolute (%s), "
                    .formatted(KEY, idle, KEY, absolute) + "so it could never fire");
        }
        return new SignIn.Session(idle, absolute);
    }

    private static Duration positive(String key, Duration limit) {
        if (limit.isZero() || limit.isNegative()) {
            throw new IllegalStateException("%s.session.%s is %s, which is not positive".formatted(KEY, key, limit));
        }
        return limit;
    }
}
