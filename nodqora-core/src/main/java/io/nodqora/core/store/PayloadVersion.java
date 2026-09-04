package io.nodqora.core.store;

/**
 * ADR-0080: one application-level constant, written onto every stored row, never migrated.
 *
 * <p>At startup any entry or header whose version differs is <em>discarded</em>; the plugins
 * repopulate within one cadence. In-place JSON migration was rejected as machinery to preserve data
 * that is about to be overwritten anyway, and tolerant deserialization forever was rejected for
 * having no forcing function.
 *
 * <p>It is one version rather than one per plugin so that a bump discards all plugins together: the
 * graph goes <b>empty and repopulating</b> rather than <b>partial and wrong</b>. Empty is honest;
 * partial is misleading. The accepted cost is an empty graph for up to one discovery cadence after a
 * deliberate developer act, and it is not to be defended against (ADR-0101).
 */
public final class PayloadVersion {

    /** Bump when the shape of a stored payload changes. */
    public static final int CURRENT = 1;

    private PayloadVersion() {}
}
