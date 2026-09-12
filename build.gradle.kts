// SPDX-License-Identifier: Apache-2.0
import groovy.json.JsonSlurper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

plugins {
    java
    id("org.springframework.boot") version "3.4.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "io.nodqora"
    version = "0.1.3"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.add("-parameters")
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// =============================================================================================
// ADR-0145 and ADR-0155: publishing, and the release.
// =============================================================================================

// ADR-0114 makes `nodqora-app` the Community *assembly* rather than a library: it reaches its
// audience as ADR-0150's public image and never as a Maven coordinate. ADR-0153 then put it in
// ADR-0142's version lockstep, which is why one number spans seven artifacts while only these six
// are published here. Seven versions, six coordinates — the discrepancy is the decision, not a
// gap in this list.
val publishedModules = setOf(
    "nodqora-plugin-api",
    "nodqora-core",
    "nodqora-plugin-yaml",
    "nodqora-plugin-kubernetes",
    "nodqora-plugin-connect",
    "nodqora-plugin-kafka",
)

// ADR-0150 fixes this name and ADR-0155 hardcodes the token scope built from it. It is one string
// in one place; `compose.yaml` names it too, and the release renders that file rather than letting
// the two drift.
val imageRepository = "nodqora/nodqora"
val imageName = "ghcr.io/$imageRepository"

// ADR-0155 renders `compose.yaml` into the release asset. The file also carries commentary
// addressed to a reader of this repository — why the copy in the tree will not run — which is
// false of the asset, so it is fenced and the render drops it.
val treeOnlyOpen = ">>> tree-only"
val treeOnlyClose = "<<< tree-only"

// ADR-0145: publishing needs a `write:packages` token, and credentials are operational secrets that
// live in `~/.gradle/gradle.properties` and never in the repository.
val publishUserProperty = "nodqora.publish.user"
val publishTokenProperty = "nodqora.publish.token"

// ADR-0157: some networks drop Gradle's parallel Maven Central fetches mid-handshake. This is a
// machine-local escape hatch, not a property of the build, so it lives in the same
// `~/.gradle/gradle.properties` as the credentials above and is absent by default — unset, the
// Dockerfile's substitution expands to nothing and Gradle chooses its own worker count.
val buildMaxWorkersProperty = "nodqora.build.maxWorkers"

// -------------------------------------------------------------------------------------- publish

configure(subprojects.filter { it.name in publishedModules }) {
    apply(plugin = "maven-publish")

    // ADR-0145: a sources jar, no javadoc jar. ADR-0115 makes Enterprise a source reader by
    // design, and a javadoc jar adds nothing an IDE cannot take from sources.
    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
    }

    // ADR-0145: no test-fixtures variants. Three of these modules apply `java-test-fixtures` to
    // share ADR-0099's recordings inside this build, and `maven-publish` would carry those variants
    // into the published module metadata by default — silently shipping the thing the ADR decided
    // not to ship. Suppressed here rather than in each module so the decision reads in one place.
    // `plugins.withId` because the module's own script has not run yet when this block does.
    plugins.withId("java-test-fixtures") {
        val java = components["java"] as AdhocComponentWithVariants
        listOf("testFixturesApiElements", "testFixturesRuntimeElements").forEach { variant ->
            java.withVariantsFromConfiguration(configurations[variant]) { skip() }
        }
    }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("library") {
                from(components["java"])
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                // ADR-0141: a private registry whose audience is the Enterprise build and nobody
                // else. Deliberately the same host as ADR-0150's public image, one level up.
                url = uri("https://maven.pkg.github.com/$imageRepository")
                credentials {
                    username = providers.gradleProperty(publishUserProperty).orNull
                    password = providers.gradleProperty(publishTokenProperty).orNull
                }
            }
        }
    }

    // Act 2 of ADR-0155's ordering: the Maven coordinates go out *after* the image has been proven
    // to build and *before* anything public is pushed. `releaseImage` is a dependency of the
    // aggregate below rather than of these tasks, so without this edge Gradle is free to publish
    // first — which is exactly the ordering the ADR spent its argument on.
    tasks.matching { it.name.startsWith("publish") }.configureEach {
        mustRunAfter(rootProject.tasks.named("releaseImage"))
    }
}

// -------------------------------------------------------------------------------------- release

