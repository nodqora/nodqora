// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KafkaConfigTest {

    @Test
    void a_client_property_is_printed_by_its_key_and_never_its_value() {
        KafkaConfig config = new KafkaConfig(
                "kafka.internal:9092",
                Map.of("sasl.jaas.config", "PlainLoginModule required username=\"nodqora\" password=\"hunter2\";"),
                new KafkaConfig.Topics(List.of("payments."), List.of()),
                new KafkaConfig.Lag(1000L, Map.of()),
                null);

        assertThat(config).asString().contains("kafka.internal:9092", "sasl.jaas.config").doesNotContain("hunter2");
    }
}
