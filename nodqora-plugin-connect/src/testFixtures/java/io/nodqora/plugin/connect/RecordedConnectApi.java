package io.nodqora.plugin.connect;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ADR-0099's recording, replayed through {@link ConnectApi} — the plugin's own outbound-client
 * interface, which is the one uniform seam for all three cluster-facing plugins.
 *
 * <p><b>One file per cluster</b>, named for the host in the configured {@code url}, because a
 * cluster is exactly what one {@code GET /connectors} covers — the same reasoning that gives
 * {@code kubernetes} one file per namespace. A cluster with no recording throws, which is the honest
 * answer: it is what an unreachable cluster does, and it is what lets a test drive ADR-0042's
 * {@code FAILED} branch without a mock.
 *
 * <p><b>A scenario is an overlay, not a second recording.</b> Directories are searched in order and
 * the first that holds the cluster wins, so {@code connect/incident/} carries only the file the
 * incident actually changes and staging keeps reading the baseline.
 *
 * <p>One recording serves both capabilities, because one cluster does: {@code info} is what the
 * connector is and {@code status} is how it is doing, and §8's scenarios are expressed by changing a
 * state in this file rather than by stubbing a health method.
 */
public final class RecordedConnectApi implements ConnectApi {

    /** The checked-in recording, alongside the YAML topology it is the other half of. */
    public static final Path DEFAULT_DIRECTORY = Path.of("fixtures", "reference-pipeline", "connect");

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final List<Path> directories;

    public RecordedConnectApi() {
        this(DEFAULT_DIRECTORY);
    }

    /** Searched in order, first hit wins: {@code overlay…, baseline}. */
    public RecordedConnectApi(Path... directories) {
        this.directories = List.of(directories);
    }

    /** The named §8 scenario layered over the baseline recording. */
    public static RecordedConnectApi scenario(String name) {
        return "baseline".equals(name)
                ? new RecordedConnectApi()
                : new RecordedConnectApi(DEFAULT_DIRECTORY.resolve(name), DEFAULT_DIRECTORY);
    }

    @Override
    public List<ConnectorInfo> connectors(ConnectConfig config) {
        return cluster(config).connectors().stream()
                .map(recorded -> new ConnectorInfo(
                        recorded.name(), recorded.type(), recorded.config(), recorded.error()))
                .toList();
    }

    @Override
    public List<ConnectorStatus> statuses(ConnectConfig config) {
        return cluster(config).connectors().stream()
                .map(recorded -> new ConnectorStatus(
                        recorded.name(), recorded.state(), recorded.tasks(), recorded.error()))
                .toList();
    }

    private RecordedCluster cluster(ConnectConfig config) {
        String host = URI.create(config.url()).getHost();
        Path recording = directories.stream()
                .map(directory -> directory.resolve(host + ".json"))
                .filter(Files::isReadable)
                .findFirst()
                .orElseThrow(() -> new ConnectApiException("no recorded connectors for cluster " + host));
        try {
            return MAPPER.readValue(Files.readString(recording), RecordedCluster.class);
        } catch (IOException e) {
            throw new ConnectApiException("recording %s is unreadable".formatted(recording), e);
        }
    }

    /**
     * The recorded shape is one entry per connector carrying <em>both</em> expansions, rather than
     * two lists that could disagree about which connectors exist. Connect's own {@code expand} form
     * is keyed by connector name for the same reason.
     */
    record RecordedCluster(List<RecordedConnector> connectors) {

        RecordedCluster {
            connectors = connectors == null ? List.of() : List.copyOf(connectors);
        }
    }

    record RecordedConnector(
            String name,
            String type,
            java.util.Map<String, String> config,
            String state,
            List<TaskStatus> tasks,
            String error) {

        RecordedConnector {
            config = config == null ? java.util.Map.of() : java.util.Map.copyOf(config);
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }
}
