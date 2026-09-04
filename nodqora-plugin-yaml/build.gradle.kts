plugins {
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports { mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.1") }
}

dependencies {
    // ADR-0015: a plugin depends on plugin-api and nothing else of ours.
    implementation(project(":nodqora-plugin-api"))
    implementation("org.springframework:spring-context")
    implementation("org.yaml:snakeyaml")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
