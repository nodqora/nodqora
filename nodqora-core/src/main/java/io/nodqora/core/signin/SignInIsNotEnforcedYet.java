// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.signin;

import org.springframework.stereotype.Component;

/**
 * Refuses a declared provider for as long as nothing enforces one.
 *
 * <p>ADR-0176's grammar binds and validates, and {@link SignInWiring} says what follows it: the
 * chain, the session and discovery. Between the two, an operator can declare {@code
 * nodqora.authentication.oidc}, watch the install accept it, and be serving the whole API to anyone
 * who can reach the port. A declaration that validates and does nothing is worse than one that is
 * not understood, because only the second is noticed — so this is ADR-0176 §3's rule applied to the
 * build instead of to the file: a declaration the process cannot honour stops it starting.
 *
 * <p>An install that declares no provider is not refused. That is every install there is today
 * (ADR-0173), and closing it is the chain's to do, not this class's.
 *
 * <p><b>This class is deleted by the change that adds the chain.</b> It is a component of its own,
 * and not a line in {@link SignInWiring}, so that the grammar's tests bind a provider without it
 * and so that deleting it is the whole of removing it.
 */
@Component
class SignInIsNotEnforcedYet {

    SignInIsNotEnforcedYet(SignIn signIn) {
        if (signIn instanceof SignIn.Provider) {
            throw new IllegalStateException(
                    "nodqora.authentication.oidc is declared, and this build does not sign anyone in yet: "
                            + "the declaration is read and checked, and nothing enforces it, so the API would be "
                            + "served to anyone who can reach it while the configuration says otherwise. Remove "
                            + "nodqora.authentication until a release carries sign-in, and keep the port off any "
                            + "network you would not show your topology to.");
        }
    }
}