/** Runs a command, relaying its output to the build log. Returns the exit status. */
fun releaseExec(vararg command: String): Int {
    // Not `inheritIO()`. Gradle runs the build in a daemon whose stdio is not the terminal, so
    // inheriting it sends a multi-minute `buildx` run somewhere nobody is looking — and takes
    // docker's own error message with it on the way. Every line goes through Gradle's logger.
    val process = ProcessBuilder(*command).directory(rootDir).redirectErrorStream(true).start()
    process.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
    return process.waitFor()
}

/**
 * Runs a command quietly. Returns the exit status and the combined output, trimmed.
 *
 * [input] is written to the process's stdin and the stream closed; a credential helper reads the
 * registry it is being asked about that way, and reads until end-of-input, so leaving stdin open
 * would hang the gate rather than fail it.
 */
fun releaseCapture(vararg command: String, input: String? = null): Pair<Int, String> {
    val process = ProcessBuilder(*command).directory(rootDir).redirectErrorStream(true).start()
    process.outputStream.bufferedWriter().use { writer -> input?.let { writer.write(it) } }
    val output = process.inputStream.bufferedReader().readText().trim()
    return process.waitFor() to output
}

/** A username and the secret behind it. Read to be presented to ghcr.io, and to nothing else. */
data class GhcrCredential(val username: String, val secret: String) {
    // A data class prints its fields, and this one would print the secret into a build log the
    // first time it reached an exception message or a `logger` call. Nothing needs it, so nothing
    // gets it — the gate's refusals name what the credential may do, never what it is.
    override fun toString() = "GhcrCredential($username, ****)"
}

/**
 * The ghcr.io credential `buildx --push` would use, resolved the way Docker resolves one: a helper
 * named for the registry first, then the global store, then the inline `auth`. Null when there is
 * none to be had — including when the helper binary is missing or has nothing stored, because in
 * both of those cases `buildx` gets nothing either.
 *
 * The three sections are keyed independently on purpose. `auths` may hold `https://ghcr.io/` where
 * `credHelpers` holds `ghcr.io`; taking the helper's key from `auths` would miss the helper and
 * refuse a machine that can push perfectly well.
 */
fun ghcrCredential(config: File): GhcrCredential? {
    if (!config.isFile) return null

    @Suppress("UNCHECKED_CAST")
    val parsed = runCatching { JsonSlurper().parse(config) as Map<String, Any?> }.getOrNull()
        ?: return null
    fun ghcrKeyIn(section: String) = (parsed[section] as? Map<*, *>)
        ?.keys?.map { it.toString() }?.firstOrNull { it.contains("ghcr.io") }

    val helperKey = ghcrKeyIn("credHelpers")
    val authsKey = ghcrKeyIn("auths")
    // A helper named for this registry wins; `credsStore` holds everything and is asked second.
    val helper = helperKey?.let { (parsed["credHelpers"] as? Map<*, *>)?.get(it)?.toString() }
        ?: parsed["credsStore"]?.toString()
    if (helper != null) {
        // Whichever section named the registry spells it the way the helper has it stored.
        val server = helperKey ?: authsKey ?: "ghcr.io"
        val (status, output) = runCatching {
            releaseCapture("docker-credential-$helper", "get", input = server)
        }.getOrElse { return null }
        if (status != 0) return null

        @Suppress("UNCHECKED_CAST")
        val stored = runCatching { JsonSlurper().parseText(output) as Map<String, Any?> }
            .getOrNull() ?: return null
        val username = stored["Username"]?.toString() ?: return null
        val secret = stored["Secret"]?.toString() ?: return null
        return GhcrCredential(username, secret)
    }

    val encoded = ((parsed["auths"] as? Map<*, *>)?.get(authsKey) as? Map<*, *>)
        ?.get("auth")?.toString() ?: return null
    val decoded = runCatching { String(Base64.getDecoder().decode(encoded)) }.getOrNull()
        ?: return null
    val separator = decoded.indexOf(':')
    if (separator < 0) return null
    return GhcrCredential(decoded.substring(0, separator), decoded.substring(separator + 1))
}

/**
 * Asks ghcr.io for a token scoped to [actions] on [repository], anonymously unless a [credential]
 * is given. One call, two readers: act 4 asks anonymously for `pull` and reads a refusal as *this
 * package is not public*; the gate asks with the credential for `pull,push` and reads the answer
 * as *what this machine may do*. What a non-200 means is therefore left to the caller.
 */
