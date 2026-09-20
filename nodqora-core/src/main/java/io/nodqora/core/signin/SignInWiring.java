// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.signin;

import io.nodqora.core.config.SecretReferences;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Sign-in's wiring, inside its own package so an assembly that drops {@code io.nodqora.core.signin}
 * from its scan drops this with it (ADR-0174 §3).
 *
 * <p>Binding is a bean, so a wrong declaration stops startup rather than the first sign-in
 * (ADR-0176 §3). Nothing acts on it yet: the chain, the session and discovery follow. Until they
 * do, {@link SignInIsNotEnforcedYet} refuses a declared provider, so that no install believes it is
 * closed while it is not.
 */
@Configuration
public class SignInWiring {

    @Bean
    public SignIn signIn(Environment environment) {
        return SignInBinder.bind(environment, SecretReferences.fromProcess());
    }
}
