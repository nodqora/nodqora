package io.nodqora.plugin.yaml;

/**
 * Something invalid was found somewhere in the topology directory (ADR-0064).
 *
 * <p>The name is deliberately not {@code TopologyFileException}: <b>the environment is the unit of
 * failure</b>, so one bad file fails the whole directory. The message still names the file, because
 * unlike ADR-0051's merge diagnostics the plugin has the filenames in hand and naming them costs
 * nothing — but say the <em>directory</em> failed, not the file.
 */
class InvalidTopologyException extends RuntimeException {

    InvalidTopologyException(String message) {
        super(message);
    }
}
