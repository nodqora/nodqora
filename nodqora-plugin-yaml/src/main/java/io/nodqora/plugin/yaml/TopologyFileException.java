package io.nodqora.plugin.yaml;

/**
 * ADR-0064: anything invalid anywhere in the directory makes the snapshot {@code FAILED}, and
 * {@code FAILED} changes nothing. The message names the file, because unlike ADR-0051's merge
 * diagnostics the plugin has the filenames in hand.
 */
class TopologyFileException extends RuntimeException {

    TopologyFileException(String message) {
        super(message);
    }
}
