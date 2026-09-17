// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * ADR-0174 §2: the read path is identity-blind. Core defines no type for who is asking, and the only
 * code that reads identity is {@code io.nodqora.core.signin}, through Spring Security's own types.
 *
 * <p>A future Community feature that genuinely wants identity in the read path fails this, and that
 * failure is the moment to reopen ADR-0174, with the consumer in hand (ADR-0115).
 */
class OnlySignInKnowsWhoIsAskingTest {

    private static final JavaClasses CORE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.nodqora.core");

    @Test
    void nothing_outside_signin_depends_on_spring_security() {
        noClasses()
                .that()
                .resideOutsideOfPackage("io.nodqora.core.signin..")
                .should()
                .dependOnClassesThat(resideInAPackage("org.springframework.security.."))
                .because("ADR-0174: nothing in core but sign-in knows who is asking")
                .check(CORE);
    }
}
