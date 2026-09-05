plugins {
    id("io.spring.dependency-management")
    `java-test-fixtures`
}

dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.1") }
}

dependencies {
    // ADR-0015: a plugin depends on plugin-api and nothing else of ours.
    implementation(project(":nodqora-plugin-api"))
    implementation("org.springframework:spring-context")
    implementation("jakarta.validation:jakarta.validation-api")
    implementation("org.slf4j:slf4j-api")

    // The only place a real cluster is spoken to (ADR-0099). No test reaches it: the plugin's
    // outbound seam is `KubernetesApi`, and everything above it is exercised against a recording.
    implementation("io.fabric8:kubernetes-client:6.13.4")

    // k3s — and any cluster whose CA issues EC rather than RSA client certificates — hands back a
    // kubeconfig the Fabric8 client cannot read on its own: it defers EC key parsing to
    // BouncyCastle and fails the *listing*, not the connection, with "JcaPEMKeyConverter is
    // provided by BouncyCastle, an optional dependency". Runtime-only because nothing compiles
    // against it; it is reached reflectively by the client's key loader.
    runtimeOnly("org.bouncycastle:bcpkix-jdk18on:1.80")

    // ADR-0099's recording, published as test fixtures so `nodqora-app`'s integration tests replay
    // the same objects through the same seam rather than keeping a second copy of them.
    testFixturesApi(project(":nodqora-plugin-api"))
    testFixturesImplementation("com.fasterxml.jackson.core:jackson-databind")
    testFixturesImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation(testFixtures(project(":nodqora-plugin-kubernetes")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The recording is a repo-relative path, the same convention `nodqora-app` already uses for the
// YAML topology: one checked-in copy of the fixture, read from wherever the test happens to run.
tasks.named<Test>("test") {
    workingDir = rootProject.projectDir
}
