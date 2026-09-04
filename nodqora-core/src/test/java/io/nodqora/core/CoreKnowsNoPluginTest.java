package io.nodqora.core;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-0015's boundary test. Product plan §5.2 makes it non-negotiable that the core does not know
 * whether a node came from Kafka, Kubernetes or anything else, and that is currently a promise
 * maintained by discipline — discipline erodes one convenient {@code if} at a time.
 *
 * <p>ADR-0098 pulls this forward into slice 1 even though slice 1 has one plugin, because <b>slice 2
 * is the first thing capable of breaching it</b> and a guard that arrives after the breach is not a
 * guard.
 *
 * <p>Two rules, one structural and one blunt.
 */
class CoreKnowsNoPluginTest {

    private static final JavaClasses CORE = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.nodqora.core");

    /**
     * The dependency arrow points one way: core depends on {@code plugin-api}, never on a plugin.
     * Anything a plugin needs must be an argument on a capability method, which is what keeps
     * ADR-0012's "stateless singleton, all state arrives as arguments" honest.
     */
    @Test
    void core_never_references_a_plugin_package() {
        noClasses()
                .that()
                .resideInAPackage("io.nodqora.core..")
                .should()
                .dependOnClassesThat(resideInAPackage("io.nodqora.plugin..")
                        .and(not(resideInAPackage("io.nodqora.plugin.api.."))))
                .because("ADR-0015: nodqora-core depends on plugin-api and never on any plugin")
                .check(CORE);
    }

    /**
     * The blunter half: core <em>source</em> may not contain the identifiers at all.
     *
     * <p>ADR-0015 accepts that this will occasionally fire on something innocent — a variable named
     * {@code connect} — and rules that renaming it is a smaller cost than the drift the check
     * prevents. It is a word-boundary match, so {@code getConnection()} and {@code dataSource} pass
     * while a bare {@code connect} does not.
     *
     * <p>Scope is {@code src/main}: the shipped core. This module's own tests name plugins freely,
     * because a test that exercises the real precedence order has to name the plugins it orders — the
     * orders themselves reach the core as configuration (see {@code NodqoraProperties.Plugins}), which
     * is what lets the shipped source stay clean.
     */
    @Test
    void core_source_never_names_a_technology() throws IOException {
        List<String> banned = List.of("kubernetes", "k8s", "kafka", "connect");
        Path main = Path.of("src", "main");

        List<String> offences = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(main)) {
            for (Path file : sources.filter(Files::isRegularFile).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int number = 0; number < lines.size(); number++) {
                    for (String identifier : banned) {
                        Matcher matcher = Pattern.compile("\\b" + identifier + "\\b", Pattern.CASE_INSENSITIVE)
                                .matcher(lines.get(number).toLowerCase(Locale.ROOT));
                        if (matcher.find()) {
                            offences.add("%s:%d names '%s': %s"
                                    .formatted(file, number + 1, identifier, lines.get(number).strip()));
                        }
                    }
                }
            }
        }

        assertThat(offences)
                .as("ADR-0015: nothing in the core names a technology, so §5.2's central claim is "
                        + "checkable rather than aspirational")
                .isEmpty();
    }
}
