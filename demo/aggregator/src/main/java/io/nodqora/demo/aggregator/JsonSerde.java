// SPDX-License-Identifier: Apache-2.0
package io.nodqora.demo.aggregator;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

/** Jackson over the wire, for the repartition topic and the windowed aggregate's state store. */
public final class JsonSerde<T> implements Serde<T> {

    // A field this version does not know about should not stop the pipeline: state stores and
    // repartition topics outlive a rolling deploy, so both sides of an upgrade read each other.
    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Class<T> type;

    public JsonSerde(Class<T> type) {
        this.type = type;
    }

    @Override
    public Serializer<T> serializer() {
        return (topic, data) -> {
            try {
                return data == null ? null : MAPPER.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new IllegalStateException("cannot serialize " + type.getSimpleName(), e);
            }
        };
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, bytes) -> {
            try {
                return bytes == null ? null : MAPPER.readValue(new String(bytes, StandardCharsets.UTF_8), type);
            } catch (Exception e) {
                throw new IllegalStateException("cannot deserialize " + type.getSimpleName(), e);
            }
        };
    }
}
