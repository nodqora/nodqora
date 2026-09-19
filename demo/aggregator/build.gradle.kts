// SPDX-License-Identifier: Apache-2.0
plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "io.nodqora.demo"
version = "0.1.0"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.apache.kafka:kafka-streams")
    // /actuator/prometheus. With a MeterRegistry present, Boot binds the Streams client's own
    // metrics to the StreamsBuilderFactoryBean that @EnableKafkaStreams creates — no code here.
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("market-aggregator.jar")
}