fun ghcrTokenResponse(
    repository: String,
    actions: String,
    credential: GhcrCredential? = null,
): HttpResponse<String> {
    val uri = URI.create("https://ghcr.io/token?service=ghcr.io&scope=repository:$repository:$actions")
    val request = HttpRequest.newBuilder(uri).GET()
    credential?.let {
        val basic = Base64.getEncoder().encodeToString("${it.username}:${it.secret}".toByteArray())
        request.header("Authorization", "Basic $basic")
    }
    return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        .send(request.build(), HttpResponse.BodyHandlers.ofString())
}

/** The `token` field of a ghcr.io token response, or null when the body carries none. */
fun ghcrTokenIn(body: String): String? = runCatching {
    @Suppress("UNCHECKED_CAST")
    (JsonSlurper().parseText(body) as Map<String, Any?>)["token"] as? String
}.getOrNull()

/**
 * What ghcr.io says this credential may actually do to [repository] — the gate's whole question,
 * asked of the registry instead of inferred from a file.
 *
 * A registry does not refuse an over-broad scope request; it answers 200 and *narrows* what it
 * grants, so the answer is in the token's `access` claim and never in the status code. A failure
 * is returned rather than thrown so that the gate owns the wording of its own refusal.
 */
fun ghcrGrantedActions(credential: GhcrCredential, repository: String): Result<Set<String>> {
    val response = runCatching { ghcrTokenResponse(repository, "pull,push", credential) }
        .getOrElse { return Result.failure(it) }

    // A refusal to issue the token at all is an answer, not an outage: this credential gets
    // nothing on this repository, which is the same verdict as a token granting nothing.
    if (response.statusCode() == 401 || response.statusCode() == 403) {
        return Result.success(emptySet())
    }
    if (response.statusCode() != 200) {
        return Result.failure(
            IllegalStateException(
                "HTTP ${response.statusCode()} from ghcr.io/token — ${response.body().trim()}",
            ),
        )
    }

    val token = ghcrTokenIn(response.body()) ?: return Result.failure(
        IllegalStateException("ghcr.io returned no token in ${response.body().trim()}"),
    )
    return registryTokenActions(token, repository)
}

/**
 * The actions a registry token's `access` claim names for [repository] — what was granted, which
 * is not what was asked for.
 *
 * The middle segment of a JWT is its claims, base64url and unpadded. They are read, not verified:
 * the signature is the registry's business, and this claim is only ever used to refuse early. A
 * token naming no access at all is a token that grants nothing, not a malformed one — but a token
 * that is not a JWT is a failure, because then nothing here has been measured.
 */
fun registryTokenActions(token: String, repository: String): Result<Set<String>> {
    @Suppress("UNCHECKED_CAST")
    val claims = runCatching {
        JsonSlurper().parseText(
            String(Base64.getUrlDecoder().decode(token.split(".")[1])),
        ) as Map<String, Any?>
    }.getOrElse {
        return Result.failure(
            IllegalStateException("ghcr.io's token is not a JWT this can read the `access` claim of"),
        )
    }

    val access = claims["access"] as? List<*> ?: return Result.success(emptySet())
    return Result.success(
        access.filterIsInstance<Map<*, *>>()
            .filter { it["type"] == "repository" && it["name"] == repository }
            .flatMap { (it["actions"] as? List<*>).orEmpty() }
            .map { it.toString() }
            .toSet(),
    )
}

/** The RFC 3339 instant both image builds label themselves with. Written by the gate, read twice. */
val releaseCreatedFile = layout.buildDirectory.file("release/created.txt")

/** The rendered install file: `compose.yaml` with ADR-0155's `@VERSION@` placeholder resolved. */
val releaseComposeFile = layout.buildDirectory.file("release/compose.yaml")

