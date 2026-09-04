package io.nodqora.plugin.api;

/**
 * A registered contributor of graph data (ADR-0010).
 *
 * <p>The id is API surface, not implementation detail: it is the {@code metadata} key, the
 * {@code sources[]} entry, the {@code backings[].plugin} value and {@code TypeDescriptor.source}.
 *
 * <p>A plugin is a stateless thread-safe singleton; all per-run state arrives as arguments
 * (ADR-0012). A capability is present <em>iff</em> the bean implements its interface — that is the
 * whole of capability negotiation.
 *
 * @param <C> the plugin's configuration type, bound from the config file at startup (ADR-0014)
 */
public interface Plugin<C> {

    String id();

    String displayLabel();

    Class<C> configType();
}
