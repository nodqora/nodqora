plugins {
    // Lets the Java toolchain below provision a JDK 21 that is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "nodqora"

include(
    "nodqora-plugin-api",
    "nodqora-core",
    "nodqora-plugin-yaml",
    "nodqora-app",
)
