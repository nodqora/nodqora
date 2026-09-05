// SPDX-License-Identifier: Apache-2.0
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
    // `lag.default` is ADR-0042's key and a Java keyword; @JsonProperty is what lets the record
    // field be named legally without renaming the operator-facing key.
    implementation("com.fasterxml.jackson.core:jackson-annotations")

    // The only place a real broker is spoken to (ADR-0099). No test reaches it: the plugin's
    // outbound seam is `KafkaApi`, and everything above it is exercised against a recording.
    implementation("org.apache.kafka:kafka-clients:3.9.0")

    // ADR-0099's recording, published as test fixtures so `nodqora-app`'s integration tests replay
    // the same objects through the same seam rather than keeping a second copy of them.
    testFixturesApi(project(":nodqora-plugin-api"))
    testFixturesImplementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(testFixtures(project(":nodqora-plugin-kafka")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The recording is a repo-relative path, the same convention the other plugins already use.
tasks.named<Test>("test") {
    workingDir = rootProject.projectDir
}
