// SPDX-License-Identifier: Apache-2.0
package io.nodqora.demo.aggregator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Repartitioned;
import org.apache.kafka.streams.kstream.Suppressed;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <pre>
 * market.trades.&lt;product&gt;  ──▶  parse, key by product  ──▶  1-minute tumbling OHLC/VWAP  ──▶  market.ohlc.1m
 * </pre>
 *
 * <p>The product is carried by the topic name rather than by the payload: Coinbase's trades
 * endpoint answers for one product per URL and does not repeat it in the body, so one source
 * connector writes one topic and the topic name is the only place the symbol exists.
 */
@Configuration
public class MarketTopology {

    private static final Logger log = LoggerFactory.getLogger(MarketTopology.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Pattern inputTopics;
    private final String outputTopic;
    private final Duration window;
    private final Duration grace;

    public MarketTopology(
            @Value("${market.input-topic-pattern}") String inputTopicPattern,
            @Value("${market.output-topic}") String outputTopic,
            @Value("${market.window-seconds}") long windowSeconds,
            @Value("${market.grace-seconds}") long graceSeconds) {
        this.inputTopics = Pattern.compile(inputTopicPattern);
        this.outputTopic = outputTopic;
        this.window = Duration.ofSeconds(windowSeconds);
        this.grace = Duration.ofSeconds(graceSeconds);
    }

    @Bean
    public KStream<String, String> marketPipeline(StreamsBuilder builder) {
        JsonSerde<Trade> trades = new JsonSerde<>(Trade.class);
        JsonSerde<Bar> bars = new JsonSerde<>(Bar.class);

        KStream<String, Trade> parsed = builder
                .stream(
                        inputTopics,
                        Consumed.with(Serdes.String(), Serdes.String())
                                .withTimestampExtractor(new TradeTimeExtractor()))
                .process(ParseTrade::new);

        parsed
                // The source topic is partitioned by whatever the connector chose as a key, so the
                // rekey to product has to be materialized before anything stateful reads it.
                .repartition(Repartitioned.with(Serdes.String(), trades).withName("by-product"))
                .groupByKey(Grouped.with(Serdes.String(), trades))
                .windowedBy(TimeWindows.ofSizeAndGrace(window, grace))
                .aggregate(Bar::empty, (product, trade, bar) -> bar.add(trade), Materialized.with(Serdes.String(), bars))
                // One record per product per minute, emitted once the window can no longer change.
                // A bar therefore lands about window + grace after the minute it describes.
                .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
                .toStream()
                .filter((windowed, bar) -> bar != null && !bar.noTrades())
                .map((windowed, bar) -> {
                    Bar closed = bar.withWindow(
                            windowed.window().start(), windowed.window().end());
                    return KeyValue.pair(closed.product() + "|" + Instant.ofEpochMilli(closed.windowStart()), render(closed));
                })
                .to(outputTopic, Produced.with(Serdes.String(), Serdes.String()));

        return parsed.mapValues(Object::toString);
    }

    /** JSON text on the wire: the sink connectors read it back with {@code schemas.enable=false}. */
    private static String render(Bar bar) {
        ObjectNode doc = MAPPER.createObjectNode();
        doc.put("product", bar.product());
        doc.put("windowStart", Instant.ofEpochMilli(bar.windowStart()).toString());
        doc.put("windowEnd", Instant.ofEpochMilli(bar.windowEnd()).toString());
        // OpenSearch's dynamic mapping picks this up as the time field with no index template.
        doc.put("@timestamp", Instant.ofEpochMilli(bar.windowEnd()).toString());
        doc.put("open", bar.open());
        doc.put("high", bar.high());
        doc.put("low", bar.low());
        doc.put("close", bar.close());
        doc.put("vwap", bar.vwap());
        doc.put("volume", bar.volume());
        doc.put("trades", bar.trades());
        try {
            return MAPPER.writeValueAsString(doc);
        } catch (Exception e) {
            throw new IllegalStateException("cannot render bar", e);
        }
    }

    /**
     * Parses the raw trade and keys it by the product named in the topic:
     * {@code market.trades.btc-usd} is {@code BTC-USD}. A record that will not parse is dropped
     * with a log line rather than killing the task — a poll that returns an error document should
     * cost one record, not the pipeline.
     */
    private static final class ParseTrade implements Processor<String, String, String, Trade> {

        private ProcessorContext<String, Trade> context;

        @Override
        public void init(ProcessorContext<String, Trade> context) {
            this.context = context;
        }

        @Override
        public void process(Record<String, String> record) {
            String topic = context.recordMetadata().map(m -> m.topic()).orElse("");
            String product = topic.substring(topic.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
            try {
                JsonNode node = MAPPER.readTree(record.value());
                Trade trade = new Trade(
                        product,
                        node.get("trade_id").asLong(),
                        Double.parseDouble(node.get("price").asText()),
                        Double.parseDouble(node.get("size").asText()),
                        node.path("side").asText(""),
                        node.path("time").asText(""));
                context.forward(record.withKey(product).withValue(trade));
            } catch (Exception e) {
                log.warn("dropping unparseable trade on {}: {}", topic, e.toString());
            }
        }
    }
}