val releasePreflight = tasks.register("releasePreflight") {
    description = "ADR-0145's release gates, run before anything is built."

    val releaseVersion = version.toString()
    val composeTemplate = layout.projectDirectory.file("compose.yaml")
    val includedBuilds = gradle.includedBuilds.map { it.name }
    val publishUser = providers.gradleProperty(publishUserProperty)
    val publishToken = providers.gradleProperty(publishTokenProperty)
    val createdFile = releaseCreatedFile

    outputs.upToDateWhen { false }

    doLast {
        // ADR-0155 rules out `docs/releasing.md` on the grounds that every step the task cannot
        // enforce is taught by the gate that fires on it. So each message below says what to do
        // next, not merely what is wrong; a terse gate silently reintroduces the rejected document.

        // ADR-0143: a version is immutable, so a SNAPSHOT is not a thing that can be released.
        if (releaseVersion.endsWith("-SNAPSHOT")) {
            error(
                "The version is $releaseVersion, and a SNAPSHOT cannot be released (ADR-0143 makes " +
                    "a published version immutable).\n\n" +
                    "Cutting a release is three acts:\n" +
                    "  1. set `version` in build.gradle.kts to the number you are releasing, and " +
                    "commit it\n" +
                    "  2. ./gradlew release\n" +
                    "  3. set `version` to the next -SNAPSHOT, and commit that\n\n" +
                    "You are at step 1.",
            )
        }

        val (branchStatus, branch) = releaseCapture("git", "rev-parse", "--abbrev-ref", "HEAD")
        if (branchStatus != 0) {
            error("Could not read the current git branch:\n$branch")
        }
        if (branch != "main") {
            error(
                "Releases are cut from `main`; this is `$branch` (ADR-0139 keeps one line of " +
                    "development, so a release from a branch names a commit that is not on it).\n\n" +
                    "Land the branch first, then release from `main`.",
            )
        }

        val (statusStatus, dirty) = releaseCapture("git", "status", "--porcelain")
        if (statusStatus != 0) {
            error("Could not read the git working tree state:\n$dirty")
        }
        if (dirty.isNotEmpty()) {
            error(
                "The working tree is not clean, so the release would not be the commit it names " +
                    "(ADR-0142: a version *is* a commit).\n\n" +
                    dirty.lines().joinToString("\n") { "  $it" } +
                    "\n\nCommit or stash these, then release again.",
            )
        }

        val tag = "v$releaseVersion"
        val (tagStatus, existingTag) = releaseCapture("git", "tag", "--list", tag)
        if (tagStatus != 0) {
            error("Could not list git tags:\n$existingTag")
        }
        if (existingTag.isNotEmpty()) {
            error(
                "$releaseVersion is already released; bump to the next -SNAPSHOT first.\n\n" +
                    "The tag $tag exists, and ADR-0143 makes a published version immutable — " +
                    "re-cutting it would leave the registry and the tag disagreeing about which " +
                    "commit $releaseVersion is.\n\n" +
                    "Set `version` in build.gradle.kts to the next -SNAPSHOT, commit it, and " +
                    "release the version after that.",
            )
        }

        // ADR-0143's inner loop: with `includeBuild` switched on, this build resolves the Enterprise
        // repository's uncommitted source, so the artifact would not be built from what is on `main`.
        if (includedBuilds.isNotEmpty()) {
            error(
                "A composite build is active, so this release would be built against uncommitted " +
                    "source in another repository (ADR-0143):\n" +
                    includedBuilds.joinToString("\n") { "  $it" } +
                    "\n\nComment out the `includeBuild` line in settings.gradle.kts and release again.",
            )
        }

        // ADR-0155 makes `docker` and `gh` release-machine prerequisites alongside the JDK. They are
        // checked here rather than discovered at act 1 or act 5, because the alternative is finding
        // out after a full test run — or, for `gh`, after the image is already pushed.
        val (dockerStatus, dockerOutput) = releaseCapture("docker", "buildx", "version")
        if (dockerStatus != 0) {
            error(
                "`docker buildx` is not usable on this machine, and ADR-0155 builds the image " +
                    "here rather than on a runner:\n$dockerOutput\n\n" +
                    "Install Docker and make sure the daemon is running.",
            )
        }
        // The daemon's own ghcr.io credential, which is *not* the one above and not the Gradle
        // property either: `buildx --push` reads ~/.docker/config.json. This is checked here and
        // not left to act 3 because act 3 runs after the coordinates are published, and ADR-0143
        // makes those immutable — an auth failure there costs the version number, not a retry.
        //
        // ADR-0163: the question is whether the credential may *push*, and it is put to ghcr.io
        // rather than inferred from the file. Presence of the key was the test until 0.1.2, and
        // presence was only ever a proxy: on a laptop the sole way a ghcr.io entry appeared was a
        // `docker login` with a PAT scoped write:packages, so it held. ADR-0159 moved the release
        // to a runner, where `docker/login-action` writes an entry for GITHUB_TOKEN — present, and
        // denied on a package that predates the workflow. The proxy stopped holding in silence,
        // which is the one way a gate can fail that costs more than having no gate.
        val dockerConfig = File(System.getProperty("user.home"), ".docker/config.json")
        val credential = ghcrCredential(dockerConfig)
        if (credential == null) {
            error(
                "The Docker daemon has no ghcr.io credential, and act 3 pushes the image with " +
                    "`buildx --push`.\n\n" +
                    "This is gated here rather than discovered at act 3, because by then the " +
                    "Maven coordinates for $releaseVersion are published and ADR-0143 makes them " +
                    "immutable — the failure would cost the version, not a retry.\n\n" +
                    "  echo <a personal access token with write:packages> | \\\n" +
                    "    docker login ghcr.io -u <your github username> --password-stdin",
            )
        }
        val granted = ghcrGrantedActions(credential, imageRepository).getOrElse { failure ->
            error(
                "The release could not ask ghcr.io what its credential may do to " +
                    "$imageRepository:\n\n" +
                    "  ${failure.message}\n\n" +
                    "The gate refuses rather than assuming, because assuming is what cost 0.1.2: " +
                    "act 3 pushes after act 2 has published coordinates ADR-0143 makes immutable.\n\n" +
                    "If ghcr.io is simply unreachable from here, the release cannot be cut from " +
                    "here and nothing is wrong with this machine. If the token no longer carries " +
                    "a readable `access` claim, the probe has stopped measuring anything and " +
                    "ADR-0163 names that as its revisit trigger — fix the gate rather than " +
                    "removing it, because act 3 has no second chance.",
            )
        }
        if ("push" !in granted) {
            error(
                "The ghcr.io credential may not push $imageRepository. ghcr.io grants it " +
                    (if (granted.isEmpty()) "nothing" else granted.sorted().joinToString(", ")) +
                    ", and act 3 pushes the image with `buildx --push`.\n\n" +
                    "This is gated here rather than discovered at act 3, because by then the " +
                    "Maven coordinates for $releaseVersion are published and ADR-0143 makes them " +
                    "immutable — the failure would cost the version, not a retry. 0.1.2 paid " +
                    "that price once, which is why this asks the registry rather than the file.\n\n" +
                    "On a runner, GITHUB_TOKEN carries `packages: write` only for packages the " +
                    "workflow itself created. A container package pushed by hand before its " +
                    "first run carries no repository link, so the link is granted once, by hand:\n\n" +
                    "  https://github.com/orgs/nodqora/packages/container/nodqora/settings\n" +
                    "  -> Manage Actions access -> Add repository -> $imageRepository -> Write\n\n" +
                    "On a laptop, log in with a personal access token scoped `write:packages` " +
                    "that can write this package:\n\n" +
                    "  echo <the token> | \\\n" +
                    "    docker login ghcr.io -u <your github username> --password-stdin",
            )
        }

        val (ghStatus, ghOutput) = releaseCapture("gh", "auth", "status")
        if (ghStatus != 0) {
            error(
                "`gh` is not usable or not authenticated, and act 5 creates the tag, the Release " +
                    "and the asset in one `gh` call:\n$ghOutput\n\nRun `gh auth login`.",
            )
        }

        // ADR-0145: the credentials are operational secrets kept in ~/.gradle/gradle.properties.
        // Presence is checked, never validity — act 2 is where a wrong token fails, and ADR-0155
        // put it before the push precisely so that failure leaves nothing public behind.
        if (!publishUser.isPresent || !publishToken.isPresent) {
            error(
                "The GitHub Packages credentials are missing. ADR-0145 keeps them out of the " +
                    "repository, so they belong in ~/.gradle/gradle.properties:\n\n" +
                    "  $publishUserProperty=<your github username>\n" +
                    "  $publishTokenProperty=<a personal access token with write:packages>\n",
            )
        }

        // ADR-0155: the file in the tree is a template on purpose, so that an unpinned install
        // cannot be copied out of `main`. This checks the `image:` line rather than the file,
        // because the tree-only commentary explaining the placeholder *mentions* it — a
        // whole-file `contains` passes happily on a compose file pinned to `:latest`, which is
        // the one asset a stranger runs unread.
        val template = composeTemplate.asFile.readText()
        if (!template.contains("image: $imageName:@VERSION@")) {
            error(
                "compose.yaml does not name `image: $imageName:@VERSION@`, so there is nothing " +
                    "for the release to render and the asset would ship some other version " +
                    "(ADR-0155).\n\n" +
                    "The file in the tree is deliberately not a runnable install.",
            )
        }
        if (!template.contains(treeOnlyOpen) || !template.contains(treeOnlyClose)) {
            error(
                "compose.yaml is missing its `$treeOnlyOpen` / `$treeOnlyClose` markers, so the " +
                    "release cannot tell the repository-facing commentary from the install.\n\n" +
                    "Without them the asset would ship a header telling the reader that the file " +
                    "does not run — which is true of the copy in the tree and false of the one " +
                    "they just downloaded.",
            )
        }

        // One timestamp, written once and read by both image builds. ADR-0154's Dockerfile defaults
        // `NODQORA_CREATED` empty rather than inventing one; filling it is the release's job, and
        // the dry run has to claim the same instant as the push or step 1 stops proving anything.
        val created = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val target = createdFile.get().asFile
        target.parentFile.mkdirs()
        target.writeText(created)

        logger.lifecycle("Releasing $releaseVersion as $tag, built at $created.")
    }
}

