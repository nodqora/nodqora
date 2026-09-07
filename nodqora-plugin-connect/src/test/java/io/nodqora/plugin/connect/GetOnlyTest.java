// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.connect;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-0042's boundary test, and <b>the one thing about Connect's authorization model that can be
 * enforced from inside this codebase</b>.
 *
 * <p>Research #5 verified two facts that together leave nothing else to do. There is no read-only
 * Connect credential, and there is no per-endpoint authorization: whatever credential Nodqora holds
 * can {@code DELETE /connectors/{name}} on every connector on the cluster, and no configuration on
 * either side changes that. The only real mitigation is an operator-side GET-only reverse proxy,
 * which is outside this repository — so the half that <em>is</em> inside it is made provably
 * harmless, the same move ADR-0010 and ADR-0015 made with §5.2's boundary, and it discharges §5.6's
 * "read-only by default".
 *
 * <p>Three rules, deliberately mirroring {@code CoreKnowsNoPluginTest}. No one of them is enough:
 * the structural rule cannot see a verb passed as a string to {@code method(…)}; the blunt one
 * cannot see a call through an indirection; and both of those only know the JDK's client, so a
 * second HTTP library would slip past the pair — {@code new HttpPost(url)} names no upper-case verb
 * and builds no {@code java.net.http.HttpRequest}. The third rule is what closes that: this plugin
 * may not reach an HTTP type that is not the JDK's, so there is no second client for the first two
 * rules to be blind about. Together they mean a write reaches the cluster only by someone defeating
 * all three on purpose, which is a different act from a convenient edit.
 */
class GetOnlyTest {

    private static final JavaClasses PLUGIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.nodqora.plugin.connect");

    /**
     * The structural half: no class in this plugin may build a request with a mutating verb.
     * {@code method(…)} is banned outright rather than inspected, because its verb is a string and
     * ArchUnit cannot read a string — a rule that let it through would be a rule that checks nothing.
     */
    @Test
    void the_plugin_never_builds_a_mutating_request() {
        for (String verb : List.of("POST", "PUT", "DELETE", "method")) {
            noClasses()
                    .should()
                    .callMethodWhere(DescribedPredicate.describe(
                            "HttpRequest.Builder." + verb + "(..)",
                            call -> call.getTarget().getOwner().isAssignableTo(HttpRequest.Builder.class)
                                    && call.getTarget().getName().equals(verb)))
                    .because("ADR-0042: the `connect` plugin issues only GET, ever — there is no "
                            + "read-only Connect credential, so this is the one thing that can be enforced")
                    .check(PLUGIN);
        }
    }

    /**
     * The blunter half: the plugin's <em>source</em> may not contain a mutating verb at all.
     *
     * <p>It is a word-boundary match, so {@code deleted} and a comment about {@code posting} pass
     * while a bare {@code DELETE} does not — and ADR-0015's ruling applies unchanged: this will
     * occasionally fire on something innocent, and renaming it is a smaller cost than the drift the
     * check prevents.
     */
    @Test
    void the_plugin_source_never_names_a_mutating_verb() throws IOException {
        List<String> banned = List.of("POST", "PUT", "PATCH", "DELETE");
        Path main = Path.of("nodqora-plugin-connect", "src", "main");

        List<String> offences = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(main)) {
            for (Path file : sources.filter(Files::isRegularFile).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int number = 0; number < lines.size(); number++) {
                    for (String verb : banned) {
                        Matcher matcher =
                                Pattern.compile("\\b" + verb + "\\b").matcher(lines.get(number));
                        if (matcher.find()) {
                            offences.add("%s:%d names '%s': %s"
                                    .formatted(file, number + 1, verb, lines.get(number).strip()));
                        }
                    }
                }
            }
        }

        assertThat(offences)
                .as("ADR-0042: Nodqora is provably harmless against a Connect cluster it could "
                        + "otherwise delete, and that is checkable rather than aspirational")
                .isEmpty();
    }

    /**
     * The half that keeps the other two from being blind. Both rules above are written against
     * {@code java.net.http}: the first names its {@code HttpRequest.Builder} methods, and the second
     * looks for bare upper-case verbs, which a well-named client type like {@code HttpPost} does not
     * contain. Adding a second HTTP library would therefore defeat both without tripping either, so
     * the way to keep them honest is to allow only one library in the first place.
     *
     * <p>Jackson and Spring are on the classpath here and neither offers an HTTP client, so this
     * costs nothing today. It costs something on the day someone reaches for one, which is the day
     * it is worth having.
     */
    @Test
    void no_second_http_client_may_enter_the_plugin() {
        DescribedPredicate<JavaClass> foreignHttpType =
                new DescribedPredicate<>("an HTTP type outside java.net.http") {
                    @Override
                    public boolean test(JavaClass type) {
                        String packageName = type.getPackageName();
                        boolean http = packageName.contains(".http") || type.getSimpleName().startsWith("Http");
                        return http
                                && !packageName.startsWith("java.net.http")
                                && !packageName.startsWith("io.nodqora");
                    }
                };

        noClasses()
                .should()
                .dependOnClassesThat(foreignHttpType)
                .as("ADR-0042: one HTTP client, so one place the GET-only rules have to hold")
                .check(PLUGIN);
    }

    /** The rule is only worth having if the thing it guards actually exists and is exercised. */
    @Test
    void the_one_request_builder_is_the_one_that_reaches_a_cluster() {
        assertThat(PLUGIN.contain(HttpConnectApi.class.getName()))
                .as("the GET-only rule guards a real client, not an empty package")
                .isTrue();
    }
}
