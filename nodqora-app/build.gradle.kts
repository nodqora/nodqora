// SPDX-License-Identifier: Apache-2.0
import com.github.jk1.license.render.JsonReportRenderer
import groovy.json.JsonSlurper
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    // ADR-0154: identifies the licence of each bundled jar. Chosen over a hand-rolled reader
    // because 39 of the 80 bundled POMs declare no <licenses> element of their own and inherit it
    // from a parent, and walking that chain correctly is the whole job.
    id("com.github.jk1.dependency-license-report") version "2.9"
}

dependencies {
    implementation(project(":nodqora-core"))
    // ADR-0015: plugins are @Components in the same deployable, collected by injecting List<Plugin>.
    implementation(project(":nodqora-plugin-yaml"))
    implementation(project(":nodqora-plugin-kubernetes"))
    implementation(project(":nodqora-plugin-connect"))
    implementation(project(":nodqora-plugin-kafka"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // ADR-0099: the recording is replayed through the plugin's own outbound seam, so the
    // integration tests exercise the real plugin against the real fixture objects.
    testImplementation(testFixtures(project(":nodqora-plugin-kubernetes")))
    testImplementation(testFixtures(project(":nodqora-plugin-connect")))
    testImplementation(testFixtures(project(":nodqora-plugin-kafka")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ADR-0152: the shipped `application.yaml` declares no environments, so both tasks load the demo
// from the repository instead. It is one file, in one place, with two consumers — the `test` task,
// which binds it for the golden documents, the incident scenario and the frontend routing tests,
// and `bootRun`, which is the only way to look at the thing on a laptop. A second copy under test
// resources would be the drift ADR-0099 refused for the topology, one level up.
//
// Both paths are repo-relative — the demo config's `yaml: { dir: ... }` lines and the location of
// the demo config itself — which is why `workingDir` is pinned to the repo root rather than to the
// subproject. `optional:` so that neither task dies if the file is gone; the empty roster it leaves
// behind is the same one a config-less container serves.
val demoConfig = "optional:file:./fixtures/reference-pipeline/application-demo.yaml"

tasks.named<Test>("test") {
    workingDir = rootProject.projectDir
    systemProperty("spring.config.additional-location", demoConfig)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
    systemProperty("spring.config.additional-location", demoConfig)
}

// ---------------------------------------------------------------------------------------------
// ADR-0154: THIRD-PARTY attribution for the Community image.
// ---------------------------------------------------------------------------------------------

// `bootJar` injects spring-boot-jarmode-tools into BOOT-INF/lib through an internal detached
// configuration, so it ships in the image while appearing on no configuration a licence plugin can
// read. Rather than special-casing a filename, the strays are declared here and resolved like
// anything else — the reconciliation below then has a coordinate to match them against.
val thirdPartyExtras: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    thirdPartyExtras("org.springframework.boot:spring-boot-jarmode-tools:3.4.1")
}

licenseReport {
    configurations = arrayOf("runtimeClasspath", thirdPartyExtras.name)
    renderers = arrayOf(JsonReportRenderer("licenses.json", false))
}

/** A licence a dependency is offered under, as its publisher names it. */
data class License(val name: String, val url: String?)

/** One bundled artifact, with whatever attribution could be recovered for it. */
data class Attribution(
    val coordinate: String,
    val displayName: String,
    val licenses: List<License>,
    val homepage: String?,
    val licenseText: String?,
    val noticeText: String?,
)

// Publishers name the same licence many ways — this build sees six spellings of Apache-2.0 across
// 80 jars. Folded to SPDX identifiers so the file reads as one document. Only unambiguous synonyms
// appear here: anything whose version or variant is not certain from the name alone passes through
// verbatim, because guessing at a licence is the one thing this task must never do. In particular
// "GNU Lesser General Public License" is left alone, since the name omits the version.
val spdxSynonyms = mapOf(
    "apache license, version 2.0" to "Apache-2.0",
    "apache license version 2.0" to "Apache-2.0",
    "the apache software license, version 2.0" to "Apache-2.0",
    "apache software license - version 2.0" to "Apache-2.0",
    "apache license 2.0" to "Apache-2.0",
    "the apache license, version 2.0" to "Apache-2.0",
    "apache 2.0" to "Apache-2.0",
    "apache-2.0" to "Apache-2.0",
    "the mit license" to "MIT",
    "mit license" to "MIT",
    "mit" to "MIT",
    "bsd 2-clause license" to "BSD-2-Clause",
    "the bsd 2-clause license" to "BSD-2-Clause",
    "bsd-2-clause" to "BSD-2-Clause",
    "bsd 3-clause license" to "BSD-3-Clause",
    "bsd-3-clause" to "BSD-3-Clause",
    "eclipse public license - v 1.0" to "EPL-1.0",
    "eclipse public license v1.0" to "EPL-1.0",
    "epl 1.0" to "EPL-1.0",
    "eclipse public license - v 2.0" to "EPL-2.0",
    "eclipse public license v2.0" to "EPL-2.0",
    "eclipse public license v. 2.0" to "EPL-2.0",
    "epl 2.0" to "EPL-2.0",
    "gnu general public license, version 2 with the gnu classpath exception" to
        "GPL-2.0-with-classpath-exception",
    "gpl2 w/ cpe" to "GPL-2.0-with-classpath-exception",
    "common development and distribution license (cddl) version 1.0" to "CDDL-1.0",
    "isc" to "ISC",
)

fun normaliseLicenseName(raw: String): String =
    spdxSynonyms[raw.trim().lowercase()] ?: raw.trim()

tasks.register("thirdParty") {
    group = "distribution"
    description = "Generates the THIRD-PARTY attribution file for the Community image (ADR-0154)."

    dependsOn(tasks.named("bootJar"), tasks.named("generateLicenseReport"))

    val headerFile = layout.projectDirectory.file("third-party-header.txt")
    val overridesFile = layout.projectDirectory.file("third-party-overrides.json")
    val licenseReportJson = layout.buildDirectory.file("reports/dependency-license/licenses.json")
    val outputFile = layout.buildDirectory.file("third-party/THIRD-PARTY")
    val bootJarFile = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")
        .flatMap { it.archiveFile }
    // ADR-0154 makes both external inputs required rather than defaulted. The npm half is produced
    // by the bundler in the image's node stage and the base image is pinned in the Dockerfile;
    // neither has a sane default here, and quietly inventing one is how half a notices file ships.
    val npmFragmentPath = providers.gradleProperty("npmFragment")
    val baseImageRef = providers.gradleProperty("baseImage")
    val runtimeArtifacts = configurations.named("runtimeClasspath")
    val extraArtifacts = configurations.named(thirdPartyExtras.name)

    inputs.file(headerFile)
    inputs.file(overridesFile)
    inputs.file(bootJarFile)
    inputs.file(licenseReportJson)
    // Both required inputs are declared, not just read. Without this the task is UP-TO-DATE across
    // a change of base image, and would silently reuse a THIRD-PARTY whose header names a digest
    // the image was not built on — the one claim in the file that nothing else can check.
    inputs.property("baseImage", baseImageRef.orElse("<unset>"))
    inputs.files(providers.provider { npmFragmentPath.orNull?.let { files(it) } ?: files() })
        .withPropertyName("npmFragment")
        .optional()
    outputs.file(outputFile)

    doLast {
        val npmFragment = File(
            npmFragmentPath.orNull
                ?: error(
                    "ADR-0154: -PnpmFragment=<path to third-party-npm.json> is required. The npm " +
                        "half is emitted by rollup-plugin-license during `vite build`; without it " +
                        "this task would write a Java-only THIRD-PARTY, which is not a complete " +
                        "notices file. Run `npm run build` in frontend/ first.",
                ),
        )
        if (!npmFragment.isFile) {
            error("ADR-0154: npm fragment not found at ${npmFragment.absolutePath}.")
        }
        val baseImage = baseImageRef.orNull
            ?: error(
                "ADR-0154: -PbaseImage=<image@sha256:...> is required. The header names the base " +
                    "image by digest and that digest is pinned in the Dockerfile, which is the " +
                    "single source for it — this task will not guess one.",
            )

        @Suppress("UNCHECKED_CAST")
        val overrides = (JsonSlurper().parse(overridesFile.asFile) as Map<String, Map<String, String>>)

        // Licence identification, keyed by "group:artifact:version".
        @Suppress("UNCHECKED_CAST")
        val report = JsonSlurper().parse(licenseReportJson.get().asFile) as Map<String, Any>

        // A module may be offered under several licences, and the report cannot say whether that
        // means "or" (dual-licensed, elect one) or "and" (different parts, different terms). Every
        // named licence is therefore carried through: logback is EPL-1.0 or LGPL-2.1, and
        // tomcat-embed-core is Apache-2.0 with EPL-2.0 parts. Naming only the first would drop a
        // licence from a legal notices file, which is exactly what this task exists to prevent.
        // Entries whose name is null are skipped rather than paired with a name from a sibling
        // entry — their URL belongs to a different licence than the name would suggest.
        @Suppress("UNCHECKED_CAST")
        val reported = (report["dependencies"] as List<Map<String, Any?>>).associate { dep ->
            val name = dep["moduleName"] as String
            val version = dep["moduleVersion"] as String?
            val declared = dep["moduleLicenses"] as? List<Map<String, Any?>> ?: emptyList()
            val licenses = buildList {
                (dep["moduleLicense"] as String?)?.let {
                    add(License(normaliseLicenseName(it), dep["moduleLicenseUrl"] as String?))
                }
                declared.forEach { entry ->
                    (entry["moduleLicense"] as String?)?.let {
                        add(License(normaliseLicenseName(it), entry["moduleLicenseUrl"] as String?))
                    }
                }
            }.distinctBy { it.name }
            val homepage = (dep["moduleUrl"] as String?)
                ?: (dep["moduleUrls"] as? List<String>)?.firstOrNull()
            "$name:$version" to (licenses to homepage)
        }

        // Map every resolvable artifact file name to its coordinate. This is what lets the
        // reconciliation below speak in coordinates rather than filenames.
        val coordinateByFileName = buildMap {
            listOf(runtimeArtifacts.get(), extraArtifacts.get()).forEach { config ->
                config.incoming.artifacts.artifacts.forEach { artifact ->
                    val id = artifact.id.componentIdentifier
                    if (id is ModuleComponentIdentifier) {
                        put(artifact.file.name, "${id.group}:${id.module}:${id.version}")
                    }
                }
            }
        }

        // Membership comes from the shipped artifact, not from a configuration.
        val bundled = mutableMapOf<String, Pair<String?, String?>>() // fileName -> (licenceText, noticeText)
        ZipFile(bootJarFile.get().asFile).use { boot ->
            boot.entries().asSequence()
                .filter { it.name.startsWith("BOOT-INF/lib/") && it.name.endsWith(".jar") }
                .map { it to it.name.removePrefix("BOOT-INF/lib/") }
                .filterNot { (_, fileName) -> fileName.startsWith("nodqora-") }
                .forEach { (entry, fileName) ->
                    var licenseText: String? = null
                    var noticeText: String? = null
                    ZipInputStream(boot.getInputStream(entry)).use { nested ->
                        while (true) {
                            val e = nested.nextEntry ?: break
                            val n = e.name
                            if (!n.startsWith("META-INF/") || n.count { it == '/' } != 1) continue
                            val leaf = n.removePrefix("META-INF/").uppercase()
                            val isLicense = leaf.startsWith("LICENSE") || leaf.startsWith("LICENCE")
                            val isNotice = leaf.startsWith("NOTICE")
                            if (!isLicense && !isNotice) continue
                            val text = nested.readBytes().toString(Charsets.UTF_8).trim()
                            if (text.isEmpty()) continue
                            if (isLicense && licenseText == null) licenseText = text
                            if (isNotice && noticeText == null) noticeText = text
                        }
                    }
                    bundled[fileName] = licenseText to noticeText
                }
        }

        // The gate. Anything shipping without a coordinate cannot be attributed at all.
        val unmatched = bundled.keys.filter { it !in coordinateByFileName }.sorted()
        if (unmatched.isNotEmpty()) {
            error(
                "ADR-0154: ${unmatched.size} jar(s) ship in BOOT-INF/lib but appear on no " +
                    "resolvable configuration, so no licence can be determined for them:\n" +
                    unmatched.joinToString("\n") { "  - $it" } +
                    "\n\nDeclare each one in the `thirdPartyExtras` configuration so it resolves " +
                    "like any other dependency.",
            )
        }

        val javaAttributions = bundled.map { (fileName, texts) ->
            val coordinate = coordinateByFileName.getValue(fileName)
            val groupArtifact = coordinate.substringBeforeLast(':')
            val override = overrides[groupArtifact] ?: overrides[coordinate]
            val (reportedLicenses, homepage) = reported[coordinate] ?: (emptyList<License>() to null)
            Attribution(
                coordinate = coordinate,
                displayName = coordinate,
                licenses = override?.get("license")
                    ?.let { listOf(License(normaliseLicenseName(it), override["licenseUrl"])) }
                    ?: reportedLicenses,
                homepage = homepage,
                licenseText = texts.first ?: override?.get("licenseText"),
                noticeText = texts.second,
            )
        }.sortedBy { it.coordinate }

        @Suppress("UNCHECKED_CAST")
        val npmAttributions = (JsonSlurper().parse(npmFragment) as List<Map<String, Any?>>).map { pkg ->
            Attribution(
                coordinate = "${pkg["name"]}@${pkg["version"]}",
                displayName = "${pkg["name"]}@${pkg["version"]}",
                licenses = (pkg["license"] as String?)
                    ?.let { listOf(License(normaliseLicenseName(it), null)) }
                    ?: emptyList(),
                homepage = pkg["homepage"] as String?,
                licenseText = (pkg["licenseText"] as String?)?.trim()?.ifEmpty { null },
                noticeText = null,
            )
        }.sortedBy { it.coordinate }

        // ADR-0154: no entry may ship saying "Unknown". A stale list is worse than none and a list
        // that documents its own incompleteness is worse still.
        val unidentified = (javaAttributions + npmAttributions)
            .filter { a -> a.licenses.isEmpty() || a.licenses.all { it.name.isBlank() } }
        if (unidentified.isNotEmpty()) {
            error(
                "ADR-0154: ${unidentified.size} bundled dependenc(ies) have no identifiable " +
                    "licence:\n" +
                    unidentified.joinToString("\n") { "  - ${it.displayName}" } +
                    "\n\nAdd each to ${overridesFile.asFile.name} with the licence named on the " +
                    "project's own site or LICENSE file. Do not guess.",
            )
        }

        val out = StringBuilder()
        out.append(headerFile.asFile.readText().replace("@BASE_IMAGE@", baseImage))

        fun section(title: String) {
            out.append("\n\n")
            out.append("=".repeat(90)).append('\n')
            out.append(title).append('\n')
            out.append("=".repeat(90)).append("\n\n")
        }

        fun inventory(entries: List<Attribution>) {
            entries.forEach { a ->
                out.append("  ").append(a.displayName).append("\n")
                val label = if (a.licenses.size > 1) "Licences" else "Licence"
                out.append("      ").append(label).append(": ")
                    .append(a.licenses.joinToString(", ") { it.name }).append('\n')
                a.licenses.mapNotNull { it.url }.distinct().forEach {
                    out.append("      Licence URL: ").append(it).append('\n')
                }
                a.homepage?.let { out.append("      Project: ").append(it).append('\n') }
                if (a.licenseText == null) {
                    out.append("      (publisher ships no licence file in the artifact; see URL above)\n")
                }
                if (a.noticeText != null) {
                    out.append("      (carries a NOTICE, reproduced below)\n")
                }
                out.append('\n')
            }
        }

        section("Java libraries bundled in BOOT-INF/lib (${javaAttributions.size})")
        inventory(javaAttributions)

        section("Frontend packages bundled in the JavaScript assets (${npmAttributions.size})")
        inventory(npmAttributions)

        val notices = javaAttributions.filter { it.noticeText != null }
        section("Dependency NOTICE files, reproduced verbatim (${notices.size})")
        out.append(
            "Apache-2.0 section 4(d) requires that the NOTICE of a dependency travel with any\n" +
                "distribution that includes it. These are reproduced unaltered.\n\n",
        )
        notices.forEach { a ->
            out.append("-".repeat(90)).append('\n')
            out.append(a.displayName).append('\n')
            out.append("-".repeat(90)).append("\n\n")
            out.append(a.noticeText).append("\n\n")
        }

        // Deduplicated by exact text: identical licence bodies collapse, while bodies differing by
        // their embedded copyright line stay distinct, because that line is the part the permissive
        // licences actually require to travel.
        val texts = (javaAttributions + npmAttributions)
            .filter { it.licenseText != null }
            .groupBy { it.licenseText!! }
            .toList()
            .sortedBy { (_, users) -> users.first().displayName }
        section("Licence texts (${texts.size})")
        texts.forEach { (text, users) ->
            out.append("-".repeat(90)).append('\n')
            out.append("Applies to: ").append(users.joinToString(", ") { it.displayName }).append('\n')
            out.append("-".repeat(90)).append("\n\n")
            out.append(text).append("\n\n")
        }

        val target = outputFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(out.toString())
        logger.lifecycle(
            "THIRD-PARTY written to ${target.absolutePath} " +
                "(${javaAttributions.size} Java, ${npmAttributions.size} npm, " +
                "${notices.size} NOTICE files, ${texts.size} licence texts)",
        )
    }
}
