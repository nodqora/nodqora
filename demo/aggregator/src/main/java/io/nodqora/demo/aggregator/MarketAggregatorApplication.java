// SPDX-License-Identifier: Apache-2.0
package io.nodqora.demo.aggregator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafkaStreams;

/**
 * The demo pipeline's one hand-written service: it reads raw Coinbase trades off one topic per
 * product, folds them into one-minute OHLC/VWAP bars, and writes the bars to a second topic.
 *
 * <p>It exists to give Nodqora something real to observe — a Deployment with replicas, a consumer
 * group with lag, and an edge that no plugin can infer from configuration alone.
 */
@SpringBootApplication
@EnableKafkaStreams
public class MarketAggregatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketAggregatorApplication.class, args);
    }
}
