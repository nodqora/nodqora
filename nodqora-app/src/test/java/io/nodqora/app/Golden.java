// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads a checked-in golden document and normalizes the document under test to match it.
 *
 * <p>Only wall-clock instants are normalized, and only their <em>values</em>. Their positions are
 * still asserted, so a field appearing or disappearing fails — and so does a reordered collection,
 * which is the whole reason ADR-0099 chose golden documents over hand-written expectations.
 */
final class Golden {

    private static final Pattern INSTANT = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$");
    private static final String PLACEHOLDER = "<timestamp>";

    private Golden() {}

    static JsonNode read(ObjectMapper mapper, String name) {
        try {
            return mapper.readTree(Files.readString(Path.of("fixtures", "golden", name)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode normalize(JsonNode document) {
        if (document instanceof ObjectNode object) {
            List<String> fields = new ArrayList<>();
            object.fieldNames().forEachRemaining(fields::add);
            fields.forEach(field -> object.set(field, normalize(object.get(field))));
            return object;
        }
        if (document.isArray()) {
            for (int index = 0; index < document.size(); index++) {
                ((com.fasterxml.jackson.databind.node.ArrayNode) document)
                        .set(index, normalize(document.get(index)));
            }
            return document;
        }
        if (document.isTextual() && INSTANT.matcher(document.asText()).matches()) {
            return TextNode.valueOf(PLACEHOLDER);
        }
        return document;
    }
}