// The gates are worth nothing if they fire after the tests. Every subproject task is ordered behind
// them, so a dirty tree or a forgotten version bump costs a second rather than a full `build`.
// `mustRunAfter` is inert unless both tasks are in the graph, so this constrains releases only.
subprojects {
    tasks.configureEach {
        mustRunAfter(releasePreflight)
    }
}

/**
 * ADR-0155's image build, run twice: once without `--push` to prove it builds, once with. Both
 * invocations take the same build arguments and produce the same tags — passing them only to the
 * pushing one would make the dry run build a different image from the one published, which is the
 * single thing the dry run exists to rule out.
 *
 * `RUNTIME_BASE` is deliberately absent. ADR-0154 makes the Dockerfile the only home of the base
 * image digest, and `thirdParty` receives it from inside the Docker build.
 */
fun buildImage(push: Boolean) {
    val releaseVersion = version.toString()
    val created = releaseCreatedFile.get().asFile.readText().trim()
    val command = buildList {
        addAll(listOf("docker", "buildx", "build"))
        // Explicit rather than inherited: `auto` resolves against a TTY, and this one is relayed
        // through Gradle's logger a line at a time, where the interactive renderer's control
        // sequences would arrive as noise.
        add("--progress=plain")
        // ADR-0150 ships one multi-arch image. This costs roughly what single-arch costs only
        // because the Dockerfile pins both build stages `FROM --platform=$BUILDPLATFORM`.
        addAll(listOf("--platform", "linux/amd64,linux/arm64"))
        addAll(listOf("--build-arg", "NODQORA_VERSION=$releaseVersion"))
        addAll(listOf("--build-arg", "NODQORA_CREATED=$created"))
        // Passed to both invocations of this function or neither: the dry run has to build the
        // same image the push builds, and a worker count that differed between them would make
        // act 1 prove something about an image act 3 never produces.
        providers.gradleProperty(buildMaxWorkersProperty).orNull?.let {
            addAll(listOf("--build-arg", "GRADLE_MAX_WORKERS=$it"))
        }
        // ADR-0153: a release pushes the exact version and `latest`, and nothing else.
        addAll(listOf("--tag", "$imageName:$releaseVersion"))
        addAll(listOf("--tag", "$imageName:latest"))
        if (push) add("--push")
        add(".")
    }
    val status = releaseExec(*command.toTypedArray())
    if (status != 0) {
        error(
            if (push) {
                "Pushing the image failed, and act 2 has already published the Maven " +
                    "coordinates for $releaseVersion.\n\n" +
                    "Nothing is tagged, so nothing points at a half-release — but ADR-0143 makes " +
                    "a published version immutable and supersedes rather than replaces, so " +
                    "$releaseVersion is spent. Fix the cause, then bump to the next version and " +
                    "cut that one."
            } else {
                "The image does not build, so nothing has been published. This is act 1 of " +
                    "ADR-0155's ordering doing its job.\n\nIf the failure is `Multi-platform " +
                    "build is not supported for the docker driver`, create a builder that is:\n" +
                    "  docker buildx create --name nodqora-builder --driver docker-container --use"
            },
        )
    }
}

