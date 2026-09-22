// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An OIDC provider in the test process (ADR-0178 §2): discovery, JWKS, token and end-session, with ID
 * tokens signed by the {@code nimbus-jose-jwt} the OAuth2 client already brings.
 *
 * <p>It is strict where a real provider is, so the tests prove Nodqora's reading of OIDC and not the
 * stub's leniency: the token endpoint checks the client's basic credentials, the code, the
 * {@code redirect_uri} it was issued for, and the PKCE verifier against the challenge.
 *
 * <p>It has no login form. {@link #signsInAs} names who the next authorization request is for, and
 * the authorization endpoint answers at once with a code — a provider whose own session is alive.
 *
 * <p>"Down" answers discovery with a {@code 503}. It stands for any failure to fetch the document;
 * a provider that refuses connections outright is proven separately, against a port nothing listens
 * on.
 */
final class StubProvider implements AutoCloseable {

    static final String CLIENT_ID = "nodqora";
    static final String CLIENT_SECRET = "stub-client-secret";

    /** Who signs in: an ID token's {@code sub}, and whatever other claims it carries. */
    record User(String sub, Map<String, Object> claims) {

        static User ada() {
            return new User("a0000000-0000-4000-8000-00000000000a",
                    Map.of("name", "Ada Lovelace", "preferred_username", "ada"));
        }
    }

    private record Issued(User user, String nonce, String redirectUri, String codeChallenge) {}

    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final RSAKey key;
    private final String issuer;
    private final Map<String, Issued> codes = new ConcurrentHashMap<>();

    private volatile boolean answering = true;
    private volatile String advertisedIssuer;
    private volatile User next = User.ada();
    private volatile Map<String, String> lastAuthorizationRequest = Map.of();
    private volatile String lastIdToken;
    private volatile String lastAccessToken;

    private StubProvider() {
        try {
            key = new RSAKeyGenerator(2048).keyID("stub").generate();
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        issuer = "http://localhost:" + server.getAddress().getPort() + "/realms/stub";
        server.createContext("/", this::handle);
        server.start();
    }

    static StubProvider start() {
        return new StubProvider();
    }

    URI issuer() {
        return URI.create(issuer);
    }

    void signsInAs(User user) {
        next = user;
    }

    void goesDown() {
        answering = false;
    }

    void comesBack() {
        answering = true;
        advertisedIssuer = null;
    }

    /** Discovery answers, and its document names a different issuer from the one declared. */
    void advertisesIssuer(String other) {
        answering = true;
        advertisedIssuer = other;
    }

    Map<String, String> lastAuthorizationRequest() {
        return lastAuthorizationRequest;
    }

    String lastIdToken() {
        return lastIdToken;
    }

    String lastAccessToken() {
        return lastAccessToken;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath().substring(URI.create(issuer).getPath().length());
            switch (path) {
                case "/.well-known/openid-configuration" -> discovery(exchange);
                case "/protocol/openid-connect/auth" -> authorize(exchange);
                case "/protocol/openid-connect/token" -> token(exchange);
                case "/protocol/openid-connect/certs" -> send(exchange, 200, new JWKSet(key.toPublicJWK()).toJSONObject());
                case "/protocol/openid-connect/logout" -> send(exchange, 200, Map.of());
                default -> send(exchange, 404, Map.of("error", "not_found"));
            }
        }
    }

    private void discovery(HttpExchange exchange) throws IOException {
        if (!answering) {
            send(exchange, 503, Map.of("error", "unavailable"));
            return;
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("issuer", advertisedIssuer == null ? issuer : advertisedIssuer);
        document.put("authorization_endpoint", issuer + "/protocol/openid-connect/auth");
        document.put("token_endpoint", issuer + "/protocol/openid-connect/token");
        document.put("jwks_uri", issuer + "/protocol/openid-connect/certs");
        document.put("end_session_endpoint", issuer + "/protocol/openid-connect/logout");
        document.put("response_types_supported", List.of("code"));
        document.put("subject_types_supported", List.of("public"));
        document.put("id_token_signing_alg_values_supported", List.of("RS256"));
        document.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic"));
        document.put("code_challenge_methods_supported", List.of("S256"));
        document.put("scopes_supported", List.of("openid", "profile"));
        send(exchange, 200, document);
    }

    private void authorize(HttpExchange exchange) throws IOException {
        Map<String, String> request = form(exchange.getRequestURI().getRawQuery());
        lastAuthorizationRequest = request;
        String code = UUID.randomUUID().toString();
        codes.put(code, new Issued(next, request.get("nonce"), request.get("redirect_uri"),
                request.get("code_challenge")));
        exchange.getResponseHeaders().add("Location", request.get("redirect_uri")
                + "?code=" + code + "&state=" + URLEncoder.encode(request.get("state"), StandardCharsets.UTF_8));
        exchange.sendResponseHeaders(302, -1);
    }

    private void token(HttpExchange exchange) throws IOException {
        String basic = "Basic " + Base64.getEncoder().encodeToString(
                (CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        if (!basic.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            send(exchange, 401, Map.of("error", "invalid_client"));
            return;
        }
        Map<String, String> request = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Issued issued = codes.remove(request.getOrDefault("code", ""));
        if (issued == null
                || !issued.redirectUri().equals(request.get("redirect_uri"))
                || issued.codeChallenge() == null
                || !issued.codeChallenge().equals(challenge(request.getOrDefault("code_verifier", "")))) {
            send(exchange, 400, Map.of("error", "invalid_grant"));
            return;
        }
        lastIdToken = idToken(issued);
        lastAccessToken = "stub-access-" + UUID.randomUUID();
        send(exchange, 200, Map.of(
                "access_token", lastAccessToken,
                "token_type", "Bearer",
                "expires_in", 300,
                "refresh_token", "stub-refresh-" + UUID.randomUUID(),
                "scope", "openid profile",
                "id_token", lastIdToken));
    }

    private String idToken(Issued issued) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(issued.user().sub())
                .audience(CLIENT_ID)
                .claim("azp", CLIENT_ID)
                .claim("nonce", issued.nonce())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)));
        issued.user().claims().forEach(claims::claim);
        try {
            SignedJWT token = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
            token.sign(new RSASSASigner(key));
            return token.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, String> form(String encoded) {
        Map<String, String> values = new LinkedHashMap<>();
        if (encoded == null || encoded.isEmpty()) {
            return values;
        }
        for (String pair : encoded.split("&")) {
            int equals = pair.indexOf('=');
            values.put(URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8),
                    equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
