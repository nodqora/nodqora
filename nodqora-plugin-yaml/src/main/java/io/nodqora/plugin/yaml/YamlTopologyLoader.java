// SPDX-License-Identifier: Apache-2.0
package io.nodqora.plugin.yaml;

import io.nodqora.plugin.api.Backing;
import io.nodqora.plugin.api.DiscoveredEdge;
import io.nodqora.plugin.api.DiscoveredNode;
import io.nodqora.plugin.api.DiscoveredOwner;
import io.nodqora.plugin.api.DiscoveryResult;
import io.nodqora.plugin.api.Link;
import io.nodqora.plugin.api.Outcome;
import io.nodqora.plugin.api.TypeDescriptor;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads one environment's topology directory into a {@link DiscoveryResult} (ADR-0061).
 *
 * <p>The whole directory is the unit of failure (ADR-0064): every {@code *.yaml} in it is read
 * together as one snapshot, and anything invalid anywhere makes the snapshot {@code FAILED} so that
 * an indentation slip cannot delete the four declared nodes and seven edges {@code yaml} owns. Say
 * the <em>directory</em> failed, not the file.
 */
class YamlTopologyLoader {

    static final String PLUGIN_ID = "yaml";

    /**
     * ADR-0013, ADR-0022. `consumerGroups:` is the one backing route `yaml` may write, and the
     * object it names belongs to another plugin's technology domain — which is exactly the point:
     * the plugin that can read this group's lag is not the one that can say whose lag it is.
     * Naming a foreign domain here is fine; ADR-0015's identifier ban is on the core, not plugins.
     */
    private static final String CONSUMER_GROUP_DOMAIN = "kafka";

    private static final String CONSUMER_GROUP_KIND = "consumer-group";

    private static final Set<String> TOP_LEVEL_KEYS = Set.of("environment", "owners", "types", "nodes");
    private static final Set<String> OWNER_KEYS = Set.of("key", "displayName", "channel", "onCall");
    private static final Set<String> TYPE_KEYS = Set.of("type", "label", "category", "icon");
    private static final Set<String> LINK_KEYS = Set.of("rel", "label", "url");
    private static final Set<String> NODE_KEYS = nodeKeys();

    private static Set<String> nodeKeys() {
        Set<String> keys = new LinkedHashSet<>(
                List.of("key", "type", "displayName", "description", "owner", "links", "consumerGroups"));
        Verb.keys().forEach(keys::add);
        return Set.copyOf(keys);
    }

    DiscoveryResult load(String environmentKey, Path directory) {
        try {
            return read(environmentKey, directory);
        } catch (InvalidTopologyException e) {
            return DiscoveryResult.failed(e.getMessage());
        } catch (IOException e) {
            return DiscoveryResult.failed(
                    "topology directory %s is unreadable: %s".formatted(directory, e.getMessage()));
        }
    }

    private DiscoveryResult read(String environmentKey, Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new InvalidTopologyException("topology directory %s does not exist".formatted(directory));
        }