val releaseImage = tasks.register("releaseImage") {
    description = "Act 1: builds the multi-arch image without pushing it (ADR-0155)."

    dependsOn(releasePreflight)
    // ADR-0145 gates on a full `build`, tests included, passing *in this invocation*.
    dependsOn(subprojects.map { "${it.path}:build" })
    outputs.upToDateWhen { false }

    doLast { buildImage(push = false) }
}

val releasePublish = tasks.register("releasePublish") {
    description = "Act 2: publishes the six library coordinates to GitHub Packages (ADR-0145)."

    dependsOn(releaseImage)
    dependsOn(
        subprojects.filter { it.name in publishedModules }
            .map { "${it.path}:publishAllPublicationsToGitHubPackagesRepository" },
    )
}

val releasePushImage = tasks.register("releasePushImage") {
    description = "Act 3: pushes the image, cache-hot from act 1 (ADR-0155)."

    dependsOn(releasePublish)
    outputs.upToDateWhen { false }

    doLast { buildImage(push = true) }
}

val releaseVerifyPublic = tasks.register("releaseVerifyPublic") {
    description = "Act 4: checks anonymously that the pushed image is actually public (ADR-0155)."

    dependsOn(releasePushImage)
    val releaseVersion = version.toString()
    outputs.upToDateWhen { false }

    doLast {
        // The one failure invisible to the only person who can see it: the publisher's own
        // `docker pull` succeeds against a private package because the publisher is authenticated,
        // while every stranger gets `denied`. This runs in the gap ADR-0155's ordering already
        // leaves between the push and the tag, so a private package stops the release *before* the
        // irreversible act, and the re-run is clean because no tag was made.
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

        // Written as one message rather than two, because a private package denies the *token*
        // and never reaches the manifest at all — checked against ghcr.io, which answers
        // `403 DENIED` to an anonymous token request for a package a stranger cannot see. Both
        // steps below therefore end here, and neither can end anywhere a releaser would have to
        // guess from.
        fun notPublic(step: String, detail: String): Nothing = error(
            "$imageName:$releaseVersion is not anonymously pullable, so every stranger following " +
                "the install instructions would get `denied`.\n\n" +
                "  $step: $detail\n\n" +
                "A GHCR container pushed by hand is created private whatever the " +
                "repository's visibility — measured, not assumed: this package was first " +
                "pushed with the repository already public and was still denied " +
                "anonymously. There is no reliable API to flip it, so this is a " +
                "one-time manual step:\n\n" +
                "  https://github.com/orgs/nodqora/packages/container/nodqora/settings\n" +
                "  -> Danger Zone -> Change visibility -> Public\n\n" +
                "The image is pushed and nothing is tagged. Act 2 has already published the " +
                "Maven coordinates for $releaseVersion, though, and ADR-0143 makes those " +
                "immutable — so flip the toggle, then bump to the next version and cut that one. " +
                "ADR-0155 expects the first release to stop here, which is why the first tag is " +
                "the one that pays for it.",
        )

        // The same call `releasePreflight` makes with the credential, made here anonymously and
        // asked for `pull` — which is the whole difference between *can this runner push it* and
        // *can a stranger pull it*.
        val tokenResponse = ghcrTokenResponse(imageRepository, "pull")
        if (tokenResponse.statusCode() != 200) {
            notPublic(
                "anonymous pull token",
                "HTTP ${tokenResponse.statusCode()} from ghcr.io/token — " +
                    tokenResponse.body().trim(),
            )
        }

        val token = ghcrTokenIn(tokenResponse.body())
            ?: notPublic("anonymous pull token", "ghcr.io returned no token: ${tokenResponse.body()}")

        val manifestUri = URI.create("https://ghcr.io/v2/$imageRepository/manifests/$releaseVersion")
        val manifest = HttpRequest.newBuilder(manifestUri)
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .header("Authorization", "Bearer $token")
            // A multi-arch push is an index, and buildx attaches provenance by default, so the
            // index media types have to be acceptable or the registry answers 404 for an image
            // that is perfectly present.
            .header("Accept", "application/vnd.oci.image.index.v1+json")
            .header("Accept", "application/vnd.docker.distribution.manifest.list.v2+json")
            .header("Accept", "application/vnd.oci.image.manifest.v1+json")
            .header("Accept", "application/vnd.docker.distribution.manifest.v2+json")
            .build()
        val status = client.send(manifest, HttpResponse.BodyHandlers.discarding()).statusCode()

        // A 404 here is a different failure from a 403, and worth separating: the package is
        // visible and the tag is not on it, which means act 3 did not push what it said it did.
        // Sending a releaser to the visibility toggle for that would be the wrong instruction.
        when (status) {
            200 -> Unit
            404 -> error(
                "$imageRepository is anonymously visible but carries no `$releaseVersion` tag " +
                    "(HTTP 404 on the manifest).\n\n" +
                    "Act 3 reported success, so this is a push that did not land what it " +
                    "claimed. Check `docker buildx imagetools inspect " +
                    "$imageName:$releaseVersion`. Nothing is tagged, but the coordinates for " +
                    "$releaseVersion are published and immutable (ADR-0143), so the next cut is " +
                    "the next version.",
            )
            else -> notPublic("manifest HEAD", "HTTP $status")
        }
        logger.lifecycle("$imageName:$releaseVersion is anonymously pullable.")
    }
}

