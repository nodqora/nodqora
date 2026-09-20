// SPDX-License-Identifier: Apache-2.0
plugins {
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.16") }
}

dependencies {
    // ADR-0015: a plugin depends on plugin-api and nothing else of ours.
    implementation(project(":nodqora-plugin-api"))
    implementation("org.springframework:spring-context")
    implementation("jakarta.validation:jakarta.validation-api")
    implementation("org.slf4j:slf4j-api")

    // The Prometheus HTTP API is HTTP and JSON and nothing else, so the JDK client plus Jackson is
    // the whole dependency, as it is for `connect`. No Prometheus client library: those are for
    // exposing series, not for reading them.
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
