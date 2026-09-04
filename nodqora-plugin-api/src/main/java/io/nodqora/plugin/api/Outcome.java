package io.nodqora.plugin.api;

import java.util.List;
import java.util.Objects;

/**
 * What a plugin says about how well it looked (ADR-0012, ADR-0026).
 *
 * <p>{@code reasons} carries a {@code PARTIAL}'s reasons or a {@code FAILED}'s cause; it is
 * empty for {@code COMPLETE}. An outcome is never a completeness guarantee — a plugin cannot
 * always tell it was blind.
 */
public record Outcome(OutcomeStatus status, List<String> reasons) {

    public Outcome {
        Objects.requireNonNull(status, "status");
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static Outcome complete() {
        return new Outcome(OutcomeStatus.COMPLETE, List.of());
    }

    public static Outcome partial(List<String> reasons) {
        return new Outcome(OutcomeStatus.PARTIAL, reasons);
    }

    public static Outcome partial(String reason) {
        return partial(List.of(reason));
    }

    public static Outcome failed(String cause) {
        return new Outcome(OutcomeStatus.FAILED, List.of(cause));
    }
}
