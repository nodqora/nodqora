plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
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

// The fixture directory is named in application.yaml as a repo-relative path, so tests and
// `bootRun` must agree on where the repo root is. ADR-0099 makes those files genuine inputs that
// double as the demo topology, so there is deliberately no second copy under test resources.
tasks.named<Test>("test") {
    workingDir = rootProject.projectDir
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