val releaseCompose = tasks.register("releaseCompose") {
    description = "Renders ADR-0155's `@VERSION@` placeholder into the release asset."

    dependsOn(releasePreflight)
    val releaseVersion = version.toString()
    val template = layout.projectDirectory.file("compose.yaml")
    val rendered = releaseComposeFile

    inputs.file(template)
    inputs.property("version", releaseVersion)
    outputs.file(rendered)

    doLast {
        // Same shape as `nodqora-app`'s `thirdParty` rendering `@BASE_IMAGE@`, for the same reason:
        // the value has one home, and the file in the tree stays deliberately unrunnable so an
        // unpinned `docker compose up` cannot be copied out of `main`.
        //
        // The fenced block goes first. It exists to tell a reader of this repository why the file
        // they are looking at will not run, and substitution alone would carry that explanation
        // into the asset, where it is simply untrue — the downloaded file is the install.
        val lines = template.asFile.readLines()
        val open = lines.indexOfFirst { it.contains(treeOnlyOpen) }
        val close = lines.indexOfFirst { it.contains(treeOnlyClose) }
        val kept = lines.filterIndexed { index, _ -> index < open || index > close }

        val target = rendered.get().asFile
        target.parentFile.mkdirs()
        target.writeText(
            kept.joinToString("\n", postfix = "\n").replace("@VERSION@", releaseVersion),
        )
        logger.lifecycle("Release asset written to ${target.absolutePath}.")
    }
}