        Accumulator accumulator = new Accumulator();
        for (Path file : topologyFiles(directory)) {
            readFile(environmentKey, file, accumulator);
        }
        return accumulator.toResult();
    }

    private List<Path> topologyFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            // Sorted so a failure message is stable; the snapshot itself is order-independent.
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yaml"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private void readFile(String environmentKey, Path file, Accumulator accumulator) throws IOException {
        String name = file.getFileName().toString();
        Map<String, Object> document = parse(file, name);

        rejectUnknownKeys(name, "file", document.keySet(), TOP_LEVEL_KEYS);
        String declared = string(document.get("environment"));
        if (declared == null) {
            throw new InvalidTopologyException("%s declares no environment:".formatted(name));
        }
        if (!declared.equals(environmentKey)) {
            throw new InvalidTopologyException(
                    "%s declares environment: %s but sits in the directory bound to %s"
                            .formatted(name, declared, environmentKey));
        }

        for (Map<String, Object> owner : mappings(name, document.get("owners"), "owners")) {
            accumulator.addOwner(name, owner);
        }
        for (Map<String, Object> type : mappings(name, document.get("types"), "types")) {
            accumulator.addType(name, type);
        }
        for (Map<String, Object> node : mappings(name, document.get("nodes"), "nodes")) {
            accumulator.addNode(name, node);
        }
    }

    private Map<String, Object> parse(Path file, String name) throws IOException {
        LoaderOptions options = new LoaderOptions();
        // ADR-0064: a key declared twice is an error, at every level.
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try (Reader reader = Files.newBufferedReader(file)) {
            Object loaded = yaml.load(reader);
            if (loaded == null) {
                return Map.of();
            }
            if (!(loaded instanceof Map<?, ?> map)) {
                throw new InvalidTopologyException("%s is not a mapping".formatted(name));
            }
            return castKeys(name, map);
        } catch (InvalidTopologyException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvalidTopologyException("%s will not parse: %s".formatted(name, rootMessage(e)));
        }
    }

    private static String rootMessage(Throwable e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName() : message.replace('\n', ' ');
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castKeys(String name, Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw new InvalidTopologyException("%s has a non-string key %s".formatted(name, key));
            }
            result.put(text, value);
        });
        return result;
    }

    private List<Map<String, Object>> mappings(String name, Object value, String section) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new InvalidTopologyException("%s: %s must be a list".formatted(name, section));
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> map)) {
                throw new InvalidTopologyException("%s: every %s entry must be a mapping".formatted(name, section));
            }
            result.add(castKeys(name, map));
        }
        return result;
    }

    private static void rejectUnknownKeys(String file, String what, Set<String> present, Set<String> allowed) {
        for (String key : present) {
            if (!allowed.contains(key)) {
                throw new InvalidTopologyException(
                        "%s: unknown %s key '%s'".formatted(file, what, key));
            }
        }
    }

    private static String string(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new InvalidTopologyException("expected a string but found %s".formatted(value));
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static List<String> strings(String file, Object value, String what) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new InvalidTopologyException("%s: %s must be a list".formatted(file, what));
        }
        return list.stream().map(YamlTopologyLoader::string).filter(java.util.Objects::nonNull).toList();
    }

    private static String folded(String key) {
        return key.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Collects the whole directory before deciding an outcome. Every claimed key remembers the file
     * that claimed it, so a contest can name both files (ADR-0064).
     */
    private static final class Accumulator {

        private final Map<String, String> nodeFiles = new LinkedHashMap<>();
        private final Map<String, String> ownerFiles = new LinkedHashMap<>();
        private final Map<String, String> typeFiles = new LinkedHashMap<>();
        private final List<DiscoveredNode> nodes = new ArrayList<>();
        private final List<DiscoveredEdge> edges = new ArrayList<>();
        private final List<DiscoveredOwner> owners = new ArrayList<>();
        private final List<TypeDescriptor> descriptors = new ArrayList<>();

        void addOwner(String file, Map<String, Object> stanza) {
            rejectUnknownKeys(file, "owner", stanza.keySet(), OWNER_KEYS);
            String key = required(file, stanza, "owner");
            claim(ownerFiles, folded(key), file, "owner", key);
            owners.add(new DiscoveredOwner(
                    key,
                    string(stanza.get("displayName")),
                    string(stanza.get("channel")),
                    string(stanza.get("onCall"))));
        }

        void addType(String file, Map<String, Object> stanza) {
            rejectUnknownKeys(file, "type", stanza.keySet(), TYPE_KEYS);
            String type = string(stanza.get("type"));
            if (type == null) {
                throw new InvalidTopologyException("%s: a types entry has no type:".formatted(file));
            }
            claim(typeFiles, type, file, "type", type);
            descriptors.add(new TypeDescriptor(
                    type,
                    string(stanza.get("label")),
                    string(stanza.get("category")),
                    string(stanza.get("icon")),
                    PLUGIN_ID));
        }

        void addNode(String file, Map<String, Object> stanza) {
            rejectUnknownKeys(file, "node", stanza.keySet(), NODE_KEYS);
            String key = required(file, stanza, "node");
            claim(nodeFiles, folded(key), file, "node", key);

            nodes.add(new DiscoveredNode(
                    key,
                    string(stanza.get("type")),
                    string(stanza.get("displayName")),
                    string(stanza.get("description")),
                    string(stanza.get("owner")),
                    links(file, stanza.get("links")),
                    backings(file, stanza.get("consumerGroups")),
                    Map.of()));

            for (Verb verb : Verb.values()) {
                for (String target : strings(file, stanza.get(verb.key()), verb.key())) {
                    edges.add(new DiscoveredEdge(
                            verb.fromKey(key, target), verb.toKey(key, target), verb.relation()));
                }
            }
        }

        private static String required(String file, Map<String, Object> stanza, String what) {
            String key = string(stanza.get("key"));
            if (key == null) {
                throw new InvalidTopologyException("%s: a %s stanza has no key:".formatted(file, what));
            }
            return key;
        }

        private static void claim(Map<String, String> claims, String identity, String file, String what, String shown) {
            String previous = claims.putIfAbsent(identity, file);
            if (previous != null) {
                throw new InvalidTopologyException(
                        "%s is declared twice: %s and %s".formatted(what + " " + shown, previous, file));
            }
        }

        private static List<Link> links(String file, Object value) {
            List<Link> result = new ArrayList<>();
            for (Object element : value == null ? List.of() : asList(file, value)) {
                Map<String, Object> stanza = castKeys(file, (Map<?, ?>) element);
                rejectUnknownKeys(file, "link", stanza.keySet(), LINK_KEYS);
                String rel = string(stanza.get("rel"));
                String url = string(stanza.get("url"));
                if (rel == null || url == null) {
                    throw new InvalidTopologyException("%s: a link needs both rel: and url:".formatted(file));
                }
                result.add(new Link(rel, string(stanza.get("label")), url));
            }
            return result;
        }

        private static List<?> asList(String file, Object value) {
            if (value instanceof List<?> list) {
                if (list.stream().allMatch(element -> element instanceof Map<?, ?>)) {
                    return list;
                }
            }
            throw new InvalidTopologyException("%s: links must be a list of mappings".formatted(file));
        }

        private static List<Backing> backings(String file, Object consumerGroups) {
            return strings(file, consumerGroups, "consumerGroups").stream()
                    .map(group -> new Backing(CONSUMER_GROUP_DOMAIN, CONSUMER_GROUP_KIND, group))
                    .toList();
        }

        DiscoveryResult toResult() {
            // ADR-0047, ADR-0064: a directory that parses but yields no node stanza is an empty
            // scope, not an emptied topology. Deletion works by removing a stanza; it does not
            // work by emptying the directory.
            Outcome outcome = nodes.isEmpty()
                    ? Outcome.partial("the topology directory holds no node stanza")
                    : Outcome.complete();
            return new DiscoveryResult(nodes, edges, owners, descriptors, outcome);
        }
    }
}
