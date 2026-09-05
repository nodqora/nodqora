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

    // The Connect REST API is HTTP and JSON and nothing else, so the JDK client plus Jackson is the
    // whole dependency. ADR-0042 makes `GET` the only verb this module may ever issue, and a client
    // with one method per verb is what lets `GetOnlyTest` check that structurally.
    implementation("com.fasterxml.jackson.core:jackson-databind")

    // ADR-0099's recording, published as test fixtures so `nodqora-app`'s integration tests replay
    // the same connectors through the same seam rather than keeping a second copy of them.
    testFixturesApi(project(":nodqora-plugin-api"))
    testFixturesImplementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(testFixtures(project(":nodqora-plugin-connect")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The recording is a repo-relative path, the same convention the other plugins already use.
tasks.named<Test>("test") {
    workingDir = rootProject.projectDir
}
