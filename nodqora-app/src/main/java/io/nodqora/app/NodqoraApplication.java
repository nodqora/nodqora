package io.nodqora.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * One JAR, one process (ADR-0015). The plugins are ordinary {@code @Component}s in this deployable,
 * collected by injecting {@code List<Plugin>} — no classloader isolation, no separate artifacts, and
 * no compile-time knowledge of any of them in {@code nodqora-core}.
 *
 * <p>This module exists precisely so that the wiring lives somewhere that is allowed to see both
 * sides. A future out-of-process plugin changes only the registration mechanism, not the interfaces.
 */
@SpringBootApplication(scanBasePackages = "io.nodqora")
public class NodqoraApplication {

    public static void main(String[] args) {
        SpringApplication.run(NodqoraApplication.class, args);
    }
}
