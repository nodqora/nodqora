package io.nodqora.plugin.kubernetes;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ADR-0099's recording, replayed through {@link KubernetesApi} — the plugin's own outbound-client
 * interface, which is the one uniform seam for all three cluster-facing plugins.
 *
 * <p>One file per namespace, because a namespace is exactly what one list call covers. A namespace
 * with no recording throws, which is the honest answer: it is what an unreachable namespace does,
 * and it is what lets a test drive ADR-0026's {@code PARTIAL}/{@code FAILED} split without a mock.
 *
 * <p>It lives in test fixtures rather than in {@code src/test} so that {@code nodqora-app}'s
 * integration tests replay the same objects through the same seam. A second copy would drift, which
 * is the fault ADR-0099 avoided for the YAML half by making the fixtures the product's own format.
 */
public final class RecordedKubernetesApi implements KubernetesApi {

    /** The checked-in recording, alongside the YAML topology it is the other half of. */
    public static final Path DEFAULT_DIRECTORY = Path.of("fixtures", "reference-pipeline", "kubernetes");

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            // So the recording reads `"kind": "Deployment"` — the Kubernetes spelling — rather than
            // shouting an enum constant at a reader comparing it against the fixture document.
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private final Path directory;

    public RecordedKubernetesApi() {
        this(DEFAULT_DIRECTORY);
    }

    public RecordedKubernetesApi(Path directory) {
        this.directory = directory;
    }

    @Override
    public NamespaceObjects list(KubernetesConfig config, String namespace) {
        Path recording = directory.resolve(namespace + ".json");
        if (!Files.isReadable(recording)) {
            throw new KubernetesApiException("no recorded objects for namespace " + namespace);
        }
        try {
            return MAPPER.readValue(Files.readString(recording), NamespaceObjects.class);
        } catch (IOException e) {
            throw new KubernetesApiException("recording %s is unreadable".formatted(recording), e);
        }
    }
}
