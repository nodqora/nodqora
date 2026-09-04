package io.nodqora.core.store;

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
        try {
            return mapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The comparable form of a value, whether it came from the fold or from the database. */
    public JsonNode canonical(Object value) {
        return value instanceof String text ? parse(text) : parse(write(value));
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
