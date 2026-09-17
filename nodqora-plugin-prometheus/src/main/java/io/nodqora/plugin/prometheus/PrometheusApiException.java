// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.prometheus;

/** A query that produced no vector, with a message fit for an outcome's reasons. */
class PrometheusApiException extends RuntimeException {

    PrometheusApiException(String message) {
        super(message);
    }

    PrometheusApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
