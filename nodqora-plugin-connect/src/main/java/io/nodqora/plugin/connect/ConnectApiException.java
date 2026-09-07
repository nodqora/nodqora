// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.connect;

/**
 * The cluster could not be reached, or answered with something that is not a connector listing.
 *
 * <p>ADR-0042: an error on {@code GET /connectors} is {@code FAILED} — the whole scope went dark, and
 * ADR-0046 then leaves the store untouched so the connectors go visibly stale rather than being
 * deleted. A <em>per-connector</em> failure is a different thing and is not thrown: it arrives as an
 * {@code error} on one entry of a listing that otherwise succeeded, and becomes a {@code PARTIAL}
 * naming that connector.
 */
public class ConnectApiException extends RuntimeException {

    public ConnectApiException(String message, Throwable cause) {
        super(message, cause);
    }

    public ConnectApiException(String message) {
        super(message);
    }
}
