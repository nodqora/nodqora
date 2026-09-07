// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kafka;

/**
 * The cluster could not be reached, or a call it was asked for did not complete.
 *
 * <p>ADR-0026's split applies here as elsewhere: the plugin catches this per call, because only the
 * caller of this seam can tell "the cluster is unreachable" ({@code FAILED}) from "the topics listed
 * and their configs did not" ({@code PARTIAL}) — and ADR-0042 makes the second of those a named
 * reason rather than a shrug, since having the topic proves we are authorized to see it and an empty
 * config can only mean the second, distinct {@code DescribeConfigs} ACL is missing.
 */
public class KafkaApiException extends RuntimeException {

    public KafkaApiException(String message, Throwable cause) {
        super(message, cause);
    }

    public KafkaApiException(String message) {
        super(message);
    }
}
