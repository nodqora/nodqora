// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.kubernetes;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ADR-0099's recording, replayed through {@link KubernetesApi} — the plugin's own outbound-client
 * interface, which is the one uniform seam for all three cluster-facing plugins.
 *
 * <p>One file per namespace, because a namespace is exactly what one list call covers. A namespace
 * with no recording throws, which is the honest answer: it is what an unreachable namespace does,
 * and it is what lets a test drive ADR-0026's {@code PARTIAL}/{@code FAILED} split without a mock.
 *
 * <p><b>A scenario is an overlay, not a second recording.</b> Directories are searched in order and
 * the first that holds the namespace wins, so {@code kubernetes/incident/} carries only the file the
 * incident actually changes — one Deployment's {@code readyReplicas} — and staging keeps reading the
 * baseline. §8's two scenarios differ by a handful of numbers over the same objects, so a full second
 * copy would be four fifths duplication, and the four fifths is where a divergence would hide.
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

    private final List<Path> directories;

    public RecordedKubernetesApi() {
        this(DEFAULT_DIRECTORY);
    }

    /** Searched in order, first hit wins: {@code overlay…, baseline}. */
    public RecordedKubernetesApi(Path... directories) {
        this.directories = List.of(directories);
    }

    /** The named §8 scenario layered over the baseline recording. */
    public static RecordedKubernetesApi scenario(String name) {
        return "baseline".equals(name)
                ? new RecordedKubernetesApi()
                : new RecordedKubernetesApi(DEFAULT_DIRECTORY.resolve(name), DEFAULT_DIRECTORY);
    }

    @Override
    public NamespaceObjects list(KubernetesConfig config, String namespace) {
        Path recording = directories.stream()
                .map(directory -> directory.resolve(namespace + ".json"))
                .filter(Files::isReadable)
                .findFirst()
                .orElseThrow(() ->
                        new KubernetesApiException("no recorded objects for namespace " + namespace));
        try {
            return MAPPER.readValue(Files.readString(recording), NamespaceObjects.class);
        } catch (IOException e) {
            throw new KubernetesApiException("recording %s is unreadable".formatted(recording), e);
        }
    }
}
