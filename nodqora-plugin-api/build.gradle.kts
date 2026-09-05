// SPDX-License-Identifier: Apache-2.0
// ADR-0015: immutable records only. No Spring, no persistence, no core dependency.
dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
