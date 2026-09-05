package io.nodqora.plugin.kafka;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ADR-0099's recording, replayed through {@link KafkaApi} — the plugin's own outbound-client
 * interface, which is the one uniform seam for all three cluster-facing plugins.
 *
 * <p><b>One file per cluster</b>, named for the host in the configured {@code bootstrap}, because a
 * cluster is exactly what one {@code listTopics} covers. A cluster with no recording throws, which is
 * the honest answer: it is what an unreachable broker does, and it is what lets a test drive
 * ADR-0046's {@code FAILED} branch without a mock.
 *
 * <p><b>A scenario is an overlay, not a second recording.</b> Directories are searched in order and
 * the first that holds the cluster wins, so {@code kafka/incident/} carries only the file the incident
 * actually changes and staging keeps reading the baseline.
 *
 * <p>The recording holds committed offsets beside high watermarks rather than a lag, because the two
 * caveats ADR-0025 attaches to lag — skip a partition with no commit, clamp a negative difference to
 * zero — live <em>above</em> this seam and would be untestable if the recording had already applied
 * them.
 */
public final class RecordedKafkaApi implements KafkaApi {

    /** The checked-in recording, alongside the YAML topology it is the other half of. */
    public static final Path DEFAULT_DIRECTORY = Path.of("fixtures", "reference-pipeline", "kafka");

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final List<Path> directories;

    public RecordedKafkaApi() {
        this(DEFAULT_DIRECTORY);
    }

    /** Searched in order, first hit wins: {@code overlay…, baseline}. */
    public RecordedKafkaApi(Path... directories) {
        this.directories = List.of(directories);
    }

    /** The named §8 scenario layered over the baseline recording. */
    public static RecordedKafkaApi scenario(String name) {
        return "baseline".equals(name)
                ? new RecordedKafkaApi()
                : new RecordedKafkaApi(DEFAULT_DIRECTORY.resolve(name), DEFAULT_DIRECTORY);
    }

    @Override
    public List<String> listTopics(KafkaConfig config) {
        // The whole cluster's topic list, exactly as `listTopics(listInternal=false)` returns it —
        // including the topics the prefix scope will reject, because rejecting them is the rule
        // under test and a recording that had already applied it would prove nothing.
        return cluster(config).topics().stream().map(RecordedTopic::name).toList();
    }

    @Override
    public List<ObservedTopic> describeTopics(KafkaConfig config, List<String> names) {
        List<String> asked = names.stream().map(RecordedKafkaApi::folded).toList();
        return cluster(config).topics().stream()
                .filter(topic -> asked.contains(folded(topic.name())))
                .map(topic -> new ObservedTopic(
                        topic.name(), topic.partitions(), topic.replicationFactor(), topic.configs()))
                .toList();
    }

    @Override
    public List<ObservedGroup> consumerGroupOffsets(KafkaConfig config, List<String> groupIds) {
        List<String> asked = groupIds.stream().map(RecordedKafkaApi::folded).toList();
        return cluster(config).groups().stream()
                .filter(group -> asked.contains(folded(group.groupId())))
                .map(group -> new ObservedGroup(group.groupId(), group.partitions()))
                .toList();
    }

    private RecordedCluster cluster(KafkaConfig config) {
        String host = config.bootstrap().split(",")[0].split(":")[0].trim();
        Path recording = directories.stream()
                .map(directory -> directory.resolve(host + ".json"))
                .filter(Files::isReadable)
                .findFirst()
                .orElseThrow(() -> new KafkaApiException("no recorded objects for cluster " + host));
        try {
            return MAPPER.readValue(Files.readString(recording), RecordedCluster.class);
        } catch (IOException e) {
            throw new KafkaApiException("recording %s is unreadable".formatted(recording), e);
        }
    }

    private static String folded(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    record RecordedCluster(List<RecordedTopic> topics, List<RecordedGroup> groups) {

        RecordedCluster {
            topics = topics == null ? List.of() : List.copyOf(topics);
            groups = groups == null ? List.of() : List.copyOf(groups);
        }
    }

    record RecordedTopic(String name, Integer partitions, Integer replicationFactor, Map<String, String> configs) {

        RecordedTopic {
            configs = configs == null ? Map.of() : Map.copyOf(configs);
        }
    }

    record RecordedGroup(String groupId, List<PartitionOffsets> partitions) {

        RecordedGroup {
            partitions = partitions == null ? List.of() : List.copyOf(partitions);
        }
    }
}
