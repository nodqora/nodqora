// SPDX-License-Identifier: Apache-2.0
package io.nodqora.demo.aggregator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/**
 * Windows are cut on the exchange's own trade time, not on when Connect happened to poll.
 *
 * <p>That is what makes the deduplication in {@link Bar} sufficient: overlapping polls republish a
 * trade seconds later, and event time puts the copy back in the window that already folded it in,
 * where its id is no longer greater than the highest seen. On ingestion time the copy would land in
 * a later window and be counted twice.
 */
public class TradeTimeExtractor implements TimestampExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        if (record.value() instanceof String json) {
            try {
                JsonNode time = MAPPER.readTree(json).get("time");
                if (time != null && !time.isNull()) {
                    return Instant.parse(time.asText()).toEpochMilli();
                }
            } catch (Exception ignored) {
                // An unparseable trade time is not worth failing the task for: the broker's own
                // append time is a good enough second choice, and the record still counts.
            }
        }
        return record.timestamp() > 0 ? record.timestamp() : partitionTime;
    }
}
