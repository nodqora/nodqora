// SPDX-License-Identifier: Apache-2.0
package io.nodqora.core.config;

import java.util.ArrayList;
import java.util.stream.IntStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Undoes one thing Spring's relaxed binder does to a plugin's config slice.
 *
 * <p>A slice is bound as {@code Map<String, Object>} (ADR-0014: the core does not know a plugin's
 * shape, so it cannot bind into it), and a YAML list nested inside that arrives <b>keyed by
 * index</b> — {@code namespaces: [one, two]} becomes {@code {0=one, 1=two}} — because an indexed
 * property name is all the binder has to go on when the declared value type is {@code Object}.
 *
 * <p>Left alone, every list-valued key any plugin declares fails to bind, and it fails at startup
 * with a Jackson message naming {@code ArrayList} that says nothing about the file the operator
 * wrote. Restoring the list here keeps that failure mode from being every plugin's to rediscover.
 *
 * <p>The rule is deliberately narrow: a map is a list only when its keys are exactly
 * {@code 0 … n-1}. Its one limitation is the mirror of that — a configuration map genuinely keyed
 * by consecutive integers from zero would be read as a list. No MVP plugin has one, and this is a
 * workaround for something the binder does rather than a decision about the product, so it is
 * recorded here rather than as an ADR.
 */
final class ConfigLists {

    private ConfigLists() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> restore(Map<String, Object> config) {
        return (Map<String, Object>) walk(config);
    }

    private static Object walk(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> walked = new LinkedHashMap<>();
            map.forEach((key, element) -> walked.put(String.valueOf(key), walk(element)));
            return indexed(walked) ? ordered(walked) : walked;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ConfigLists::walk).toList();
        }
        return value;
    }

    private static boolean indexed(Map<String, Object> map) {
        return !map.isEmpty()
                && IntStream.range(0, map.size())
                        .allMatch(index -> map.containsKey(String.valueOf(index)));
    }

    /** By index rather than by encounter order, so the list is the one the file declares. */
    private static List<Object> ordered(Map<String, Object> map) {
        List<Object> list = new ArrayList<>(map.size());
        for (int index = 0; index < map.size(); index++) {
            list.add(map.get(String.valueOf(index)));
        }
        return List.copyOf(list);
    }
}