tasks.register("release") {
    group = "distribution"
    description = "Cuts a release: gates, build, image, coordinates, push, visibility, tag."

    dependsOn(releaseVerifyPublic, releaseCompose)
    val releaseVersion = version.toString()
    val asset = releaseComposeFile
    outputs.upToDateWhen { false }

    doLast {
        // Act 5, and the last act on purpose. One `gh` call creates the tag, the Release and the
        // asset, so there is no window in which a tag exists without an image behind it — which
        // under ADR-0143 is the one failure that cannot be fixed by re-cutting.
        val tag = "v$releaseVersion"
        val (shaStatus, sha) = releaseCapture("git", "rev-parse", "HEAD")
        if (shaStatus != 0) {
            error("Could not read the commit to tag:\n$sha")
        }

        // `--generate-notes` would title the Release too; `--title` is passed anyway so the name
        // is the tag whatever the API decides to call it. What goes *in* the notes is left to
        // GitHub — the prose a stranger reads is the install documentation, a different artifact
        // for a different audience, and ADR-0155 is explicit that the release owns neither.
        val status = releaseExec(
            "gh", "release", "create", tag,
            "--target", sha,
            "--title", tag,
            "--generate-notes",
            asset.get().asFile.absolutePath,
        )
        if (status != 0) {
            error(
                "`gh release create` failed, so no tag was made. The image for $releaseVersion " +
                    "is pushed and the coordinates are published; an untagged image is harmless " +
                    "and undiscoverable, which is why ADR-0155 leaves this window open on " +
                    "purpose.\n\n" +
                    "The coordinates are immutable either way (ADR-0143), so bump to the next " +
                    "version and cut that one. The same applies if the tag already exists " +
                    "remotely.",
            )
        }

        logger.lifecycle(
            "Released $tag.\n" +
                "  Image:      $imageName:$releaseVersion (and :latest)\n" +
                "  Coordinates: io.nodqora:*:$releaseVersion\n" +
                "  Release:    https://github.com/$imageRepository/releases/tag/$tag\n\n" +
                "Now set `version` in build.gradle.kts to the next -SNAPSHOT and commit it.",
        )
    }
}
