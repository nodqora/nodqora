// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kubernetes;

/**
 * A namespace could not be listed, for a reason this plugin can put in its own words. The plugin
 * catches any failure of a list per namespace, because ADR-0026 asks it to tell "the API server is
 * unreachable" ({@code FAILED}) from "some list calls succeeded and others did not" ({@code PARTIAL})
 * — a distinction only the caller of this seam can make.
 *
 * <p>fabric8's own exceptions cross the seam unwrapped rather than as this one's message, because
 * that message can quote the kubeconfig: {@link ListingFailure} reads their chain and decides which
 * parts are safe to show.
 */
public class KubernetesApiException extends RuntimeException {

    public KubernetesApiException(String message, Throwable cause) {
        super(message, cause);
    }

    public KubernetesApiException(String message) {
        super(message);
    }
}
