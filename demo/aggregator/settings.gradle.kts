plugins {
    // Lets the toolchain provision a JDK 21 that may not be installed locally, exactly as the
    // product build does.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "market-aggregator"
