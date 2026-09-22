// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.signin;

import io.nodqora.core.config.SecretReferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Sign-in's binding, inside its own package so an assembly that drops {@code io.nodqora.core.signin}
 * from its scan drops this with it (ADR-0174 §3).
 *
 * <p>Binding is a bean, so a wrong declaration stops startup rather than the first sign-in
 * (ADR-0176 §3). What acts on it is {@link SignInChain}, kept apart so the grammar's tests bind a
 * declaration without a web server.
 */
@Configuration
public class SignInWiring {

    private static final Logger log = LoggerFactory.getLogger(SignInWiring.class);

    @Bean
    public SignIn signIn(Environment environment) {
        SignIn signIn = SignInBinder.bind(environment, SecretReferences.fromProcess());
        if (signIn instanceof SignIn.Open) {
            // ADR-0173 §2: open is declared, and says so quietly — here for the operator, and in the
            // shell's session area for the reader.
            log.warn("nodqora.authentication is none: every environment is served to anyone who can reach "
                    + "this install, without signing in");
        }
        return signIn;
    }
}
