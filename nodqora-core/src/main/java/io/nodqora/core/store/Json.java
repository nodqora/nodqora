package io.nodqora.core.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.UncheckedIOException;
import org.springframework.stereotype.Component;

/**
 * Serialization for stored payloads and, more importantly, the one place a stored value and a
 * freshly folded value are made comparable.
 *
 * <p>ADR-0073 records that a {@code jsonb} round trip is not byte-identical to what a plugin
 * emitted, and calls that "harmless for a value-comparing diff". It is only harmless if both sides
 * of the comparison are normalized the same way: a plugin emitting {@code retentionMs} as a
 * {@code long} and the database handing it back as an {@code int} compares unequal on every poll,
 * which is precisely ADR-0050's silent failure — {@code updatedAt} degenerating into a poll clock
 * with nothing looking broken.
 *
 * <p>{@link #canonical} closes that by routing both sides through the same parse, so the number
 * width, the key order and the whitespace are decided once rather than by where the value came from.
 */
@Component
public class Json {

    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        return unchecked(() -> mapper.writeValueAsString(value));
    }

    public <T> T read(String json, Class<T> type) {
        return unchecked(() -> mapper.readValue(json, type));
    }

    public <T> T read(String json, TypeReference<T> type) {
        return unchecked(() -> mapper.readValue(json, type));
    }

    /** The comparable form of a value, whether it came from the fold or from the database. */
    public JsonNode canonical(Object value) {
        return value instanceof String text ? parse(text) : parse(write(value));
    }

    private JsonNode parse(String json) {
        return unchecked(() -> mapper.readTree(json));
    }

    /**
     * Every payload here has already been produced or accepted by this process, so a Jackson failure
     * is a bug in a mapping rather than a condition a caller could handle.
     */
    private static <T> T unchecked(JacksonCall<T> call) {
        try {
            return call.get();
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    private interface JacksonCall<T> {
        T get() throws JsonProcessingException;
    }
}
